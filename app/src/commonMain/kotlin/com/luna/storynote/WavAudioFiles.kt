package com.luna.storynote

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class WavFileStreamWriter(
    private val file: File,
    private val sampleRate: Int = FrontierProtocol.SAMPLE_RATE,
) {
    private val raf: RandomAccessFile
    var dataBytes: Long = 0L
        private set
    private var bytesSinceHeaderSync = 0L

    init {
        file.parentFile?.mkdirs()
        val existed = file.isFile && file.length() > 44L
        raf = RandomAccessFile(file, "rw")
        if (existed) {
            dataBytes = raf.length() - 44L
            if (dataBytes % 2L != 0L) dataBytes--
        } else {
            raf.setLength(0L)
            writeHeader(0L)
        }
    }

    private fun writeHeader(pcmDataBytes: Long) {
        val clampedData = pcmDataBytes.coerceAtMost(0xFFFF_FFFFL).toInt()
        val riffSize = (36L + pcmDataBytes).coerceAtMost(0xFFFF_FFFFL).toInt()
        val hdr = buildWavHeader(sampleRate, clampedData, riffSize)
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
        if (bytesSinceHeaderSync >= sampleRate * 4) {
            writeHeader(dataBytes)
            bytesSinceHeaderSync = 0L
        }
    }

    @Synchronized
    fun appendSilenceMs(durationMs: Int) {
        val samples = (sampleRate * durationMs) / 1000
        if (samples > 0) {
            val zeros = ByteArray(samples * 2)
            appendPcm(zeros, zeros.size)
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

    companion object {
        fun buildWavHeader(sampleRate: Int, clampedDataBytes: Int, riffSize: Int): ByteArray =
            ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(riffSize)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1) // PCM
                putShort(1) // Mono
                putInt(sampleRate)
                putInt(sampleRate * 2)
                putShort(2) // BlockAlign
                putShort(16) // 16-bit
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(clampedDataBytes)
            }.array()

        /** Appends `extraWav` PCM onto `targetWav` separated by a natural breath pause, returning new total duration. */
        fun appendWavFiles(targetWav: File, extraWav: File, pauseMs: Int = 550): Double {
            if (!extraWav.isFile || extraWav.length() <= 44L) {
                return wavDurationSec(targetWav)
            }
            val extraPcm = RandomAccessFile(extraWav, "r").use { src ->
                val raw = ByteArray((src.length() - 44L).toInt())
                src.seek(44L)
                src.readFully(raw)
                raw
            }
            val writer = WavFileStreamWriter(targetWav)
            if (writer.dataBytes > 0L && pauseMs > 0) {
                writer.appendSilenceMs(pauseMs)
            }
            writer.appendPcm(extraPcm, extraPcm.size)
            return writer.close()
        }

        fun wavDurationSec(wavFile: File, sampleRate: Int = FrontierProtocol.SAMPLE_RATE): Double {
            if (!wavFile.isFile || wavFile.length() <= 44L) return 0.0
            return (wavFile.length() - 44L).toDouble() / (sampleRate * 2.0)
        }

        fun computeNormalizedRms(pcm: ByteArray, length: Int): Float {
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
}
