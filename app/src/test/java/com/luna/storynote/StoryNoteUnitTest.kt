package com.luna.storynote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory

class StoryNoteUnitTest {
    @Test
    fun varintAndPacketRoundTrip() {
        val encoded = FrontierProtocol.encodeVarint(20_000_000L)
        assertTrue(encoded.size >= 4)

        val audio = ByteArray(640) { (it % 127).toByte() }
        val pkt = FrontierProtocol.buildPacket(
            token = "test-key",
            method = "TaskRequest",
            payload = "{\"timestamp_ms\":1}",
            audio = audio,
            requestId = "REQ-123",
        )
        assertTrue(pkt.size > 640)
    }

    @Test
    fun parseServerResponseFrame() {
        // Construct synthetic server response: field 4 (event="SessionStarted"), field 5 (status=20_000_000)
        val eventBytes = "SessionStarted".toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()
        out.write(FrontierProtocol.encodeVarint(((4 shl 3) or 2).toLong()))
        out.write(FrontierProtocol.encodeVarint(eventBytes.size.toLong()))
        out.write(eventBytes)
        out.write(FrontierProtocol.encodeVarint(((5 shl 3) or 0).toLong()))
        out.write(FrontierProtocol.encodeVarint(20_000_000L))

        val parsed = FrontierProtocol.parseResponse(out.toByteArray())
        assertNotNull(parsed)
        assertEquals("SessionStarted", parsed!!.event)
        assertEquals(20_000_000L, parsed.statusCode)
    }

    @Test
    fun silenceDetectionAndWavHeader() {
        val silentFrame = ByteArray(640)
        assertTrue(isFrameSilent(silentFrame))

        val loudFrame = ByteBuffer.allocate(640).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0, 4200)
        }.array()
        assertFalse(isFrameSilent(loudFrame))

        val dir = createTempDirectory("wav-test").toFile()
        try {
            val wav = File(dir, "test.wav")
            val writer = WavFileStreamWriter(wav)
            writer.appendPcm(ByteArray(32000), 32000) // 1.0s @ 16kHz 16-bit mono
            val dur = writer.close()
            assertEquals(1.0, dur, 0.001)
            assertEquals(32044L, wav.length())

            val header = wav.readBytes().copyOfRange(0, 4)
            assertArrayEquals("RIFF".toByteArray(Charsets.US_ASCII), header)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cjkAndEnglishTranscriptJoining() {
        assertEquals("小恐龙飞船", joinTranscriptText("小恐龙", "飞船"))
        assertEquals("hello world", joinTranscriptText("hello", "world"))
        assertEquals("第一句。第二句。", joinTranscriptText("第一句。", "第二句。"))
    }
}
