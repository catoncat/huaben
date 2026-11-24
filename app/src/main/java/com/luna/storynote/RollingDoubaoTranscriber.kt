package com.luna.storynote

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.TreeMap
import java.util.UUID
import kotlin.math.abs

private val PUNCT_CHARS = setOf('。', '，', '！', '？', ',', '.', '!', '?', '；', '：')

fun joinTranscriptText(left: String, right: String): String {
    val a = left.trim()
    val b = right.trim()
    if (a.isEmpty()) return b
    if (b.isEmpty()) return a
    val lastA = a.last()
    val firstB = b.first()
    val asciiWord = lastA.code < 128 && lastA.isLetterOrDigit() && firstB.code < 128 && firstB.isLetterOrDigit()
    return if (asciiWord) "$a $b" else "$a$b"
}

private fun hasSpeechChars(text: String): Boolean = text.any { it.isLetterOrDigit() }

fun isFrameSilent(frame: ByteArray, peakThreshold: Int = 380): Boolean {
    if (frame.size < 2) return true
    var i = 0
    while (i + 1 < frame.size) {
        val lo = frame[i].toInt() and 0xFF
        val hi = frame[i + 1].toInt() // signed high byte
        val sample = (hi shl 8) or lo
        if (abs(sample) >= peakThreshold) return false
        i += 2
    }
    return true
}

class SingleDoubaoSession(
    private val authRepo: DoubaoAuthRepository,
    private val httpClient: OkHttpClient,
    private val onPartialUpdate: (() -> Unit)? = null,
) {
    private val lock = Object()
    private var ws: WebSocket? = null
    private var sessionId = UUID.randomUUID().toString().uppercase()
    private var appKey = ""

    private var ready = false
    private var done = false
    private var finishedOk = false
    private var errorMsg = ""

    private val segments = TreeMap<Int, String>()
    private val finals = TreeMap<Int, String>()
    private var unindexedPartial = ""
    private var unindexedFinal = ""

    val isDone: Boolean
        get() = synchronized(lock) { done }

    val error: String
        get() = synchronized(lock) { if (finishedOk) "" else errorMsg }

    fun connect(timeoutMs: Long = 8000L) {
        var lastErr: Exception? = null
        for (attempt in 0..1) {
            try {
                val creds = authRepo.resolve(forceRefresh = attempt > 0)
                appKey = creds.appKey
                sessionId = UUID.randomUUID().toString().uppercase()
                synchronized(lock) {
                    ready = false
                    done = false
                    finishedOk = false
                    errorMsg = ""
                }

                val req = Request.Builder()
                    .url(creds.wsUrl)
                    .header("User-Agent", DoubaoCredentials.ANDROID_USER_AGENT)
                    .header("proto-version", "v2")
                    .header("x-custom-keepalive", "true")
                    .header("x-keepalive-interval", "3")
                    .header("x-keepalive-timeout", "3600")
                    .build()

                val socket = httpClient.newWebSocket(req, Listener())
                ws = socket

                val deadline = System.currentTimeMillis() + timeoutMs
                synchronized(lock) {
                    while (!ready && !done && errorMsg.isEmpty()) {
                        val rem = deadline - System.currentTimeMillis()
                        if (rem <= 0L) throw AsrException("豆包语音会话握手超时")
                        lock.wait(rem)
                    }
                    if (errorMsg.isNotEmpty() || !ready) {
                        throw AsrException(errorMsg.ifEmpty { "豆包语音会话连接关闭" })
                    }
                }
                return
            } catch (e: Exception) {
                lastErr = e
                close()
                if (attempt == 0) Thread.sleep(250)
            }
        }
        throw AsrException("豆包语音连接失败：${lastErr?.message ?: "未知错误"}", lastErr)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val startTask = FrontierProtocol.buildPacket(appKey, "StartTask", "", null, sessionId)
            val startSess = FrontierProtocol.buildPacket(
                appKey,
                "StartSession",
                FrontierProtocol.buildSessionConfig(),
                null,
                sessionId,
            )
            webSocket.send(startTask.toByteString())
            webSocket.send(startSess.toByteString())
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val parsed = FrontierProtocol.parseResponse(bytes.toByteArray()) ?: return
            var notifyUi = false
            synchronized(lock) {
                if (parsed.statusCode != 20_000_000L) {
                    errorMsg = "豆包识别异常 (${parsed.statusCode})"
                    done = true
                    lock.notifyAll()
                    return
                }
                when (parsed.event) {
                    "SessionStarted" -> ready = true
                    "SessionFinished", "TaskFinished" -> {
                        finishedOk = true
                        done = true
                    }
                }
                val results = parsed.payloadJson?.optJSONArray("results")
                if (results != null) {
                    for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i) ?: continue
                        val txt = item.optString("text", "").trim()
                        if (txt.isEmpty() || !hasSpeechChars(txt)) continue
                        val extra = item.optJSONObject("extra")
                        val nonStream = extra?.optBoolean("nonstream_result", false) == true
                        val isInterim = if (item.has("is_interim")) item.optBoolean("is_interim", true) else true
                        val vadFin = item.optBoolean("is_vad_finished", false)
                        val isTerm = nonStream || (!isInterim && vadFin)

                        val idx = if (item.has("index") && !item.isNull("index")) item.optInt("index", -1) else -1
                        if (idx < 0) {
                            unindexedPartial = txt
                            if (isTerm && txt.count { it in PUNCT_CHARS } >= unindexedFinal.count { it in PUNCT_CHARS }) {
                                unindexedFinal = txt
                            }
                        } else {
                            segments[idx] = txt
                            if (isTerm) finals[idx] = txt
                        }
                        notifyUi = true
                    }
                }
                lock.notifyAll()
            }
            if (notifyUi) onPartialUpdate?.invoke()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) {
                if (!finishedOk && errorMsg.isEmpty()) {
                    errorMsg = "豆包连接关闭"
                }
                done = true
                lock.notifyAll()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(lock) {
                if (!finishedOk && errorMsg.isEmpty()) {
                    errorMsg = t.message ?: "网络连接中断"
                }
                done = true
                lock.notifyAll()
            }
        }
    }

    fun sendAudioFrame(frame: ByteArray): Boolean {
        val socket = synchronized(lock) {
            if (done) return false
            ws
        } ?: return false
        val meta = "{\"timestamp_ms\":${System.currentTimeMillis()}}"
        val pkt = FrontierProtocol.buildPacket("", "TaskRequest", meta, frame, sessionId)
        return socket.send(pkt.toByteString())
    }

    fun composeText(preferFinal: Boolean): String = synchronized(lock) {
        var joined = ""
        val keys = (segments.keys + finals.keys).toSortedSet()
        for (k in keys) {
            val seg = if (preferFinal) {
                finals[k] ?: segments[k].orEmpty()
            } else {
                segments[k] ?: finals[k].orEmpty()
            }
            if (hasSpeechChars(seg)) {
                joined = joinTranscriptText(joined, seg)
            }
        }
        val tail = if (preferFinal) {
            unindexedFinal.ifEmpty { unindexedPartial }
        } else {
            unindexedPartial.ifEmpty { unindexedFinal }
        }
        if (hasSpeechChars(tail)) {
            joined = joinTranscriptText(joined, tail)
        }
        joined
    }

    fun finish(timeoutMs: Long = 4500L): String {
        val socket = ws
        if (socket != null) {
            val shouldSend = synchronized(lock) { !done }
            if (shouldSend) {
                val fin = FrontierProtocol.buildPacket(appKey, "FinishSession", "", null, sessionId)
                socket.send(fin.toByteString())
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            synchronized(lock) {
                while (!done) {
                    val rem = deadline - System.currentTimeMillis()
                    if (rem <= 0L) break
                    lock.wait(rem)
                }
            }
        }
        val text = composeText(preferFinal = true).ifEmpty { composeText(preferFinal = false) }
        close()
        return text.trim()
    }

    fun close() {
        synchronized(lock) {
            done = true
            lock.notifyAll()
        }
        ws?.close(1000, "done")
        ws = null
    }
}

class RollingDoubaoTranscriber(
    private val authRepo: DoubaoAuthRepository,
    private val httpClient: OkHttpClient,
    private val softRolloverSec: Double = 120.0,
    private val hardRolloverSec: Double = 165.0,
    private val minSilenceForRollSec: Double = 0.45,
    private val framePaceMs: Long = 0L,
    private val onTranscriptChanged: ((String) -> Unit)? = null,
) {
    private val lock = Object()
    private val pcmStaging = ByteArrayOutputStream(4096)
    private val frameQueue = ArrayDeque<ByteArray>()
    private val committedParts = mutableListOf<String>()
    private var activeSession: SingleDoubaoSession? = null

    private var finishing = false
    private var cancelled = false
    private var rollovers = 0
    private var lastError = ""

    private val workerThread = Thread({ runLoop() }, "rolling-doubao-asr").apply {
        isDaemon = true
        start()
    }

    fun feedPcm(pcmBytes: ByteArray, length: Int = pcmBytes.size) {
        if (length <= 0) return
        synchronized(lock) {
            if (finishing || cancelled) return
            pcmStaging.write(pcmBytes, 0, length)
            val all = pcmStaging.toByteArray()
            pcmStaging.reset()
            var offset = 0
            while (offset + FrontierProtocol.PCM_FRAME_BYTES <= all.size) {
                frameQueue.addLast(all.copyOfRange(offset, offset + FrontierProtocol.PCM_FRAME_BYTES))
                offset += FrontierProtocol.PCM_FRAME_BYTES
            }
            if (offset < all.size) {
                pcmStaging.write(all, offset, all.size - offset)
            }
            lock.notifyAll()
        }
    }

    fun currentTranscript(): String {
        val (committed, session) = synchronized(lock) {
            var acc = ""
            for (p in committedParts) acc = joinTranscriptText(acc, p)
            Pair(acc, activeSession)
        }
        val live = session?.composeText(preferFinal = false).orEmpty()
        return joinTranscriptText(committed, live)
    }

    fun finish(timeoutMs: Long = 14000L): Triple<String, Int, String> {
        synchronized(lock) {
            if (!finishing) {
                val leftover = pcmStaging.toByteArray()
                pcmStaging.reset()
                if (leftover.isNotEmpty()) {
                    val padded = ByteArray(FrontierProtocol.PCM_FRAME_BYTES)
                    System.arraycopy(leftover, 0, padded, 0, minOf(leftover.size, padded.size))
                    frameQueue.addLast(padded)
                }
                finishing = true
                lock.notifyAll()
            }
        }
        workerThread.join(timeoutMs)
        val (committed, session, rolls, err) = synchronized(lock) {
            var acc = ""
            for (p in committedParts) acc = joinTranscriptText(acc, p)
            Quad(acc, activeSession, rollovers, lastError)
        }
        var finalTxt = committed
        if (session != null) {
            val tail = session.composeText(preferFinal = true).ifEmpty { session.composeText(preferFinal = false) }
            finalTxt = joinTranscriptText(finalTxt, tail)
            session.close()
        }
        return Triple(finalTxt.trim(), rolls, err)
    }

    fun cancel() {
        val sess = synchronized(lock) {
            cancelled = true
            frameQueue.clear()
            pcmStaging.reset()
            lock.notifyAll()
            activeSession
        }
        sess?.close()
        workerThread.join(1500L)
    }

    private fun emitUpdate() {
        onTranscriptChanged?.invoke(currentTranscript())
    }

    private fun commitSession(sess: SingleDoubaoSession, isRollover: Boolean) {
        val txt = sess.finish(timeoutMs = 4500L)
        synchronized(lock) {
            if (txt.isNotEmpty()) committedParts.add(txt)
            if (isRollover) rollovers++
            if (activeSession === sess) activeSession = null
        }
        emitUpdate()
    }

    private fun runLoop() {
        var sess: SingleDoubaoSession? = null
        var sessAudioSec = 0.0
        var silenceSec = 0.0
        var connectFailures = 0

        try {
            while (true) {
                val frame: ByteArray = synchronized(lock) {
                    while (frameQueue.isEmpty() && !finishing && !cancelled) {
                        lock.wait(500L)
                    }
                    if (cancelled) return
                    if (frameQueue.isEmpty() && finishing) break
                    frameQueue.removeFirst()
                }

                if (sess == null) {
                    val candidate = SingleDoubaoSession(authRepo, httpClient) { emitUpdate() }
                    try {
                        candidate.connect(timeoutMs = 7500L)
                        sess = candidate
                        sessAudioSec = 0.0
                        silenceSec = 0.0
                        connectFailures = 0
                        synchronized(lock) {
                            activeSession = sess
                            lastError = ""
                        }
                    } catch (e: Exception) {
                        connectFailures++
                        synchronized(lock) {
                            lastError = e.message ?: "连接豆包失败"
                            frameQueue.addFirst(frame)
                            if (connectFailures >= 6) {
                                // Keep memory bounded if offline for long; WAV on disk preserves 100% of audio
                                while (frameQueue.size > 250) frameQueue.removeFirst()
                            }
                        }
                        Thread.sleep(minOf(2000L, 300L * connectFailures))
                        continue
                    }
                }

                val silent = isFrameSilent(frame)
                val ok = sess.sendAudioFrame(frame)
                if (!ok || sess.isDone) {
                    val err = sess.error
                    commitSession(sess, isRollover = true)
                    sess = null
                    synchronized(lock) {
                        if (err.isNotEmpty()) lastError = err
                        frameQueue.addFirst(frame)
                    }
                    continue
                }

                val dur = frame.size.toDouble() / (FrontierProtocol.SAMPLE_RATE * 2.0)
                sessAudioSec += dur
                silenceSec = if (silent) (silenceSec + dur) else 0.0

                val shouldRoll = (sessAudioSec >= softRolloverSec && silenceSec >= minSilenceForRollSec) ||
                    (sessAudioSec >= hardRolloverSec)
                if (shouldRoll) {
                    commitSession(sess, isRollover = true)
                    sess = null
                    sessAudioSec = 0.0
                    silenceSec = 0.0
                }

                if (framePaceMs > 0L) {
                    Thread.sleep(framePaceMs)
                }
            }

            if (sess != null) {
                commitSession(sess, isRollover = false)
                sess = null
            }
        } finally {
            sess?.close()
            synchronized(lock) {
                activeSession = null
            }
        }
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    companion object {
        fun transcribeWavFile(
            wavFile: File,
            authRepo: DoubaoAuthRepository,
            httpClient: OkHttpClient,
            onProgress: ((String) -> Unit)? = null,
        ): Pair<String, Int> {
            if (!wavFile.isFile || wavFile.length() <= 44L) {
                throw AsrException("录音文件为空或不存在")
            }
            val pcmBytes = RandomAccessFile(wavFile, "r").use { raf ->
                val hdr = ByteArray(44)
                raf.readFully(hdr)
                val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                val channels = bb.getShort(22).toInt()
                val sampleRate = bb.getInt(24)
                val bits = bb.getShort(34).toInt()
                if (channels != 1 || sampleRate != FrontierProtocol.SAMPLE_RATE || bits != 16) {
                    throw AsrException("不支持的音频规格：${sampleRate}Hz/${channels}ch/${bits}bit")
                }
                val rem = (raf.length() - 44L).toInt()
                val raw = ByteArray(rem)
                raf.seek(44L)
                raf.readFully(raw)
                raw
            }

            val engine = RollingDoubaoTranscriber(
                authRepo = authRepo,
                httpClient = httpClient,
                softRolloverSec = 110.0,
                hardRolloverSec = 155.0,
                minSilenceForRollSec = 0.4,
                framePaceMs = 2L, // ~10x realtime speed for fast re-transcription
                onTranscriptChanged = onProgress,
            )
            engine.feedPcm(pcmBytes)
            val audioSec = pcmBytes.size.toDouble() / (FrontierProtocol.SAMPLE_RATE * 2.0)
            val maxWaitMs = maxOf(15_000L, (audioSec * 350.0).toLong() + 15_000L)
            val (text, rolls, err) = engine.finish(timeoutMs = maxWaitMs)
            if (text.isEmpty() && err.isNotEmpty()) {
                throw AsrException(err)
            }
            return Pair(text, rolls)
        }
    }
}
