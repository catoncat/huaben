package com.luna.storynote

import com.luna.storynote.audio.WavFileStreamWriter
import com.luna.storynote.storage.StoryBookRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class StorageMigrationAndTimeMachineTest {
    @Test
    fun v3BookMigratesToV4PreservingClipsAndSupportingWordBoxCompat() {
        val tmp = createTempDirectory("v3-migration-test-").toFile()
        try {
            val bookDir = File(tmp, "books/legacy_book_1").apply { mkdirs() }
            val clipsDir = File(bookDir, "clips").apply { mkdirs() }
            val wav0 = File(clipsDir, "s_0.wav")
            WavFileStreamWriter(wav0).apply {
                appendSilenceMs(2400)
                close()
            }

            val sentences = JSONArray().put(
                JSONObject()
                    .put("id", "s_0")
                    .put("text", "从前有一只蓝色的小恐龙，他住在一座会喷棉花糖的火山上。")
                    .put("audio_file", "clips/s_0.wav")
                    .put("duration_sec", 2.4)
                    .put("paragraph_break_after", true),
            )
            val v3Json = JSONObject()
                .put("version", 3)
                .put("id", "legacy_book_1")
                .put("book_number", 1)
                .put("title", "蓝色小恐龙")
                .put("title_audio_file", "")
                .put("cover_color_index", 0)
                .put("emblem_index", 0)
                .put("sentences", sentences)
                .put("created_at_ms", 1000L)
                .put("updated_at_ms", 2000L)
            File(bookDir, "book.json").writeText(v3Json.toString(2), Charsets.UTF_8)

            // Write legacy v3 word_box.json without take_start_ms / take_end_ms
            File(tmp, "word_box.json").writeText(
                JSONArray().put(
                    JSONObject()
                        .put("word", "恐龙")
                        .put("pinyins", JSONArray(listOf("kǒng", "lóng")))
                        .put("book_id", "legacy_book_1")
                        .put("sentence_id", "s_0")
                        .put("sentence_text", "从前有一只蓝色的小恐龙")
                        .put("sentence_audio_path", wav0.absolutePath)
                        .put("collected_at_ms", 1500L),
                ).toString(2),
                Charsets.UTF_8,
            )

            val repo = StoryBookRepository(tmp)
            val migrated = repo.getBook("legacy_book_1")
            assertNotNull(migrated)
            assertTrue("Expected legacy WAV preserved", wav0.isFile)
            assertTrue("Expected >= 2 clauses after v3->v4 migration", migrated!!.clauses.size >= 2)
            assertEquals(1, migrated.takes.size)
            assertEquals("clips/s_0.wav", migrated.takes[0].wavFileName)
            assertTrue(migrated.clauses.last().paragraphBreakAfter)
            assertEquals(4, JSONObject(File(bookDir, "book.json").readText()).optInt("version"))

            val words = repo.listCollectedWords()
            assertEquals(1, words.size)
            assertEquals("恐龙", words[0].word)
            assertEquals(0L, words[0].takeStartMs)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun timeMachineSnapshotsProtectAudioFilesAndRestoreClauseAndBook() {
        val tmp = createTempDirectory("time-machine-test-").toFile()
        try {
            val repo = StoryBookRepository(tmp)
            val book = repo.createBlankBook()

            val wav1 = repo.newStagingWav()
            WavFileStreamWriter(wav1).apply { appendSilenceMs(1200); close() }
            val (b1, clauses1) = repo.insertUtteranceWithClauses(
                bookId = book.id,
                afterClauseId = null,
                rawText = "小恐龙坐在火山上。",
                takeId = "take_v1",
                takeWavFile = wav1,
                uttStartMs = 0L,
                uttEndMs = 1200L,
                takeDurationMs = 1200L,
            )
            val clauseId = clauses1.first().id
            val takeV1File = b1.takeWavFile("take_v1")!!
            assertTrue(takeV1File.isFile)

            // Re-record clause -> must snapshot old text & audio and NOT delete take_v1.wav
            val wav2 = repo.newStagingWav()
            WavFileStreamWriter(wav2).apply { appendSilenceMs(1400); close() }
            val b2 = repo.replaceClauseWithTake(
                bookId = book.id,
                clauseId = clauseId,
                newRawText = "小狐狸飞上了月亮。",
                takeId = "take_v2",
                takeWavFile = wav2,
                uttStartMs = 0L,
                uttEndMs = 1400L,
                takeDurationMs = 1400L,
            )!!
            assertEquals("小狐狸飞上了月亮。", b2.clauses.first().text)
            assertTrue("Old take_v1.wav must NOT be deleted", takeV1File.isFile)

            // Erase clause -> must snapshot again and NOT delete take_v2.wav
            val takeV2File = b2.takeWavFile("take_v2")!!
            val (b3, removed) = repo.removeClause(book.id, clauseId)!!
            assertNotNull(removed)
            assertTrue(b3.clauses.isEmpty())
            assertTrue("take_v2.wav must NOT be deleted on erase", takeV2File.isFile)

            val history = repo.timeMachine.findClauseHistory(b3, clauseId)
            assertEquals(2, history.size)
            assertEquals("小狐狸飞上了月亮。", history[0].clause.text)
            assertEquals("小恐龙坐在火山上。", history[1].clause.text)
            assertNotNull(history[1].wavFile)

            val protectedPaths = repo.timeMachine.collectProtectedAssetPaths(b3)
            assertTrue(protectedPaths.contains("takes/take_v1.wav"))
            assertTrue(protectedPaths.contains("takes/take_v2.wav"))

            // Restore original v1 clause from history
            val restored = repo.restoreClauseFromSnapshot(book.id, history[1].snapshotId, clauseId)!!
            assertEquals(1, restored.clauses.size)
            assertEquals("小恐龙坐在火山上。", restored.clauses.first().text)
            assertEquals("take_v1", restored.clauses.first().audioSpan?.takeId)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
