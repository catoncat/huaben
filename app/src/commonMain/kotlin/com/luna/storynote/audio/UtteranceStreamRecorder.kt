package com.luna.storynote.audio

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.ArrayDeque
import java.util.UUID

data class CommittedUtterance(
    val takeId: String,
    val takeFile: File,
    val takeDurationMs: Long,
    val text: String,
    val uttStartMs: Long,
    val uttEndMs: Long,
)

/**
 * Streams continuous 16kHz S16_LE PCM to Doubao Frontier, recording one continuous `AudioTake` WAV
 * per recording session, syncing its header on each finalized VAD commit, and emitting `[uttStartMs, uttEndMs]`
 * spans inside the take.
 */
class UtteranceStreamRecorder(
    private val authRepo: DoubaoAuthRepository,
    private val httpClient: OkHttpClient,
    private val newTakeWavFile: (takeId: String) -> File,
    private val singleUtteranceMode: Boolean = false,
    private val onLiveDraft: (draftText: String, voiceLevel: Float) -> Unit,
    private val onUtteranceCommitted: (CommittedUtterance) -> Unit,
    private val onAutoFinishedSingle: () -> Unit = {},
    private val onError: (String) -> Unit = {},
) {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()
    private val frameQueue = ArrayDeque<ByteArray>()
    private val pcmStaging = ByteArrayOutputStream(4096)
    private val currentUtterancePcm = ByteArrayOutputStream(64_000)

    private val takeId: String = "take_${UUID.randomUUID().toString().replace("-", "").take(10)}"
    private val takeFile: File by lazy { newTakeWavFile(takeId) }
    private var takeWriter: WavFileStreamWriter? = null

    @Volatile
    private var stopping = false

    @Volatile
    private var cancelled = false

    @Volatile
    private var latestDraftSnapshot: String = ""

    private val worker = Thread({ runWorker() }, "utterance-stream-recorder").apply {
        isDaemon = true
        start()
    }

    fun pushMicPcm(bytes: ByteArray, length: Int) {
        if (length <= 0 || stopping || cancelled) return
        val rms = WavFileStreamWriter.computeNormalizedRms(bytes, length)
        synchronized(lock) {
            if (stopping || cancelled) return
            pcmStaging.write(bytes, 0, length)
            val staged = pcmStaging.toByteArray()
            pcmStaging.reset()
            var off = 0
            while (off + FrontierProtocol.PCM_FRAME_BYTES <= staged.size) {
                frameQueue.addLast(staged.copyOfRange(off, off + FrontierProtocol.PCM_FRAME_BYTES))
                off += FrontierProtocol.PCM_FRAME_BYTES
            }
            if (off < staged.size) {
                pcmStaging.write(staged, off, staged.size - off)
            }
            lock.notifyAll()
        }
        onLiveDraft(latestDraftSnapshot, rms)
    }

    fun finishGracefully(timeoutMs: Long = 8000L) {
        synchronized(lock) {
            if (!stopping) {
                val rem = pcmStaging.toByteArray()
                pcmStaging.reset()
                if (rem.isNotEmpty()) {
                    val pad = ByteArray(FrontierProtocol.PCM_FRAME_BYTES)
                    System.arraycopy(rem, 0, pad, 0, minOf(rem.size, pad.size))
                    frameQueue.addLast(pad)
                }
                stopping = true
                lock.notifyAll()
            }
        }
        worker.join(timeoutMs)
    }

    fun cancel() {
        synchronized(lock) {
            cancelled = true
            frameQueue.clear()
            pcmStaging.reset()
            currentUtterancePcm.reset()
            lock.notifyAll()
        }
        worker.join(1500L)
        closeTakeWriter()
    }

    private fun closeTakeWriter() {
        val w = synchronized(lock) {
            val prev = takeWriter
            takeWriter = null
            prev
        }
        w?.close()
    }

    private fun flushUtteranceClip(recognizedText: String) {
        val rawPcm: ByteArray
        synchronized(lock) {
            rawPcm = currentUtterancePcm.toByteArray()
            currentUtterancePcm.reset()
        }
        val cleanText = recognizedText.trim()
        if (cleanText.isEmpty() || !TranscriptUtils.hasSpeechChars(cleanText)) return

        val trimmedPcm = TranscriptUtils.trimSilenceEdges(rawPcm)
        val writer = synchronized(lock) {
            takeWriter ?: WavFileStreamWriter(takeFile).also { takeWriter = it }
        }
        if (writer.dataBytes > 0L) {
            writer.appendSilenceMs(220)
        }
        val uttStartMs = writer.durationMs
        writer.appendPcm(trimmedPcm, trimmedPcm.size)
        val uttEndMs = writer.durationMs
        writer.syncHeader()

        latestDraftSnapshot = ""
        onLiveDraft("", 0f)
        onUtteranceCommitted(
            CommittedUtterance(
                takeId = takeId,
                takeFile = takeFile,
                takeDurationMs = uttEndMs,
                text = cleanText,
                uttStartMs = uttStartMs,
                uttEndMs = uttEndMs,
            ),
        )
    }

    private fun runWorker() {
        var activeSocket: LiveWsSession? = null
        var sessionAudioSec = 0.0
        var silenceRunSec = 0.0

        try {
            while (true) {
                val frame: ByteArray? = synchronized(lock) {
                    while (frameQueue.isEmpty() && !stopping && !cancelled) {
                        lock.wait(400L)
                    }
                    if (cancelled) return
                    if (frameQueue.isNotEmpty()) frameQueue.removeFirst() else null
                }

                if (frame == null) break

                if (activeSocket == null || activeSocket.isClosed) {
                    activeSocket?.close()
                    activeSocket = openLiveWsSession()
                    if (activeSocket == null) {
                        synchronized(lock) { currentUtterancePcm.write(frame) }
                        Thread.sleep(350L)
                        continue
                    }
                    sessionAudioSec = 0.0
                    silenceRunSec = 0.0
                }

                synchronized(lock) {
                    if (latestDraftSnapshot.isEmpty() && currentUtterancePcm.size() > FrontierProtocol.SAMPLE_RATE * 2 * 6) {
                        val tail = currentUtterancePcm.toByteArray()
                        currentUtterancePcm.reset()
                        val keep = FrontierProtocol.SAMPLE_RATE * 2 * 1
                        currentUtterancePcm.write(tail, tail.size - keep, keep)
                    }
                    currentUtterancePcm.write(frame)
                }

                val sentOk = activeSocket.sendPcmFrame(frame)
                if (!sentOk) {
                    activeSocket.close()
                    activeSocket = null
                    continue
                }

                val silent = TranscriptUtils.isFrameSilent(frame)
                val dur = frame.size.toDouble() / (FrontierProtocol.SAMPLE_RATE * 2.0)
                sessionAudioSec += dur
                silenceRunSec = if (silent) (silenceRunSec + dur) else 0.0

                while (true) {
                    val committedTxt = activeSocket.pollCommittedUtterance() ?: break
                    flushUtteranceClip(committedTxt)
                    if (singleUtteranceMode) {
                        stopping = true
                        activeSocket.finishAndDrain(1200L)
                        activeSocket.close()
                        onAutoFinishedSingle()
                        return
                    }
                }

                if ((sessionAudioSec >= 115.0 && silenceRunSec >= 0.45) || sessionAudioSec >= 162.0) {
                    val leftoverTxt = activeSocket.finishAndDrain(3500L)
                    if (leftoverTxt.isNotEmpty()) flushUtteranceClip(leftoverTxt)
                    activeSocket.close()
                    activeSocket = null
                }
            }

            if (activeSocket != null && !cancelled) {
                val tailTxt = activeSocket.finishAndDrain(4500L)
                while (true) {
                    val c = activeSocket.pollCommittedUtterance() ?: break
                    flushUtteranceClip(c)
                }
                if (tailTxt.isNotEmpty()) {
                    flushUtteranceClip(tailTxt)
                }
            }
        } catch (e: Exception) {
            if (!cancelled) onError(e.message ?: "语音识别中断")
        } finally {
            activeSocket?.close()
            closeTakeWriter()
        }
    }

    private fun openLiveWsSession(): LiveWsSession? {
        for (attempt in 0..1) {
            try {
                val creds = authRepo.resolve(forceRefresh = attempt > 0)
                val s = LiveWsSession(creds, httpClient) { draft ->
                    latestDraftSnapshot = draft
                    onLiveDraft(draft, 0f)
                }
                if (s.connect(6500L)) return s
                s.close()
            } catch (e: Exception) {
                if (attempt == 1) onError(e.message ?: "无法连接语音服务")
            }
        }
        return null
    }

    private class LiveWsSession(
        private val creds: DoubaoCredentials,
        private val httpClient: OkHttpClient,
        private val onInterimChanged: (String) -> Unit,
    ) {
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        private val sLock = Object()
        private val sessionId = UUID.randomUUID().toString().uppercase()
        private var ws: WebSocket? = null
        private var ready = false
        var isClosed = false
            private set

        private val committedQueue = ArrayDeque<String>()
        private val finalizedIndices = HashSet<Int>()
        private val partialByIndex = HashMap<Int, String>()

        fun connect(timeoutMs: Long): Boolean {
            val req = Request.Builder()
                .url(creds.wsUrl)
                .header("User-Agent", DoubaoCredentials.ANDROID_USER_AGENT)
                .header("proto-version", "v2")
                .header("x-custom-keepalive", "true")
                .header("x-keepalive-interval", "3")
                .header("x-keepalive-timeout", "3600")
                .build()

            ws = httpClient.newWebSocket(
                req,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(FrontierProtocol.buildPacket(creds.appKey, "StartTask", "", null, sessionId).toByteString())
                        webSocket.send(
                            FrontierProtocol.buildPacket(
                                creds.appKey,
                                "StartSession",
                                FrontierProtocol.buildSessionConfig(),
                                null,
                                sessionId,
                            ).toByteString(),
                        )
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val parsed = FrontierProtocol.parseResponse(bytes.toByteArray()) ?: return
                        var draftToEmit: String? = null
                        synchronized(sLock) {
                            if (parsed.statusCode != 20_000_000L) {
                                isClosed = true
                                sLock.notifyAll()
                                return
                            }
                            when (parsed.event) {
                                "SessionStarted" -> ready = true
                                "SessionFinished", "TaskFinished" -> isClosed = true
                            }
                            val results = parsed.payloadJson?.optJSONArray("results")
                            if (results != null) {
                                for (i in 0 until results.length()) {
                                    val item = results.optJSONObject(i) ?: continue
                                    val txt = item.optString("text", "").trim()
                                    if (txt.isEmpty() || !TranscriptUtils.hasSpeechChars(txt)) continue
                                    val idx = if (item.has("index") && !item.isNull("index")) item.optInt("index", 0) else 0
                                    if (idx in finalizedIndices) continue

                                    val extra = item.optJSONObject("extra")
                                    val nonStream = extra?.optBoolean("nonstream_result", false) == true
                                    val isInterim = if (item.has("is_interim")) item.optBoolean("is_interim", true) else true
                                    val vadFin = item.optBoolean("is_vad_finished", false)
                                    val isTerm = nonStream || (!isInterim && vadFin)

                                    if (isTerm) {
                                        finalizedIndices.add(idx)
                                        partialByIndex.remove(idx)
                                        committedQueue.addLast(txt)
                                        draftToEmit = composeActiveDraftUnlocked()
                                    } else {
                                        partialByIndex[idx] = txt
                                        draftToEmit = composeActiveDraftUnlocked()
                                    }
                                }
                            }
                            sLock.notifyAll()
                        }
                        val emitVal = draftToEmit
                        if (emitVal != null) onInterimChanged(emitVal)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        synchronized(sLock) {
                            isClosed = true
                            sLock.notifyAll()
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        synchronized(sLock) {
                            isClosed = true
                            sLock.notifyAll()
                        }
                    }
                },
            )

            val deadline = System.currentTimeMillis() + timeoutMs
            synchronized(sLock) {
                while (!ready && !isClosed) {
                    val rem = deadline - System.currentTimeMillis()
                    if (rem <= 0L) return false
                    sLock.wait(rem)
                }
                return ready && !isClosed
            }
        }

        private fun composeActiveDraftUnlocked(): String =
            partialByIndex.entries.sortedBy { it.key }.joinToString("") { it.value }

        fun sendPcmFrame(frame: ByteArray): Boolean {
            val s = ws ?: return false
            if (isClosed) return false
            val meta = "{\"timestamp_ms\":${System.currentTimeMillis()}}"
            return s.send(FrontierProtocol.buildPacket("", "TaskRequest", meta, frame, sessionId).toByteString())
        }

        fun pollCommittedUtterance(): String? = synchronized(sLock) {
            if (committedQueue.isNotEmpty()) committedQueue.removeFirst() else null
        }

        fun finishAndDrain(timeoutMs: Long): String {
            val s = ws
            if (s != null && !isClosed) {
                s.send(FrontierProtocol.buildPacket(creds.appKey, "FinishSession", "", null, sessionId).toByteString())
                val deadline = System.currentTimeMillis() + timeoutMs
                synchronized(sLock) {
                    while (!isClosed) {
                        val rem = deadline - System.currentTimeMillis()
                        if (rem <= 0L) break
                        sLock.wait(rem)
                    }
                }
            }
            return synchronized(sLock) {
                val left = composeActiveDraftUnlocked()
                partialByIndex.clear()
                left
            }
        }

        fun close() {
            synchronized(sLock) {
                isClosed = true
                sLock.notifyAll()
            }
            ws?.close(1000, "bye")
            ws = null
        }
    }
}
