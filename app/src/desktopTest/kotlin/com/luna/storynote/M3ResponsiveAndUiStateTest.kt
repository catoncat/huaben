package com.luna.storynote

import androidx.compose.ui.unit.dp
import com.luna.storynote.literacy.WordSpan
import com.luna.storynote.model.ActiveWordInspection
import com.luna.storynote.model.Clause
import com.luna.storynote.model.DeviceFormFactor
import com.luna.storynote.model.SoftAnnotation
import com.luna.storynote.storage.StoryBookRepository
import com.luna.storynote.ui.CandidateReplacement
import com.luna.storynote.ui.motion.bookshelfColumnCount
import com.luna.storynote.ui.motion.isLandscapeSpreadWidth
import com.luna.storynote.ui.motion.resolveDeviceFormFactor
import com.luna.storynote.ui.rankHomophoneCandidates
import com.luna.storynote.ui.resolveCandidateReplacement
import com.luna.storynote.ui.resolveSoftAnnotationForCell
import org.jetbrains.skia.Color as SkColor
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class M3ResponsiveAndUiStateTest {
    @Test
    fun deviceFormFactorAndBreakpointsMatchTargetDevices() {
        // Phone portrait
        assertEquals(DeviceFormFactor.PHONE, resolveDeviceFormFactor(390.dp, 844.dp))
        assertEquals(2, bookshelfColumnCount(390.dp))
        assertFalse(isLandscapeSpreadWidth(390.dp, 844.dp))

        // 7-inch tablet portrait
        assertEquals(DeviceFormFactor.TABLET_PORTRAIT, resolveDeviceFormFactor(560.dp, 900.dp))
        assertEquals(3, bookshelfColumnCount(560.dp))
        assertFalse(isLandscapeSpreadWidth(560.dp, 900.dp))

        // 7-inch tablet landscape min & standard
        assertEquals(DeviceFormFactor.TABLET_LANDSCAPE_OR_DESKTOP, resolveDeviceFormFactor(853.dp, 533.dp))
        assertEquals(4, bookshelfColumnCount(853.dp))
        assertTrue(isLandscapeSpreadWidth(853.dp, 533.dp))
        assertTrue(isLandscapeSpreadWidth(900.dp, 560.dp))

        // Portrait with >= 780dp width stays single-page (not landscape)
        assertFalse(isLandscapeSpreadWidth(800.dp, 1200.dp))
    }

    @Test
    fun softSuggestionRanksFirstInHomophoneCandidates() {
        val inspection = ActiveWordInspection(
            clauseId = "c1",
            span = WordSpan("座", 1, 2, listOf("zuò")),
            homophones = listOf("做", "作", "坐"),
            softSuggestion = "坐",
        )
        val ranked = rankHomophoneCandidates(inspection, listOf("做", "作", "坐"), '座')
        assertEquals("坐", ranked.first())
        assertEquals(ranked.distinct(), ranked)
        assertTrue(ranked.indexOf("坐") < ranked.indexOf("做"))
    }

    @Test
    fun controllerTimeMachineOpenAndCloseUpdatesState() {
        val tmp = createTempDirectory("m3-tm-state-").toFile()
        try {
            val env = DesktopPlatformEnvironment(
                storageRoot = File(tmp, "storage").apply { mkdirs() },
                privateConfigDir = File(tmp, "cfg").apply { mkdirs() },
                authCacheFile = File(tmp, "cfg/auth.json"),
                piAgentDir = File(tmp, "pi").apply { mkdirs() },
            )
            val repo = StoryBookRepository(env.storageRoot)
            val book = repo.createBlankBook()
            val controller = BookStudioController(env)
            try {
                val deadline = System.currentTimeMillis() + 3000L
                while (controller.uiState.value.books.isEmpty() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20L)
                }
                controller.openBook(book.id)
                assertFalse(controller.uiState.value.isTimeMachineOpen)

                controller.openTimeMachine("c_target")
                assertTrue(controller.uiState.value.isTimeMachineOpen)
                assertEquals("c_target", controller.uiState.value.timeMachineTargetClauseId)

                controller.closeTimeMachine()
                assertFalse(controller.uiState.value.isTimeMachineOpen)
                assertNull(controller.uiState.value.timeMachineTargetClauseId)
            } finally {
                controller.dispose()
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun decodeFileToImageBitmapHandlesMissingCorruptAndValidPng() {
        val tmp = createTempDirectory("m3-img-decode-").toFile()
        try {
            assertNull(decodeFileToImageBitmap(File(tmp, "missing.png")))

            val corrupt = File(tmp, "corrupt.png").apply { writeText("not-a-png") }
            assertNull(decodeFileToImageBitmap(corrupt))

            val valid = File(tmp, "valid.png")
            val surface = Surface.makeRasterN32Premul(32, 24)
            surface.canvas.drawRect(
                org.jetbrains.skia.Rect.makeWH(32f, 24f),
                Paint().apply { color = SkColor.makeRGB(212, 74, 46) },
            )
            valid.writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
            surface.close()

            val bmp = decodeFileToImageBitmap(valid)
            assertNotNull(bmp)
            assertEquals(32, bmp!!.width)
            assertEquals(24, bmp.height)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun singleCharSoftSuggestionReplacesAnnotatedOffsetInMultiCharPhrase() {
        val tmp = createTempDirectory("m3-soft-offset-").toFile()
        try {
            val env = DesktopPlatformEnvironment(
                storageRoot = File(tmp, "storage").apply { mkdirs() },
                privateConfigDir = File(tmp, "cfg").apply { mkdirs() },
                authCacheFile = File(tmp, "cfg/auth.json"),
                piAgentDir = File(tmp, "pi").apply { mkdirs() },
            )
            val repo = StoryBookRepository(env.storageRoot)
            val created = repo.createBlankBook()
            val (withClause, inserted) = repo.insertUtteranceWithClauses(
                bookId = created.id,
                afterClauseId = null,
                rawText = "蓝色小恐龙。",
                takeId = "",
                takeWavFile = null,
                uttStartMs = 0L,
                uttEndMs = 0L,
                takeDurationMs = 0L,
            )
            val clauseId = inserted.first().id
            repo.annotateClauseSoftSuggestions(
                bookId = withClause.id,
                clauseId = clauseId,
                annotations = listOf(
                    SoftAnnotation(
                        charIndex = 4,
                        length = 1,
                        suggestedWord = "笼",
                        correctedPinyin = "lóng",
                        reason = "同音字",
                    ),
                ),
            )

            val controller = BookStudioController(env)
            try {
                val initDeadline = System.currentTimeMillis() + 3000L
                while (controller.uiState.value.books.isEmpty() && System.currentTimeMillis() < initDeadline) {
                    Thread.sleep(20L)
                }
                controller.openBook(created.id)
                val clause = controller.uiState.value.activeBook!!.clauses.first()

                // 1) Tap on '恐' (index 3, offset 1 in "小恐龙") -> still targets annotated '龙' (offset 2) for "笼"
                controller.onCharacterTapped(clause, 3)
                val tappedFirst = controller.uiState.value.inspectedWord!!
                assertEquals("小恐龙", tappedFirst.span.word)
                assertEquals(1, tappedFirst.initialCharIndexInWord)
                assertEquals(2, tappedFirst.softAnnotationOffsetInWord)
                assertEquals(1, tappedFirst.softAnnotationLength)
                assertEquals("笼", tappedFirst.softSuggestion)
                assertEquals("笼", rankHomophoneCandidates(tappedFirst, emptyList(), '恐').first())
                assertEquals(
                    CandidateReplacement.SingleChar(2, "笼"),
                    resolveCandidateReplacement(tappedFirst, tappedFirst.initialCharIndexInWord, "笼"),
                )
                // Non-soft 1-char candidate on active tab 1 replaces offset 1
                assertEquals(
                    CandidateReplacement.SingleChar(1, "空"),
                    resolveCandidateReplacement(tappedFirst, tappedFirst.initialCharIndexInWord, "空"),
                )

                // 2) Tap on '龙' (index 4, offset 2 in "小恐龙") and apply "笼" -> "蓝色小恐笼。"
                controller.onCharacterTapped(clause, 4)
                val tappedSecond = controller.uiState.value.inspectedWord!!
                assertEquals(2, tappedSecond.initialCharIndexInWord)
                assertEquals(2, tappedSecond.softAnnotationOffsetInWord)
                val action = resolveCandidateReplacement(
                    tappedSecond,
                    tappedSecond.initialCharIndexInWord,
                    "笼",
                ) as CandidateReplacement.SingleChar
                assertEquals(2, action.charOffsetInWord)

                controller.applySingleCharHomophoneReplacement(tappedSecond, action.charOffsetInWord, action.candidateChar)
                val replaceDeadline = System.currentTimeMillis() + 3000L
                while (controller.uiState.value.activeBook?.clauses?.firstOrNull()?.text == "蓝色小恐龙。" &&
                    System.currentTimeMillis() < replaceDeadline
                ) {
                    Thread.sleep(20L)
                }
                assertEquals("蓝色小恐笼。", controller.uiState.value.activeBook!!.clauses.first().text)
            } finally {
                controller.dispose()
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun resolveSoftAnnotationForCellRejectsNegativeAndEmptyBounds() {
        val clause = Clause(
            id = "c_bounds",
            text = "小恐龙",
            audioSpan = null,
            paragraphBreakAfter = false,
            softAnnotations = listOf(
                SoftAnnotation(charIndex = -1, length = 3, suggestedWord = "坏", correctedPinyin = "a b c"),
                SoftAnnotation(charIndex = 0, length = 0, suggestedWord = "空", correctedPinyin = "kōng"),
                SoftAnnotation(charIndex = 1, length = -2, suggestedWord = "负", correctedPinyin = "fù"),
                SoftAnnotation(charIndex = 2, length = 1, suggestedWord = "笼", correctedPinyin = "lóng"),
            ),
        )
        assertEquals(null to false, resolveSoftAnnotationForCell(clause, -1))
        assertEquals(null to false, resolveSoftAnnotationForCell(clause, 0))
        assertEquals(null to false, resolveSoftAnnotationForCell(clause, 1))
        assertEquals("lóng" to true, resolveSoftAnnotationForCell(clause, 2))
    }

    @Test
    fun restoreSnapshotClampsCurrentPageIndexWhenRestoringFewerPages() {
        val tmp = createTempDirectory("m3-restore-clamp-").toFile()
        try {
            val env = DesktopPlatformEnvironment(
                storageRoot = File(tmp, "storage").apply { mkdirs() },
                privateConfigDir = File(tmp, "cfg").apply { mkdirs() },
                authCacheFile = File(tmp, "cfg/auth.json"),
                piAgentDir = File(tmp, "pi").apply { mkdirs() },
            )
            val repo = StoryBookRepository(env.storageRoot)
            val created = repo.createBlankBook()
            // Page 0: two paragraphs (maxParas = 2)
            repo.insertUtteranceWithClauses(created.id, null, "第一段。", "", null, 0L, 0L, 0L, forceParagraphBreak = true)
            repo.insertUtteranceWithClauses(
                created.id,
                repo.getBook(created.id)!!.clauses.last().id,
                "第二段。",
                "",
                null,
                0L,
                0L,
                0L,
                forceParagraphBreak = true,
            )
            // Page 1: third paragraph (snapshots the 1-page 2-paragraph book before expanding to 2 pages)
            repo.insertUtteranceWithClauses(
                created.id,
                repo.getBook(created.id)!!.clauses.last().id,
                "第三段。",
                "",
                null,
                0L,
                0L,
                0L,
                forceParagraphBreak = true,
            )
            val onePageSnapId = repo.timeMachine.listSnapshots(created.id).first().id
            assertEquals(2, repo.getBook(created.id)!!.paginate().size)

            val controller = BookStudioController(env)
            try {
                val initDeadline = System.currentTimeMillis() + 3000L
                while (controller.uiState.value.books.isEmpty() && System.currentTimeMillis() < initDeadline) {
                    Thread.sleep(20L)
                }
                controller.openBook(created.id)
                controller.setCurrentPage(1)
                assertEquals(1, controller.uiState.value.currentPageIndex)

                controller.restoreSnapshot(onePageSnapId)
                val restoreDeadline = System.currentTimeMillis() + 3000L
                while ((controller.uiState.value.activeBook?.paginate()?.size ?: 2) > 1 &&
                    System.currentTimeMillis() < restoreDeadline
                ) {
                    Thread.sleep(20L)
                }
                assertEquals(1, controller.uiState.value.activeBook!!.paginate().size)
                assertEquals(0, controller.uiState.value.currentPageIndex)
            } finally {
                controller.dispose()
            }
        } finally {
            tmp.deleteRecursively()
        }
    }
}
