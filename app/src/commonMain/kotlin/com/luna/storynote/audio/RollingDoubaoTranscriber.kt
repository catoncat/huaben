package com.luna.storynote.audio

import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

class RollingDoubaoTranscriber(
    private val authRepo: DoubaoAuthRepository,
    private val httpClient: OkHttpClient,
    private val softRolloverSec: Double = 120.0,
    private val hardRolloverSec: Double = 165.0,
    private val minSilenceForRollSec: Double = 0.45,
    private val framePaceMs: Long = 0L,
    private val onTranscriptChanged: ((String) -> Unit)? = null,
) {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()
    private val pcmStaging = ByteArrayOutputStream(4096)
    private val frameQueue = ArrayDeque<ByteArray>()
    private val committedParts = mutableListOf<String>()
    private var activeSession: SingleDoubaoSession? = null

    private var pauseRequested = false
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
            if (offset < all.size) pcmStaging.write(all, offset, all.size - offset)
            lock.notifyAll()
        }
    }

    /** Commits the current session cleanly while child pauses to think, avoiding idle socket timeouts. */
    fun notifyPaused() {
        synchronized(lock) {
            val leftover = pcmStaging.toByteArray()
            pcmStaging.reset()
            if (leftover.isNotEmpty()) {
                val padded = ByteArray(FrontierProtocol.PCM_FRAME_BYTES)
                System.arraycopy(leftover, 0, padded, 0, minOf(leftover.size, padded.size))
                frameQueue.addLast(padded)
            }
            pauseRequested = true
            lock.notifyAll()
        }
    }

    fun currentTranscript(): String {
        val (committed, session) = synchronized(lock) {
            var acc = ""
            for (p in committedParts) acc = TranscriptUtils.joinTranscriptText(acc, p)
            Pair(acc, activeSession)
        }
        val live = session?.composeText(preferFinal = false).orEmpty()
        return TranscriptUtils.joinTranscriptText(committed, live)
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
            for (p in committedParts) acc = TranscriptUtils.joinTranscriptText(acc, p)
            Quad(acc, activeSession, rollovers, lastError)
        }
        var finalTxt = committed
        if (session != null) {
            val tail = session.composeText(preferFinal = true).ifEmpty { session.composeText(preferFinal = false) }
            finalTxt = TranscriptUtils.joinTranscriptText(finalTxt, tail)
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
                var shouldCommitForPause = false
                var frame: ByteArray? = null

                synchronized(lock) {
                    while (frameQueue.isEmpty() && !pauseRequested && !finishing && !cancelled) {
                        lock.wait(500L)
                    }
                    if (cancelled) return
                    if (frameQueue.isNotEmpty()) {
                        frame = frameQueue.removeFirst()
                    } else if (pauseRequested) {
                        pauseRequested = false
                        shouldCommitForPause = true
                    } else if (finishing) {
                        return@synchronized
                    }
                }

                if (shouldCommitForPause) {
                    if (sess != null) {
                        commitSession(sess, isRollover = true)
                        sess = null
                        sessAudioSec = 0.0
                        silenceSec = 0.0
                    }
                    continue
                }

                val currentFrame = frame ?: break

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
                            frameQueue.addFirst(currentFrame)
                            if (connectFailures >= 6) {
                                while (frameQueue.size > 250) frameQueue.removeFirst()
                            }
                        }
                        Thread.sleep(minOf(2000L, 300L * connectFailures))
                        continue
                    }
                }

                val silent = TranscriptUtils.isFrameSilent(currentFrame)
                val ok = sess.sendAudioFrame(currentFrame)
                if (!ok || sess.isDone) {
                    val err = sess.error
                    commitSession(sess, isRollover = true)
                    sess = null
                    synchronized(lock) {
                        if (err.isNotEmpty()) lastError = err
                        frameQueue.addFirst(currentFrame)
                    }
                    continue
                }

                val dur = currentFrame.size.toDouble() / (FrontierProtocol.SAMPLE_RATE * 2.0)
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

                if (framePaceMs > 0L) Thread.sleep(framePaceMs)
            }

            if (sess != null) {
                commitSession(sess, isRollover = false)
                sess = null
            }
        } finally {
            sess?.close()
            synchronized(lock) { activeSession = null }
        }
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    companion object {
        fun transcribeWavFile(
            wavFile: File,
            authRepo: DoubaoAuthRepository,
            httpClient: OkHttpClient,
        ): Pair<String, Int> {
            if (!wavFile.isFile || wavFile.length() <= 44L) throw AsrException("录音文件为空")
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
                val raw = ByteArray((raf.length() - 44L).toInt())
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
                framePaceMs = 2L,
            )
            engine.feedPcm(pcmBytes)
            val audioSec = pcmBytes.size.toDouble() / (FrontierProtocol.SAMPLE_RATE * 2.0)
            val maxWaitMs = maxOf(15_000L, (audioSec * 350.0).toLong() + 15_000L)
            val (text, rolls, err) = engine.finish(timeoutMs = maxWaitMs)
            if (text.isEmpty() && err.isNotEmpty()) throw AsrException(err)
            return Pair(text, rolls)
        }
    }
}
