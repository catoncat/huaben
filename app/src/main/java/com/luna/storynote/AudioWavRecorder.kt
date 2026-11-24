package com.luna.storynote

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class WavFileStreamWriter(private val file: File, private val sampleRate: Int = FrontierProtocol.SAMPLE_RATE) {
    private val raf: RandomAccessFile
    var dataBytes: Long = 0L
        private set
    private var bytesSinceHeaderSync = 0L

    init {
        file.parentFile?.mkdirs()
        raf = RandomAccessFile(file, "rw")
        raf.setLength(0L)
        writeHeader(0L)
    }

    private fun writeHeader(pcmDataBytes: Long) {
        val clampedData = pcmDataBytes.coerceAtMost(0xFFFF_FFFFL).toInt()
        val riffSize = (36L + pcmDataBytes).coerceAtMost(0xFFFF_FFFFL).toInt()
        val byteRate = sampleRate * 2 // 1ch * 16-bit
        val hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(riffSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(1) // Mono
            putInt(sampleRate)
            putInt(byteRate)
            putShort(2) // BlockAlign
            putShort(16) // BitsPerSample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(clampedData)
        }.array()
        raf.seek(0L)
        raf.write(hdr)
    }

    @Synchronized
    fun appendPcm(buffer: ByteArray, length: Int) {
        if (length <= 0) return
        val evenLen = if (length % 2 == 0) length else length - 1
        if (evenLen <= 0) return
        raf.seek(44L + dataBytes)
        raf.write(buffer, 0, evenLen)
        dataBytes += evenLen
        bytesSinceHeaderSync += evenLen
        // Sync RIFF header every ~2 seconds so crash recovery still yields a playable WAV
        if (bytesSinceHeaderSync >= sampleRate * 4) {
            writeHeader(dataBytes)
            bytesSinceHeaderSync = 0L
        }
    }

    val durationSec: Double
        @Synchronized get() = dataBytes.toDouble() / (sampleRate * 2.0)

    @Synchronized
    fun close(): Double {
        try {
            writeHeader(dataBytes)
            raf.fd.sync()
        } catch (_: Exception) {
        } finally {
            try {
                raf.close()
            } catch (_: Exception) {
            }
        }
        return durationSec
    }
}

class AudioWavRecorder(
    private val outputWavFile: File,
    private val onPcmChunk: (ByteArray, Int) -> Unit,
    private val onProgress: (elapsedSec: Double, voiceLevel: Float) -> Unit,
) {
    @Volatile
    private var running = false
    private var recordThread: Thread? = null
    private var wavWriter: WavFileStreamWriter? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(
            FrontierProtocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            throw IllegalStateException("设备麦克风不支持 16kHz 录音")
        }
        val bufferBytes = maxOf(minBuf * 2, 6400)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            FrontierProtocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw IllegalStateException("无法打开麦克风，请检查录音权限")
        }

        val writer = WavFileStreamWriter(outputWavFile)
        wavWriter = writer
        running = true
        audioRecord.startRecording()

        recordThread = Thread(
            {
                val readBuf = ByteArray(3200) // 100ms @ 16kHz S16_LE
                try {
                    while (running) {
                        val readBytes = audioRecord.read(readBuf, 0, readBuf.size)
                        if (readBytes > 0) {
                            writer.appendPcm(readBuf, readBytes)
                            onPcmChunk(readBuf.copyOf(readBytes), readBytes)
                            val rms = computeNormalizedRms(readBuf, readBytes)
                            onProgress(writer.durationSec, rms)
                        }
                    }
                } finally {
                    try {
                        audioRecord.stop()
                    } catch (_: Exception) {
                    }
                    audioRecord.release()
                }
            },
            "story-mic-capture",
        ).apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop(): Double {
        running = false
        recordThread?.join(2000L)
        recordThread = null
        val dur = wavWriter?.close() ?: 0.0
        wavWriter = null
        return dur
    }

    private fun computeNormalizedRms(pcm: ByteArray, length: Int): Float {
        val sampleCount = length / 2
        if (sampleCount <= 0) return 0f
        var sumSq = 0.0
        var i = 0
        while (i + 1 < length) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            val sample = ((hi shl 8) or lo) / 32768.0
            sumSq += sample * sample
            i += 2
        }
        return sqrt(sumSq / sampleCount).toFloat().coerceIn(0f, 1f)
    }
}
