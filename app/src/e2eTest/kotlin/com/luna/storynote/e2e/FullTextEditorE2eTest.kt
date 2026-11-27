package com.luna.storynote.e2e

import com.luna.storynote.BookStudioController
import com.luna.storynote.e2e.fixtures.ContractProbe
import com.luna.storynote.e2e.fixtures.FakePlatformEnvironment
import com.luna.storynote.e2e.fixtures.HeadlessUiHarness
import com.luna.storynote.storage.StoryBookRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FullTextEditorE2eTest {
    @Rule @JvmField val tmp = TemporaryFolder()
    private val disposables = mutableListOf<() -> Unit>()

    @After fun tearDown() {
        disposables.reversed().forEach { runCatching { it() } }
        disposables.clear()
    }

    private fun awaitUntil(timeoutMs: Long = 4000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25L)
        }
        assertTrue("Condition timed out after ${timeoutMs}ms", cond())
    }

    @Test
    fun longPressProofreadGateAndFullTextSavePreserveAudioSpans() {
        val env = FakePlatformEnvironment(tmp.newFolder("env-fulltext"))
        val repo = StoryBookRepository(env.storageRoot)
        val blank = repo.createBlankBook()
        val takeWav = env.writeDeterministicWav(File(env.storageRoot, "staging/take_ft.wav"), 3600)
        repo.insertUtteranceWithClauses(
            bookId = blank.id,
            afterClauseId = null,
            rawText = "小孔龙慢慢的走出了防间。天上的太阳亮晶晶。",
            takeId = "take_ft",
            takeWavFile = takeWav,
            uttStartMs = 0L,
            uttEndMs = 3600L,
            takeDurationMs = 3600L,
        )
        repo.updateBookTitleAndCover(blank.id, "小孔龙出门", null, 0, 0)

        val beforeClauses = ContractProbe.readBookJson(env.storageRoot, blank.id).getJSONArray("clauses")
        val firstSpanBefore = beforeClauses.getJSONObject(0).getJSONObject("audio_span").toString()
        val secondSpanBefore = beforeClauses.getJSONObject(1).getJSONObject("audio_span").toString()

        val controller = BookStudioController(env).also { disposables.add { it.dispose() } }
        awaitUntil { controller.uiState.value.books.any { it.id == blank.id } }
        controller.openBook(blank.id)

        HeadlessUiHarness.openSession(controller, 900, 560).use { s ->
            val initial = s.renderFrames(10)
            assertTrue(initial.hasTag("proofread_button"))
            assertFalse(initial.hasTag("full_text_editor_sheet"))

            // Short tap must NOT open adult keyboard editor (child protection)
            val afterShortTap = s.tapTag("proofread_button")
            assertFalse(afterShortTap.hasTag("full_text_editor_sheet"))

            // Long press opens full-text editor sheet
            val opened = s.longPressTag("proofread_button")
            assertTrue(opened.hasTag("full_text_editor_sheet"))
            assertTrue(opened.hasTag("full_text_title_input"))
            assertTrue(opened.hasTag("full_text_body_input"))
            assertTrue(opened.hasTag("full_text_cancel"))
            assertTrue(opened.hasTag("full_text_save"))

            // Cancel closes editor with zero side effects
            val afterCancel = s.tapTag("full_text_cancel")
            assertFalse(afterCancel.hasTag("full_text_editor_sheet"))
            assertTrue(repo.timeMachine.listSnapshots(blank.id).isEmpty())

            // Re-open, fix typos, and save -> preserves audio spans and records one TimeMachine snapshot
            assertTrue(s.longPressTag("proofread_button").hasTag("full_text_editor_sheet"))
            controller.saveFullTextEdit(
                newTitle = "小恐龙出门",
                newFullText = "小恐龙慢慢地走出了房间。天上的太阳亮晶晶。",
            )
            awaitUntil {
                !controller.uiState.value.isFullTextEditorOpen &&
                    ContractProbe.readBookJson(env.storageRoot, blank.id).getString("title") == "小恐龙出门"
            }

            val afterSaveFrame = s.renderFrames(10)
            assertFalse(afterSaveFrame.hasTag("full_text_editor_sheet"))
            assertTrue(afterSaveFrame.hasText("小恐龙") && afterSaveFrame.hasText("房间"))

            val afterJson = ContractProbe.readBookJson(env.storageRoot, blank.id)
            assertEquals("小恐龙出门", afterJson.getString("title"))
            val afterClauses = afterJson.getJSONArray("clauses")
            assertEquals(2, afterClauses.length())
            assertTrue(afterClauses.getJSONObject(0).getString("text").contains("房间"))
            assertEquals(firstSpanBefore, afterClauses.getJSONObject(0).getJSONObject("audio_span").toString())
            assertEquals(secondSpanBefore, afterClauses.getJSONObject(1).getJSONObject("audio_span").toString())
            assertEquals(1, repo.timeMachine.listSnapshots(blank.id).size)
        }
    }
}
