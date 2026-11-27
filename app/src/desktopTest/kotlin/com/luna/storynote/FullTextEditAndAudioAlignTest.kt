package com.luna.storynote

import com.luna.storynote.agent.AgentTaskRole
import com.luna.storynote.agent.ToolBus
import com.luna.storynote.agent.ToolId
import com.luna.storynote.agent.ToolRequest
import com.luna.storynote.agent.ToolResult
import com.luna.storynote.audio.WavAudioFiles
import com.luna.storynote.audio.WavFileStreamWriter
import com.luna.storynote.literacy.FullTextAudioAligner
import com.luna.storynote.model.AudioSpan
import com.luna.storynote.model.Clause
import com.luna.storynote.model.SoftAnnotation
import com.luna.storynote.model.StoryBook
import com.luna.storynote.storage.BookExporter
import com.luna.storynote.storage.StoryBookRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class FullTextEditAndAudioAlignTest {

    private fun makeNonSilentWav(file: File, durationMs: Int) {
        val sampleCount = (16000L * durationMs / 1000L).toInt().coerceAtLeast(160)
        val pcm = ByteArray(sampleCount * 2) { i -> ((i * 23) % 110).toByte() }
        WavFileStreamWriter(file).apply {
            appendPcm(pcm, pcm.size)
            close()
        }
    }

    private fun buildSampleBookWithAudio(tmpDir: File): Pair<StoryBookRepository, StoryBook> {
        val repo = StoryBookRepository(tmpDir)
        val blank = repo.createBlankBook()
        val wav1 = repo.newStagingWav().also { makeNonSilentWav(it, 6000) }
        val (b1, _) = repo.insertUtteranceWithClauses(
            bookId = blank.id,
            afterClauseId = null,
            rawText = "小孔龙慢慢的走出了防间，他往前走啊走，终于来到了大声林里。",
            takeId = "take_1",
            takeWavFile = wav1,
            uttStartMs = 0L,
            uttEndMs = 6000L,
            takeDurationMs = 6000L,
            forceParagraphBreak = true,
        )
        val wav2 = repo.newStagingWav().also { makeNonSilentWav(it, 2400) }
        val (b2, _) = repo.insertUtteranceWithClauses(
            bookId = b1.id,
            afterClauseId = b1.clauses.last().id,
            rawText = "树上的小鸟唱着欢快的歌。",
            takeId = "take_2",
            takeWavFile = wav2,
            uttStartMs = 0L,
            uttEndMs = 2400L,
            takeDurationMs = 2400L,
        )
        val titleWav = repo.newStagingWav().also { makeNonSilentWav(it, 1000) }
        val titled = repo.updateBookTitleAndCover(
            bookId = b2.id,
            newTitle = "小孔龙的大声林",
            titleTempWav = titleWav,
        )!!
        return repo to titled
    }

    @Test
    fun noOpFullTextSaveLeavesBookUntouchedWithoutSnapshot() {
        val tmp = createTempDirectory("fulltext-noop-").toFile()
        try {
            val (repo, book) = buildSampleBookWithAudio(tmp)
            val withAnn = repo.annotateClauseSoftSuggestions(
                book.id,
                book.clauses.first().id,
                listOf(SoftAnnotation(charIndex = 1, length = 1, suggestedWord = "恐")),
            )!!
            val beforeSnaps = repo.timeMachine.listSnapshots(withAnn.id).size
            val rendered = FullTextAudioAligner.renderEditableFullText(withAnn)

            val (after, changed) = repo.applyFullTextEdit(withAnn.id, withAnn.title, rendered)!!
            assertFalse(changed)
            assertEquals(beforeSnaps, repo.timeMachine.listSnapshots(after.id).size)
            assertEquals(withAnn.clauses, after.clauses)
            assertEquals(withAnn.titleAudioFileName, after.titleAudioFileName)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun homophoneAndFuzzyAsrCorrectionsPreserveOriginalAudioAndIds() {
        val tmp = createTempDirectory("fulltext-typo-").toFile()
        try {
            val (repo, book) = buildSampleBookWithAudio(tmp)
            val origClauses = book.clauses
            assertTrue(origClauses.size >= 3)

            // Fix ASR homophones (孔->恐, 的->地, 防->房) and fuzzy pinyin (声林->森林)
            val editedBody = """
                小恐龙慢慢地走出了房间，他往前走啊走，终于来到了大森林里。

                树上的小鸟唱着欢快的歌。
            """.trimIndent()
            val (updated, changed) = repo.applyFullTextEdit(book.id, "小恐龙的大森林", editedBody)!!
            assertTrue(changed)
            assertEquals("小恐龙的大森林", updated.title)
            assertEquals("title.wav", updated.titleAudioFileName)

            val first = updated.clauses.first()
            assertTrue(first.text.contains("小恐龙"))
            assertTrue(first.text.contains("房间"))
            assertEquals(origClauses.first().id, first.id)
            assertEquals(origClauses.first().audioSpan, first.audioSpan)

            val last = updated.clauses.last()
            assertEquals("树上的小鸟唱着欢快的歌。", last.text)
            assertEquals(origClauses.last().id, last.id)
            assertEquals(origClauses.last().audioSpan, last.audioSpan)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun deletedSentenceDroppedNewAndRewrittenDetachedAndNeverResurrectsDeletedAudio() {
        val tmp = createTempDirectory("fulltext-del-add-").toFile()
        try {
            val repo = StoryBookRepository(tmp)
            val blank = repo.createBlankBook()
            val wav = repo.newStagingWav().also { makeNonSilentWav(it, 6000) }
            val (b1, _) = repo.insertUtteranceWithClauses(
                bookId = blank.id,
                afterClauseId = null,
                rawText = "小恐龙走出山洞。",
                takeId = "take_1",
                takeWavFile = wav,
                uttStartMs = 0L,
                uttEndMs = 2000L,
                takeDurationMs = 6000L,
            )
            val (b2, _) = repo.insertUtteranceWithClauses(
                bookId = b1.id,
                afterClauseId = b1.clauses.last().id,
                rawText = "呃那个不对不对重来。",
                takeId = "take_1",
                takeWavFile = wav,
                uttStartMs = 2000L,
                uttEndMs = 4000L,
                takeDurationMs = 6000L,
            )
            val (book, _) = repo.insertUtteranceWithClauses(
                bookId = b2.id,
                afterClauseId = b2.clauses.last().id,
                rawText = "阳光照在草地上。",
                takeId = "take_1",
                takeWavFile = wav,
                uttStartMs = 4000L,
                uttEndMs = 6000L,
                takeDurationMs = 6000L,
            )

            // Delete middle blooper, completely rewrite a sentence, and append a brand-new sentence
            val edited = """
                小恐龙走出山洞。

                天上的月亮像弯弯的小船。

                阳光照在草地上。

                最后他们开心地回家了。
            """.trimIndent()

            val (updated, changed) = repo.applyFullTextEdit(book.id, "彩虹城堡历险记", edited)!!
            assertTrue(changed)
            // Completely renamed title drops old unrelated title audio (none here anyway, verified separately)
            assertEquals(4, updated.clauses.size)

            // Clause 0: preserved from [0, 2000]
            assertEquals("小恐龙走出山洞。", updated.clauses[0].text)
            assertEquals(AudioSpan("take_1", 0L, 2000L), updated.clauses[0].audioSpan)

            // Clause 1: brand new / unrelated text -> detached (null audio)
            assertEquals("天上的月亮像弯弯的小船。", updated.clauses[1].text)
            assertNull(updated.clauses[1].audioSpan)

            // Clause 2: preserved from [4000, 6000] (middle [2000, 4000] blooper skipped!)
            assertEquals("阳光照在草地上。", updated.clauses[2].text)
            assertEquals(AudioSpan("take_1", 4000L, 6000L), updated.clauses[2].audioSpan)

            // Clause 3: newly added sentence -> null audio
            assertEquals("最后他们开心地回家了。", updated.clauses[3].text)
            assertNull(updated.clauses[3].audioSpan)

            // Merged audio export only includes the 2 kept clips (~4s + pause), skipping deleted [2000..4000]
            val mergedDest = File(tmp, "merged.wav")
            val exported = BookExporter.exportMergedAudio(updated, mergedDest)
            assertNotNull(exported)
            val durMs = WavAudioFiles.wavDurationMs(exported!!.first)
            assertTrue("Expected ~4.3s merged audio, got ${durMs}ms", durMs in 3800L..5000L)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun mergingAcrossDeletedMiddleClauseDoesNotSwallowDeletedAudioSpan() {
        val tmp = createTempDirectory("fulltext-gap-").toFile()
        try {
            val repo = StoryBookRepository(tmp)
            val blank = repo.createBlankBook()
            val wav = repo.newStagingWav().also { makeNonSilentWav(it, 6000) }
            val (b1, _) = repo.insertUtteranceWithClauses(blank.id, null, "小恐龙出发，", "take_1", wav, 0L, 1500L, 6000L)
            val (b2, _) = repo.insertUtteranceWithClauses(b1.id, b1.clauses.last().id, "呃这句说错了删掉，", "take_1", wav, 1500L, 4500L, 6000L)
            val (book, _) = repo.insertUtteranceWithClauses(b2.id, b2.clauses.last().id, "到了河边。", "take_1", wav, 4500L, 6000L, 6000L)

            // Parent deleted middle clause and merged first + third into one short clause
            val (updated, _) = repo.applyFullTextEdit(book.id, book.title, "小恐龙出发，到了河边。")!!
            assertEquals(1, updated.clauses.size)
            val span = updated.clauses.first().audioSpan
            assertNotNull(span)
            // Must NOT cover [0..6000] which would include the deleted [1500..4500] blooper!
            assertTrue("Span duration must not swallow deleted middle clip: $span", (span!!.endMs - span.startMs) <= 2000L)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun splittingSingleClauseAndMergingAdjacentClausesPreserveMonotonicSpans() {
        val tmp = createTempDirectory("fulltext-split-merge-").toFile()
        try {
            val repo = StoryBookRepository(tmp)
            val blank = repo.createBlankBook()
            val wav = repo.newStagingWav().also { makeNonSilentWav(it, 4000) }
            val (book, _) = repo.insertUtteranceWithClauses(
                bookId = blank.id,
                afterClauseId = null,
                rawText = "小恐龙开心地跑进大森林里去了。",
                takeId = "take_1",
                takeWavFile = wav,
                uttStartMs = 0L,
                uttEndMs = 4000L,
                takeDurationMs = 4000L,
            )
            assertEquals(1, book.clauses.size)

            // Split single 15-char clause into two paragraphs
            val (splitBook, _) = repo.applyFullTextEdit(
                book.id,
                book.title,
                "小恐龙开心地跑。\n\n进大森林里去了。",
            )!!
            assertEquals(2, splitBook.clauses.size)
            val s0 = splitBook.clauses[0].audioSpan!!
            val s1 = splitBook.clauses[1].audioSpan!!
            assertEquals(0L, s0.startMs)
            assertEquals(s0.endMs, s1.startMs)
            assertEquals(4000L, s1.endMs)
            assertTrue(s0.endMs in 1000L..3000L)

            // Now merge the two adjacent clauses back into one clause
            val (mergedBook, _) = repo.applyFullTextEdit(
                splitBook.id,
                splitBook.title,
                "小恐龙开心地跑进大森林里去了。",
            )!!
            assertEquals(1, mergedBook.clauses.size)
            assertEquals(AudioSpan("take_1", 0L, 4000L), mergedBook.clauses[0].audioSpan)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun completeTitleRenameClearsUnrelatedTitleAudioAndSingleSnapshotRestoresBook() {
        val tmp = createTempDirectory("fulltext-title-snap-").toFile()
        try {
            val (repo, book) = buildSampleBookWithAudio(tmp)
            assertEquals("title.wav", book.titleAudioFileName)
            val snapsBefore = repo.timeMachine.listSnapshots(book.id).size

            val (updated, changed) = repo.applyFullTextEdit(
                book.id,
                "太空飞船奇遇记",
                "全新的第一句话。",
            )!!
            assertTrue(changed)
            assertEquals("太空飞船奇遇记", updated.title)
            assertEquals("", updated.titleAudioFileName)
            assertEquals(1, updated.clauses.size)
            assertNull(updated.clauses.first().audioSpan)

            val snapsAfter = repo.timeMachine.listSnapshots(book.id)
            assertEquals(snapsBefore + 1, snapsAfter.size)
            val latestSnap = snapsAfter.first()
            assertEquals("全文校对", latestSnap.actionLabel)
            assertEquals("edit_full_text", latestSnap.toolName)

            val restored = repo.restoreSnapshot(book.id, latestSnap.id)!!
            assertEquals(book.clauses.size, restored.clauses.size)
            assertEquals(book.clauses.first().audioSpan, restored.clauses.first().audioSpan)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun toolBusAllowsUserDirectAndDeniesAiRolesForEditFullText() {
        val tmp = createTempDirectory("fulltext-toolbus-").toFile()
        try {
            val (repo, book) = buildSampleBookWithAudio(tmp)
            val bus = ToolBus(repo)

            for (aiRole in listOf(
                AgentTaskRole.SILENT_COPY_EDITOR,
                AgentTaskRole.VOICE_REVISION,
                AgentTaskRole.ILLUSTRATION_COMPILER,
                AgentTaskRole.CORNER_VOICE_SPARK,
            )) {
                val denied = bus.dispatch(
                    aiRole,
                    ToolRequest(
                        ToolId.EDIT_FULL_TEXT,
                        mapOf("bookId" to book.id, "title" to "改名", "fullText" to "改字。"),
                    ),
                )
                assertTrue("Expected Denied for $aiRole", denied is ToolResult.Denied)
            }

            val ok = bus.dispatch(
                AgentTaskRole.USER_DIRECT,
                ToolRequest(
                    ToolId.EDIT_FULL_TEXT,
                    mapOf("bookId" to book.id, "title" to "小恐龙的大森林", "fullText" to "小恐龙慢慢地走出了房间。"),
                ),
            )
            assertTrue(ok is ToolResult.Success)
            val updated = (ok as ToolResult.Success).book!!
            assertEquals("小恐龙的大森林", updated.title)
            assertEquals("小恐龙慢慢地走出了房间。", updated.clauses.first().text)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
