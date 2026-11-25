package com.luna.storynote

import com.luna.storynote.agent.AgentTaskRole
import com.luna.storynote.agent.ToolBus
import com.luna.storynote.agent.ToolId
import com.luna.storynote.agent.ToolRequest
import com.luna.storynote.agent.ToolResult
import com.luna.storynote.agent.ToolWhitelists
import com.luna.storynote.audio.WavFileStreamWriter
import com.luna.storynote.storage.StoryBookRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ToolBusAndWhitelistTest {
    @Test
    fun whitelistsEnforceMinimumPermissionsPerRole() {
        assertEquals(ToolId.entries.toSet(), ToolWhitelists.allowedTools(AgentTaskRole.USER_DIRECT))

        val copyAllowed = ToolWhitelists.allowedTools(AgentTaskRole.SILENT_COPY_EDITOR)
        assertEquals(
            setOf(
                ToolId.READ_BOOK_STATE,
                ToolId.READ_PAGE,
                ToolId.READ_BOOK_BIBLE,
                ToolId.ANNOTATE_SOFT_SUGGESTION,
                ToolId.UPDATE_BOOK_BIBLE,
            ),
            copyAllowed,
        )
        assertFalse(ToolWhitelists.isAllowed(AgentTaskRole.SILENT_COPY_EDITOR, ToolId.REPLACE_SPAN))
        assertFalse(ToolWhitelists.isAllowed(AgentTaskRole.SILENT_COPY_EDITOR, ToolId.ERASE_CLAUSE))
        assertFalse(ToolWhitelists.isAllowed(AgentTaskRole.SILENT_COPY_EDITOR, ToolId.APPEND_CLAUSE))
        assertFalse(ToolWhitelists.isAllowed(AgentTaskRole.SILENT_COPY_EDITOR, ToolId.INSERT_CLAUSE_AFTER))
        assertFalse(ToolWhitelists.isAllowed(AgentTaskRole.SILENT_COPY_EDITOR, ToolId.RERECORD_CLAUSE_AUDIO))

        assertEquals(
            setOf(ToolId.READ_BOOK_STATE, ToolId.READ_BOOK_BIBLE),
            ToolWhitelists.allowedTools(AgentTaskRole.CORNER_VOICE_SPARK),
        )
        assertEquals(
            setOf(ToolId.READ_BOOK_STATE, ToolId.READ_PAGE, ToolId.READ_BOOK_BIBLE, ToolId.REPLACE_SPAN),
            ToolWhitelists.allowedTools(AgentTaskRole.VOICE_REVISION),
        )
        assertEquals(
            setOf(ToolId.READ_PAGE, ToolId.READ_BOOK_BIBLE, ToolId.ATTACH_PAGE_ILLUSTRATION),
            ToolWhitelists.allowedTools(AgentTaskRole.ILLUSTRATION_COMPILER),
        )
    }

    @Test
    fun toolBusEnforcesDenialsRecordsSnapshotsAndPreservesAudioFiles() {
        val tmp = createTempDirectory("toolbus-test-").toFile()
        try {
            val repo = StoryBookRepository(tmp)
            val bus = ToolBus(repo)
            val book = repo.createBlankBook()
            val wav1 = repo.newStagingWav()
            WavFileStreamWriter(wav1).apply { appendSilenceMs(900); close() }

            val appendRes = bus.dispatch(
                AgentTaskRole.USER_DIRECT,
                ToolRequest(
                    ToolId.APPEND_CLAUSE,
                    mapOf(
                        "bookId" to book.id,
                        "text" to "小恐龙坐在火山旁。",
                        "takeId" to "take_01",
                        "takeFile" to wav1,
                        "uttStartMs" to 0L,
                        "uttEndMs" to 900L,
                        "takeDurationMs" to 900L,
                    ),
                ),
            )
            assertTrue(appendRes.isSuccess)
            val afterAppend = (appendRes as ToolResult.Success).book!!
            val clauseId = afterAppend.clauses.first().id
            val takeFileOnDisk = afterAppend.takeWavFile("take_01")!!
            assertTrue(takeFileOnDisk.isFile)

            // SILENT_COPY_EDITOR denied write operations
            for (forbidden in listOf(
                ToolId.REPLACE_SPAN,
                ToolId.ERASE_CLAUSE,
                ToolId.APPEND_CLAUSE,
                ToolId.INSERT_CLAUSE_AFTER,
                ToolId.RERECORD_CLAUSE_AUDIO,
            )) {
                val denied = bus.dispatch(
                    AgentTaskRole.SILENT_COPY_EDITOR,
                    ToolRequest(forbidden, mapOf("bookId" to book.id, "clauseId" to clauseId)),
                )
                assertTrue("Expected Denied for $forbidden", denied is ToolResult.Denied)
                assertFalse(denied.isSuccess)
            }

            // CORNER_VOICE_SPARK strictly read-only
            val sparkDenied = bus.dispatch(
                AgentTaskRole.CORNER_VOICE_SPARK,
                ToolRequest(ToolId.UPDATE_BOOK_BIBLE, mapOf("bookId" to book.id, "plotSummary" to "x")),
            )
            assertTrue(sparkDenied is ToolResult.Denied)

            // SILENT_COPY_EDITOR can annotate soft suggestion & update book bible without altering Clause.text
            val softRes = bus.dispatch(
                AgentTaskRole.SILENT_COPY_EDITOR,
                ToolRequest(
                    ToolId.ANNOTATE_SOFT_SUGGESTION,
                    mapOf(
                        "bookId" to book.id,
                        "clauseId" to clauseId,
                        "charIndex" to 3,
                        "length" to 1,
                        "suggestedWord" to "座",
                        "correctedPinyin" to "zuò",
                        "reason" to "同音字",
                    ),
                ),
            )
            assertTrue(softRes.isSuccess)
            assertEquals("小恐龙坐在火山旁。", repo.getBook(book.id)!!.clauses.first().text)
            assertEquals(1, repo.getBook(book.id)!!.clauses.first().softAnnotations.size)

            val bibleRes = bus.dispatch(
                AgentTaskRole.SILENT_COPY_EDITOR,
                ToolRequest(
                    ToolId.UPDATE_BOOK_BIBLE,
                    mapOf(
                        "bookId" to book.id,
                        "protagonistTraits" to listOf("蓝色小恐龙"),
                        "keyScenesAndProps" to listOf("棉花糖火山"),
                        "plotSummary" to "小恐龙在火山旁休息",
                    ),
                ),
            )
            assertTrue(bibleRes.isSuccess)

            // Toggle paragraph break
            val paraRes = bus.dispatch(
                AgentTaskRole.USER_DIRECT,
                ToolRequest(ToolId.TOGGLE_PARAGRAPH_BREAK, mapOf("bookId" to book.id, "clauseId" to clauseId)),
            )
            assertTrue(paraRes.isSuccess)
            assertTrue(repo.getBook(book.id)!!.clauses.first().paragraphBreakAfter)

            // VOICE_REVISION targeted replace_span
            val revRes = bus.dispatch(
                AgentTaskRole.VOICE_REVISION,
                ToolRequest(
                    ToolId.REPLACE_SPAN,
                    mapOf(
                        "bookId" to book.id,
                        "clauseId" to clauseId,
                        "startIndex" to 0,
                        "endIndex" to 3,
                        "replacement" to "小狐狸",
                    ),
                ),
            )
            assertTrue(revRes.isSuccess)
            assertEquals("小狐狸坐在火山旁。", repo.getBook(book.id)!!.clauses.first().text)
            assertTrue(takeFileOnDisk.isFile)

            // Attach & select illustration using imageFileName
            val illDir = File(afterAppend.bookDir, "illustrations").apply { mkdirs() }
            File(illDir, "p0.png").writeBytes(byteArrayOf(1, 2, 3, 4))
            val attachRes = bus.dispatch(
                AgentTaskRole.ILLUSTRATION_COMPILER,
                ToolRequest(
                    ToolId.ATTACH_PAGE_ILLUSTRATION,
                    mapOf("bookId" to book.id, "pageIndex" to 0, "imageFileName" to "illustrations/p0.png", "promptUsed" to "wc"),
                ),
            )
            assertTrue(attachRes.isSuccess)
            val illId = repo.getBook(book.id)!!.illustrations.first().id
            val selectRes = bus.dispatch(
                AgentTaskRole.USER_DIRECT,
                ToolRequest(
                    ToolId.SELECT_PAGE_ILLUSTRATION,
                    mapOf("bookId" to book.id, "pageIndex" to 0, "illustrationId" to illId),
                ),
            )
            assertTrue(selectRes.isSuccess)

            // Add word box card (idempotent)
            val wb1 = bus.dispatch(
                AgentTaskRole.USER_DIRECT,
                ToolRequest(
                    ToolId.ADD_WORD_BOX_CARD,
                    mapOf("bookId" to book.id, "clauseId" to clauseId, "word" to "火山", "pinyins" to listOf("huǒ", "shān")),
                ),
            ) as ToolResult.Success
            assertEquals(true, wb1.data["added"])
            val wb2 = bus.dispatch(
                AgentTaskRole.USER_DIRECT,
                ToolRequest(
                    ToolId.ADD_WORD_BOX_CARD,
                    mapOf("bookId" to book.id, "clauseId" to clauseId, "word" to "火山", "pinyins" to listOf("huǒ", "shān")),
                ),
            ) as ToolResult.Success
            assertEquals(false, wb2.data["added"])
            assertEquals(1, repo.listCollectedWords().size)

            // Snapshots recorded with tool names & restore works
            val snapshots = repo.timeMachine.listSnapshots(book.id)
            val toolNames = snapshots.map { it.toolName }.toSet()
            assertTrue(toolNames.contains("append_clause"))
            assertTrue(toolNames.contains("annotate_soft_suggestion"))
            assertTrue(toolNames.contains("toggle_paragraph_break"))
            assertTrue(toolNames.contains("replace_span"))
            assertTrue(toolNames.contains("attach_page_illustration"))
            assertTrue(toolNames.contains("select_page_illustration"))

            val beforeRevSnap = snapshots.first { it.toolName == "replace_span" }
            val restoreRes = bus.dispatch(
                AgentTaskRole.USER_DIRECT,
                ToolRequest(ToolId.RESTORE_HISTORY_SNAPSHOT, mapOf("bookId" to book.id, "snapshotId" to beforeRevSnap.id)),
            )
            assertTrue(restoreRes.isSuccess)
            assertEquals("小恐龙坐在火山旁。", repo.getBook(book.id)!!.clauses.first().text)

            // Invalid IDs / bounds return Error
            val missingBook = bus.dispatch(
                AgentTaskRole.USER_DIRECT,
                ToolRequest(ToolId.READ_BOOK_STATE, mapOf("bookId" to "missing_book")),
            )
            assertTrue(missingBook is ToolResult.Error)
            assertNotNull((missingBook as ToolResult.Error).message)

            val badSpan = bus.dispatch(
                AgentTaskRole.USER_DIRECT,
                ToolRequest(
                    ToolId.REPLACE_SPAN,
                    mapOf("bookId" to book.id, "clauseId" to clauseId, "startIndex" to 5, "endIndex" to 2, "replacement" to "好"),
                ),
            )
            assertTrue(badSpan is ToolResult.Error)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun toolBusRejectsPathTraversalInvalidBookIdBlankRerecordAndEmptyReplaceSpan() {
        val root = createTempDirectory("toolbus-sec-").toFile()
        try {
            val storageRoot = File(root, "storage").apply { mkdirs() }
            val repo = StoryBookRepository(storageRoot)
            val bus = ToolBus(repo)
            val book = repo.createBlankBook()
            val illDir = File(book.bookDir, "illustrations")

            // Seed a real clause
            val appended = bus.dispatch(
                AgentTaskRole.USER_DIRECT,
                ToolRequest(ToolId.APPEND_CLAUSE, mapOf("bookId" to book.id, "text" to "小恐龙坐在火山旁。")),
            ) as ToolResult.Success
            val clauseId = appended.book!!.clauses.first().id
            val snapCountBefore = repo.timeMachine.listSnapshots(book.id).size

            // 1. Rogue book outside booksDir via bookId traversal
            val rogueDir = File(storageRoot, "rogue").apply { mkdirs() }
            File(rogueDir, "book.json").writeText("""{"version":4,"id":"rogue","title":"secret","clauses":[]}""")
            for (badBookId in listOf("", "   ", "../rogue", "../../rogue", "a/b", "a\\b", "..")) {
                val res = bus.dispatch(AgentTaskRole.USER_DIRECT, ToolRequest(ToolId.READ_BOOK_STATE, mapOf("bookId" to badBookId)))
                assertTrue("Expected ToolResult.Error for bookId='$badBookId'", res is ToolResult.Error)
            }
            assertEquals(null, repo.getBook("../rogue"))

            // 2. Path traversal via imageFileName (relative & absolute) and external imageFile
            val outsideSecret = File(root, "outside_secret.txt").apply { writeText("SENSITIVE_DATA") }
            val illCountBefore = repo.getBook(book.id)!!.illustrations.size
            for (badPath in listOf("../../../outside_secret.txt", "../outside_secret.txt", outsideSecret.absolutePath)) {
                val res = bus.dispatch(
                    AgentTaskRole.ILLUSTRATION_COMPILER,
                    ToolRequest(
                        ToolId.ATTACH_PAGE_ILLUSTRATION,
                        mapOf("bookId" to book.id, "pageIndex" to 0, "imageFileName" to badPath, "promptUsed" to "wc"),
                    ),
                )
                assertTrue("Expected ToolResult.Error for imageFileName='$badPath'", res is ToolResult.Error)
            }
            val externalFileRes = bus.dispatch(
                AgentTaskRole.ILLUSTRATION_COMPILER,
                ToolRequest(
                    ToolId.ATTACH_PAGE_ILLUSTRATION,
                    mapOf("bookId" to book.id, "pageIndex" to 0, "imageFile" to outsideSecret, "promptUsed" to "wc"),
                ),
            )
            assertTrue("Expected ToolResult.Error for external File", externalFileRes is ToolResult.Error)
            assertEquals(illCountBefore, repo.getBook(book.id)!!.illustrations.size)
            assertEquals(0, illDir.listFiles()?.size ?: 0)

            // Valid staging File for imageFile succeeds
            val stagedImg = File(storageRoot, "staging/ill_stage.png").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            val stagedRes = bus.dispatch(
                AgentTaskRole.ILLUSTRATION_COMPILER,
                ToolRequest(
                    ToolId.ATTACH_PAGE_ILLUSTRATION,
                    mapOf("bookId" to book.id, "pageIndex" to 0, "imageFile" to stagedImg, "promptUsed" to "wc"),
                ),
            )
            assertTrue(stagedRes.isSuccess)

            // 3. RERECORD_CLAUSE_AUDIO with blank text must fail and keep clause intact
            val rerecordBlank = bus.dispatch(
                AgentTaskRole.USER_DIRECT,
                ToolRequest(ToolId.RERECORD_CLAUSE_AUDIO, mapOf("bookId" to book.id, "clauseId" to clauseId, "text" to "   ")),
            )
            assertTrue(rerecordBlank is ToolResult.Error)
            val afterRerecord = repo.getBook(book.id)!!
            assertEquals(1, afterRerecord.clauses.size)
            assertEquals("小恐龙坐在火山旁。", afterRerecord.clauses.first().text)

            // 4. REPLACE_SPAN with empty or punctuation-only replacement must return Error without mutating or snapshotting
            val snapBeforeSpan = repo.timeMachine.listSnapshots(book.id).size
            for (emptyRep in listOf("", "   ", "。", "，！？")) {
                val repRes = bus.dispatch(
                    AgentTaskRole.VOICE_REVISION,
                    ToolRequest(
                        ToolId.REPLACE_SPAN,
                        mapOf(
                            "bookId" to book.id,
                            "clauseId" to clauseId,
                            "startIndex" to 0,
                            "endIndex" to 3,
                            "replacement" to emptyRep,
                        ),
                    ),
                )
                assertTrue("Expected ToolResult.Error for replacement='$emptyRep'", repRes is ToolResult.Error)
            }
            assertEquals("小恐龙坐在火山旁。", repo.getBook(book.id)!!.clauses.first().text)
            assertEquals(snapBeforeSpan, repo.timeMachine.listSnapshots(book.id).size)
            assertTrue(snapCountBefore >= 1)
        } finally {
            root.deleteRecursively()
        }
    }
}
