package com.luna.storynote.audio

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

data class WavFormatInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataBytes: Long,
) {
    val blockAlign: Int
        get() = (channels * (bitsPerSample / 8)).coerceAtLeast(2)

    val bytesPerSecond: Long
        get() = sampleRate.toLong() * blockAlign

    val durationMs: Long
        get() = if (bytesPerSecond <= 0L) 0L else (dataBytes * 1000L) / bytesPerSecond
}

data class PcmSliceResult(
    val format: WavFormatInfo,
    val pcmBytes: ByteArray,
    val actualStartMs: Long,
    val actualEndMs: Long,
)

object WavAudioFiles {
    fun wavDurationMs(wavFile: File, fallbackSampleRate: Int = FrontierProtocol.SAMPLE_RATE): Long {
        val info = readWavHeader(wavFile)
        if (info != null) return info.durationMs
        if (!wavFile.isFile || wavFile.length() <= 44L) return 0L
        return ((wavFile.length() - 44L) * 1000L) / (fallbackSampleRate * 2L)
    }

    fun readWavHeader(wavFile: File): WavFormatInfo? {
        if (!wavFile.isFile || wavFile.length() < 44L) return null
        return try {
            RandomAccessFile(wavFile, "r").use { raf ->
                val riff = ByteArray(12)
                raf.readFully(riff)
                if (String(riff, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                    String(riff, 8, 4, Charsets.US_ASCII) != "WAVE"
                ) {
                    return null
                }

                var audioFormat = -1
                var channels = 0
                var sampleRate = 0
                var bitsPerSample = 0
                var dataOffset = -1L
                var dataBytes = 0L

                val chunkHdr = ByteArray(8)
                while (raf.filePointer + 8L <= raf.length()) {
                    raf.readFully(chunkHdr)
                    val id = String(chunkHdr, 0, 4, Charsets.US_ASCII)
                    val size = ByteBuffer.wrap(chunkHdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL
                    val chunkStart = raf.filePointer
                    if (id == "fmt " && size >= 16L) {
                        val fmtBytes = ByteArray(16)
                        raf.readFully(fmtBytes)
                        val bb = ByteBuffer.wrap(fmtBytes).order(ByteOrder.LITTLE_ENDIAN)
                        audioFormat = bb.getShort(0).toInt() and 0xFFFF
                        channels = bb.getShort(2).toInt() and 0xFFFF
                        sampleRate = bb.getInt(4)
                        bitsPerSample = bb.getShort(14).toInt() and 0xFFFF
                    } else if (id == "data") {
                        dataOffset = chunkStart
                        dataBytes = minOf(size, (raf.length() - chunkStart).coerceAtLeast(0L))
                        break
                    }
                    val skipTo = chunkStart + size + (size and 1L)
                    if (skipTo > raf.length()) break
                    raf.seek(skipTo)
                }

                if (audioFormat != 1 || channels <= 0 || sampleRate <= 0 || bitsPerSample <= 0 || dataOffset < 0L) {
                    return null
                }
                WavFormatInfo(
                    sampleRate = sampleRate,
                    channels = channels,
                    bitsPerSample = bitsPerSample,
                    dataOffset = dataOffset,
                    dataBytes = dataBytes,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun readPcmRange(wavFile: File, startMs: Long, endMs: Long): PcmSliceResult? {
        val info = readWavHeader(wavFile) ?: return null
        val align = info.blockAlign
        val bytesPerSec = info.bytesPerSecond
        if (bytesPerSec <= 0L || info.dataBytes <= 0L) {
            return PcmSliceResult(info, ByteArray(0), 0L, 0L)
        }
        val totalMs = info.durationMs
        val clampedStartMs = startMs.coerceIn(0L, totalMs)
        val clampedEndMs = if (endMs <= 0L) totalMs else endMs.coerceIn(clampedStartMs, totalMs)

        val rawStartByte = (clampedStartMs * bytesPerSec) / 1000L
        val rawEndByte = (clampedEndMs * bytesPerSec) / 1000L

        val startByte = ((rawStartByte / align) * align).coerceIn(0L, info.dataBytes)
        val alignedDataEnd = (info.dataBytes / align) * align
        val endByte = ((rawEndByte / align) * align).coerceIn(startByte, alignedDataEnd)
        val byteLen = (endByte - startByte).toInt().coerceAtLeast(0)

        val pcm = ByteArray(byteLen)
        if (byteLen > 0) {
            RandomAccessFile(wavFile, "r").use { raf ->
                raf.seek(info.dataOffset + startByte)
                raf.readFully(pcm)
            }
        }
        val actualStartMs = (startByte * 1000L) / bytesPerSec
        val actualEndMs = (endByte * 1000L) / bytesPerSec
        return PcmSliceResult(info, pcm, actualStartMs, actualEndMs)
    }

    fun sliceWavToFile(sourceWav: File, destWav: File, startMs: Long, endMs: Long): Long {
        val slice = readPcmRange(sourceWav, startMs, endMs) ?: return 0L
        destWav.parentFile?.mkdirs()
        val hdr = WavFileStreamWriter.buildWavHeader(
            sampleRate = slice.format.sampleRate,
            clampedDataBytes = slice.pcmBytes.size,
            riffSize = 36 + slice.pcmBytes.size,
            channels = slice.format.channels,
            bitsPerSample = slice.format.bitsPerSample,
        )
        FileOutputStream(destWav).use { fos ->
            fos.write(hdr)
            fos.write(slice.pcmBytes)
            fos.fd.sync()
        }
        return (slice.actualEndMs - slice.actualStartMs).coerceAtLeast(0L)
    }

    fun isRangeNearSilent(
        wavFile: File,
        startMs: Long,
        endMs: Long,
        minPeakAmplitude: Int = 64,
    ): Boolean {
        if (!wavFile.isFile || !wavFile.canRead() || wavFile.length() <= 44L) return true
        val slice = try {
            readPcmRange(wavFile, startMs, endMs)
        } catch (_: Exception) {
            null
        } ?: return true
        val pcm = slice.pcmBytes
        if (pcm.isEmpty()) return true
        if (slice.format.bitsPerSample == 8) {
            for (b in pcm) {
                val amp = abs((b.toInt() and 0xFF) - 128) shl 8
                if (amp >= minPeakAmplitude) return false
            }
            return true
        }
        var i = 0
        while (i + 1 < pcm.size) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val absSample = if (sample == Int.MIN_VALUE) Int.MAX_VALUE else abs(sample)
            if (absSample >= minPeakAmplitude) return false
            i += 2
        }
        return true
    }
}

class WavFileStreamWriter(
    private val file: File,
    private val sampleRate: Int = FrontierProtocol.SAMPLE_RATE,
) {
    private val raf: RandomAccessFile
    var dataBytes: Long = 0L
        private set
    private var bytesSinceHeaderSync = 0L
    private var closed = false

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
        if (closed || length <= 0) return
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

    val durationMs: Long
        @Synchronized get() = (dataBytes * 1000L) / (sampleRate * 2L)

    val durationSec: Double
        @Synchronized get() = dataBytes.toDouble() / (sampleRate * 2.0)

    @Synchronized
    fun syncHeader() {
        if (closed) return
        try {
            writeHeader(dataBytes)
            raf.fd.sync()
            bytesSinceHeaderSync = 0L
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun close(): Double {
        if (closed) return durationSec
        closed = true
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
        fun buildWavHeader(
            sampleRate: Int,
            clampedDataBytes: Int,
            riffSize: Int,
            channels: Int = 1,
            bitsPerSample: Int = 16,
        ): ByteArray {
            val blockAlign = (channels * (bitsPerSample / 8)).toShort()
            val byteRate = sampleRate * blockAlign
            return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(riffSize)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1) // PCM
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort(blockAlign)
                putShort(bitsPerSample.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(clampedDataBytes)
            }.array()
        }

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
