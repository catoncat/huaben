package com.luna.storynote

import com.luna.storynote.audio.FrontierProtocol
import com.luna.storynote.audio.WavAudioFiles
import com.luna.storynote.audio.WavFileStreamWriter
import com.luna.storynote.literacy.StoryBookFormatter
import com.luna.storynote.storage.StoryBookRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ClauseFormatterAndAudioSpanTest {
    @Test
    fun longSpeechSplitsIntoEightToSixteenCharClausesWithContiguousSpans() {
        val longSpeech =
            "从前有一只蓝色的小恐龙他住在一座会喷棉花糖的火山上每天早上都要吃冰淇淋还要和狐狸朋友去森林探险"
        val drafts = StoryBookFormatter.splitIntoClausesWithSpans(
            rawText = longSpeech,
            totalDurationMs = 12_000L,
            vadPauseMs = listOf(3_100L, 6_400L, 9_200L),
        )
        assertTrue("Expected >= 4 clauses, got ${drafts.size}", drafts.size >= 4)
        for (i in drafts.indices) {
            val d = drafts[i]
            assertTrue("Clause '${d.text}' too long (${d.text.length})", d.text.length <= 16)
            if (i < drafts.lastIndex) {
                assertTrue("Clause '${d.text}' too short (${d.text.length})", d.text.length >= 6)
                assertEquals(drafts[i + 1].startMs, d.endMs)
            }
            assertTrue("Expected positive span duration for '${d.text}'", d.endMs > d.startMs)
        }
        assertEquals(0L, drafts.first().startMs)
        assertEquals(12_000L, drafts.last().endMs)
        assertEquals(
            StoryBookFormatter.normalizeChinesePunct(longSpeech),
            drafts.joinToString("") { it.text },
        )
    }

    @Test
    fun replacingOneClausePreservesNeighborAudioSpansAndTake() {
        val tmp = createTempDirectory("clause-span-test-").toFile()
        try {
            val repo = StoryBookRepository(tmp)
            val book = repo.createBlankBook()
            val takeWav = repo.newStagingWav()
            val writer = WavFileStreamWriter(takeWav)
            writer.appendSilenceMs(4000)
            writer.close()

            val (b1, clauses) = repo.insertUtteranceWithClauses(
                bookId = book.id,
                afterClauseId = null,
                rawText = "从前有一只蓝色的小恐龙，他住在一座会喷棉花糖的火山上。",
                takeId = "take_main",
                takeWavFile = takeWav,
                uttStartMs = 0L,
                uttEndMs = 4000L,
                takeDurationMs = 4000L,
            )
            assertTrue("Expected >= 2 clauses, got ${clauses.size}", clauses.size >= 2)
            val firstSpanBefore = b1.clauses.first().audioSpan
            val lastSpanBefore = b1.clauses.last().audioSpan
            assertNotNull(firstSpanBefore)
            assertNotNull(lastSpanBefore)

            val updated = repo.replaceRangeInClause(
                bookId = book.id,
                clauseId = b1.clauses.first().id,
                startIdx = 0,
                endIdx = 2,
                replacementWord = "很久以前",
            )!!
            assertTrue(updated.clauses.first().text.startsWith("很久以前"))
            assertEquals(firstSpanBefore, updated.clauses.first().audioSpan)
            assertEquals(lastSpanBefore, updated.clauses.last().audioSpan)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun wavReadPcmRangeAndSliceWavAreSampleAligned() {
        val tmp = createTempDirectory("wav-slice-test-").toFile()
        try {
            val src = File(tmp, "source.wav")
            val writer = WavFileStreamWriter(src)
            val sampleBytes = ByteArray(FrontierProtocol.SAMPLE_RATE * 2 * 3) // 3000ms
            for (i in sampleBytes.indices step 2) {
                sampleBytes[i] = 0x11
                sampleBytes[i + 1] = 0x22
            }
            writer.appendPcm(sampleBytes, sampleBytes.size)
            writer.syncHeader()
            assertEquals(3000L, writer.durationMs)
            writer.close()

            val slice = WavAudioFiles.readPcmRange(src, startMs = 500L, endMs = 1500L)
            assertNotNull(slice)
            // 1000ms @ 16kHz 16-bit mono = 32000 bytes
            assertEquals(1000L * 32, slice!!.pcmBytes.size.toLong())
            assertEquals(500L, slice.actualStartMs)
            assertEquals(1500L, slice.actualEndMs)

            val slicedFile = File(tmp, "sliced.wav")
            val slicedMs = WavAudioFiles.sliceWavToFile(src, slicedFile, 500L, 1500L)
            assertEquals(1000L, slicedMs)
            assertEquals(1000L, WavAudioFiles.wavDurationMs(slicedFile))
            assertEquals(44L + 32_000L, slicedFile.length())
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun shortAndZeroDurationsProduceMonotonicNonInvertedAudioSpans() {
        val fourClauseText =
            "从前有一只特别喜欢吃草莓棉花糖的小蓝恐龙每天早上都会爬到高高的火山顶上看太阳升起来"
        for (totalMs in listOf(0L, 1L, 2L)) {
            val drafts = StoryBookFormatter.splitIntoClausesWithSpans(
                rawText = fourClauseText,
                totalDurationMs = totalMs,
            )
            assertTrue("Expected 4+ clauses for totalMs=$totalMs, got ${drafts.size}", drafts.size >= 4)
            assertEquals(0L, drafts.first().startMs)
            assertEquals(totalMs, drafts.last().endMs)
            var prevEnd = 0L
            for (d in drafts) {
                assertTrue("Expected 0 <= startMs (${d.startMs}) <= endMs (${d.endMs}) <= $totalMs", d.startMs in 0L..d.endMs && d.endMs <= totalMs)
                assertTrue("Expected monotonic non-overlapping bounds: $prevEnd <= ${d.startMs}", prevEnd <= d.startMs)
                prevEnd = d.endMs
            }

            val clauses = StoryBookFormatter.buildClausesForTake(
                rawText = fourClauseText,
                takeId = "take_short",
                uttStartMs = 100L,
                uttEndMs = 100L + totalMs,
                clauseIdGenerator = { "c_$it" },
            )
            assertEquals(drafts.size, clauses.size)
            var prevClauseEnd = 100L
            for (c in clauses) {
                val span = c.audioSpan
                if (totalMs == 0L) {
                    assertEquals(null, span)
                } else {
                    assertNotNull(span)
                    assertTrue(
                        "Expected 100 <= ${span!!.startMs} <= ${span.endMs} <= ${100L + totalMs}",
                        span.startMs in 100L..span.endMs && span.endMs <= 100L + totalMs,
                    )
                    assertTrue("Expected monotonic span bounds: $prevClauseEnd <= ${span.startMs}", prevClauseEnd <= span.startMs)
                    prevClauseEnd = span.endMs
                }
            }
        }
    }

    @Test
    fun cottonCandyCompoundIsNotSplitAcrossClausesAndMaxLenRespected() {
        val story = "“哇！太棒了！”小恐龙一边吃着草莓棉花糖一边快乐地跳起舞来，然后又跑到了火山口。"
        val clauses = StoryBookFormatter.splitIntoClauses(story)
        assertTrue(
            "Expected 棉花糖 to stay intact in a single clause, got $clauses",
            clauses.any { it.contains("棉花糖") },
        )

        val forced23 = "甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申酉戌亥。"
        val customClauses = StoryBookFormatter.splitIntoClauses(
            rawText = forced23,
            wordBoundaries = { listOf(0..5, 6..21, 22..22) },
        )
        assertTrue("Expected all clauses <= 16 chars, got $customClauses", customClauses.all { it.length <= 16 })
        assertTrue("Expected no orphan punctuation clause, got $customClauses", customClauses.none { it == "。" })
    }
}
