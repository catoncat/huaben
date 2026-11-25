package com.luna.storynote.audio

import kotlin.math.abs

object TranscriptUtils {
    val PUNCT_CHARS = setOf('。', '，', '！', '？', ',', '.', '!', '?', '；', '：')

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

    fun hasSpeechChars(text: String): Boolean = text.any { it.isLetterOrDigit() }

    fun isFrameSilent(frame: ByteArray, peakThreshold: Int = 380): Boolean {
        if (frame.size < 2) return true
        var i = 0
        while (i + 1 < frame.size) {
            val lo = frame[i].toInt() and 0xFF
            val hi = frame[i + 1].toInt()
            val sample = (hi shl 8) or lo
            if (abs(sample) >= peakThreshold) return false
            i += 2
        }
        return true
    }

    /**
     * Trims long thinking pauses at the start/end of an utterance down to a natural 140ms pre-roll
     * and 180ms tail so tapping any clause on the book page plays the child's voice immediately.
     */
    fun trimSilenceEdges(pcm: ByteArray): ByteArray {
        val frameBytes = FrontierProtocol.PCM_FRAME_BYTES // 20ms = 640 bytes
        if (pcm.size <= frameBytes * 16) return pcm

        var firstSpeechOffset = 0
        var foundSpeech = false
        var off = 0
        while (off + frameBytes <= pcm.size) {
            val slice = pcm.copyOfRange(off, off + frameBytes)
            if (!isFrameSilent(slice, peakThreshold = 340)) {
                firstSpeechOffset = off
                foundSpeech = true
                break
            }
            off += frameBytes
        }
        if (!foundSpeech) return pcm.copyOfRange(pcm.size - frameBytes * 12, pcm.size)

        var lastSpeechEnd = pcm.size
        var rev = pcm.size - frameBytes
        while (rev >= firstSpeechOffset) {
            val slice = pcm.copyOfRange(rev, rev + frameBytes)
            if (!isFrameSilent(slice, peakThreshold = 300)) {
                lastSpeechEnd = rev + frameBytes
                break
            }
            rev -= frameBytes
        }

        val preRollBytes = frameBytes * 7  // 140ms natural breath pre-roll
        val postRollBytes = frameBytes * 9 // 180ms natural tail
        val start = (firstSpeechOffset - preRollBytes).coerceAtLeast(0)
        val end = (lastSpeechEnd + postRollBytes).coerceAtMost(pcm.size)
        return if (end > start) pcm.copyOfRange(start, end) else pcm
    }
}
