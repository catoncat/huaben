package com.luna.storynote.audio

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.TreeMap
import java.util.UUID

class SingleDoubaoSession(
    private val authRepo: DoubaoAuthRepository,
    private val httpClient: OkHttpClient,
    private val onPartialUpdate: (() -> Unit)? = null,
) {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
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

    val isDone: Boolean get() = synchronized(lock) { done }
    val error: String get() = synchronized(lock) { if (finishedOk) "" else errorMsg }

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

                ws = httpClient.newWebSocket(req, Listener())
                val deadline = System.currentTimeMillis() + timeoutMs
                synchronized(lock) {
                    while (!ready && !done && errorMsg.isEmpty()) {
                        val rem = deadline - System.currentTimeMillis()
                        if (rem <= 0L) throw AsrException("豆包语音握手超时")
                        lock.wait(rem)
                    }
                    if (errorMsg.isNotEmpty() || !ready) {
                        throw AsrException(errorMsg.ifEmpty { "豆包语音会话关闭" })
                    }
                }
                return
            } catch (e: Exception) {
                lastErr = e
                close()
                if (attempt == 0) Thread.sleep(250)
            }
        }
        throw AsrException("豆包语音连接失败：${lastErr?.message ?: ""}", lastErr)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val startTask = FrontierProtocol.buildPacket(appKey, "StartTask", "", null, sessionId)
            val startSess = FrontierProtocol.buildPacket(
                appKey, "StartSession", FrontierProtocol.buildSessionConfig(), null, sessionId,
            )
            webSocket.send(startTask.toByteString())
            webSocket.send(startSess.toByteString())
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val parsed = FrontierProtocol.parseResponse(bytes.toByteArray()) ?: return
            var notifyUi = false
            synchronized(lock) {
                if (parsed.statusCode != 20_000_000L) {
                    errorMsg = "识别异常 (${parsed.statusCode})"
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
                        if (txt.isEmpty() || !TranscriptUtils.hasSpeechChars(txt)) continue
                        val extra = item.optJSONObject("extra")
                        val nonStream = extra?.optBoolean("nonstream_result", false) == true
                        val isInterim = if (item.has("is_interim")) item.optBoolean("is_interim", true) else true
                        val vadFin = item.optBoolean("is_vad_finished", false)
                        val isTerm = nonStream || (!isInterim && vadFin)
                        val idx = if (item.has("index") && !item.isNull("index")) item.optInt("index", -1) else -1
                        if (idx < 0) {
                            unindexedPartial = txt
                            if (isTerm &&
                                txt.count { it in TranscriptUtils.PUNCT_CHARS } >=
                                unindexedFinal.count { it in TranscriptUtils.PUNCT_CHARS }
                            ) {
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
                if (!finishedOk && errorMsg.isEmpty()) errorMsg = "连接已关闭"
                done = true
                lock.notifyAll()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(lock) {
                if (!finishedOk && errorMsg.isEmpty()) errorMsg = t.message ?: "网络连接中断"
                done = true
                lock.notifyAll()
            }
        }
    }

    fun sendAudioFrame(frame: ByteArray): Boolean {
        val socket = synchronized(lock) { if (done) return false else ws } ?: return false
        val meta = "{\"timestamp_ms\":${System.currentTimeMillis()}}"
        return socket.send(FrontierProtocol.buildPacket("", "TaskRequest", meta, frame, sessionId).toByteString())
    }

    fun composeText(preferFinal: Boolean): String = synchronized(lock) {
        var joined = ""
        for (k in (segments.keys + finals.keys).toSortedSet()) {
            val seg = if (preferFinal) (finals[k] ?: segments[k].orEmpty()) else (segments[k] ?: finals[k].orEmpty())
            if (TranscriptUtils.hasSpeechChars(seg)) joined = TranscriptUtils.joinTranscriptText(joined, seg)
        }
        val tail = if (preferFinal) unindexedFinal.ifEmpty { unindexedPartial } else unindexedPartial.ifEmpty { unindexedFinal }
        if (TranscriptUtils.hasSpeechChars(tail)) joined = TranscriptUtils.joinTranscriptText(joined, tail)
        joined
    }

    fun finish(timeoutMs: Long = 4500L): String {
        val socket = ws
        if (socket != null) {
            if (synchronized(lock) { !done }) {
                socket.send(FrontierProtocol.buildPacket(appKey, "FinishSession", "", null, sessionId).toByteString())
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
