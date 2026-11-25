package com.luna.storynote.agent

import com.luna.storynote.audio.CommittedUtterance
import com.luna.storynote.model.Clause
import com.luna.storynote.model.MicIntent
import com.luna.storynote.model.StudioUiState
import com.luna.storynote.model.UiMotionEvent
import com.luna.storynote.storage.StoryBookRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class CommittedUtteranceHandler(
    private val repo: StoryBookRepository,
    private val toolBus: ToolBus,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<StudioUiState>,
    private val refreshBookState: (String, (StudioUiState) -> StudioUiState) -> Unit,
    private val onSpeechLanded: (bookId: String, clauseIds: List<String>) -> Unit = { _, _ -> },
    private val onVoiceRevision: (bookId: String, clauseId: String, instruction: String) -> Unit = { _, _, _ -> },
) {
    fun handle(bookId: String, intent: MicIntent, committed: CommittedUtterance) {
        scope.launch(Dispatchers.IO) {
            when (intent) {
                is MicIntent.WriteAtCursor -> handleWriteAtCursor(bookId, committed)
                is MicIntent.ReplaceClause -> {
                    val res = dispatch(
                        ToolId.RERECORD_CLAUSE_AUDIO,
                        mapOf(
                            "bookId" to bookId, "clauseId" to intent.clauseId, "text" to committed.text,
                            "takeId" to committed.takeId, "takeFile" to committed.takeFile,
                            "uttStartMs" to committed.uttStartMs, "uttEndMs" to committed.uttEndMs,
                            "takeDurationMs" to committed.takeDurationMs,
                        ),
                    )
                    if (res != null) {
                        refreshBookState(bookId) { it.copy(selectedClauseId = null) }
                        onSpeechLanded(bookId, listOf(intent.clauseId))
                    }
                }
                is MicIntent.ReplaceWord -> {
                    val res = dispatch(
                        ToolId.REPLACE_SPAN,
                        mapOf(
                            "bookId" to bookId, "clauseId" to intent.clauseId,
                            "startIndex" to intent.startIdx, "endIndex" to intent.endIdx,
                            "replacement" to committed.text,
                        ),
                    )
                    if (res != null) {
                        refreshBookState(bookId) { it.copy(inspectedWord = null) }
                        onSpeechLanded(bookId, listOf(intent.clauseId))
                    }
                }
                is MicIntent.SpeakBookTitle -> {
                    repo.updateBookTitleAndCover(bookId, newTitle = committed.text, titleTempWav = committed.takeFile)
                    uiState.update { it.copy(books = repo.listBooks()) }
                }
                is MicIntent.VoiceRevision -> {
                    runCatching { committed.takeFile.delete() }
                    val instruction = committed.text.trim()
                    if (instruction.isNotEmpty()) {
                        onVoiceRevision(bookId, intent.clauseId, instruction)
                    }
                }
            }
        }
    }

    private suspend fun handleWriteAtCursor(bookId: String, committed: CommittedUtterance) {
        val prevPageCount = repo.getBook(bookId)?.paginate()?.size ?: 1
        val afterId = uiState.value.cursorAfterClauseId
        val tool = if (afterId == null) ToolId.APPEND_CLAUSE else ToolId.INSERT_CLAUSE_AFTER
        val res = dispatch(
            tool,
            mapOf(
                "bookId" to bookId, "afterClauseId" to afterId, "text" to committed.text,
                "takeId" to committed.takeId, "takeFile" to committed.takeFile,
                "uttStartMs" to committed.uttStartMs, "uttEndMs" to committed.uttEndMs,
                "takeDurationMs" to committed.takeDurationMs,
            ),
        ) ?: return
        val updatedBook = res.book ?: return
        @Suppress("UNCHECKED_CAST")
        val insertedClauses = (res.data["insertedClauses"] as? List<Clause>).orEmpty()
        val tailClause = insertedClauses.lastOrNull()
        val clipPage = updatedBook.pageIndexOfClause(tailClause?.id)
        val openedNewPage = updatedBook.paginate().size > prevPageCount
        refreshBookState(bookId) {
            it.copy(
                cursorAfterClauseId = tailClause?.id ?: it.cursorAfterClauseId,
                currentPageIndex = clipPage,
                newlyStampedPage = if (openedNewPage) clipPage else it.newlyStampedPage,
                lastMotionEvent = tailClause?.let { c -> UiMotionEvent.ClauseLanded(c.id) } ?: it.lastMotionEvent,
            )
        }
        if (insertedClauses.isNotEmpty()) {
            onSpeechLanded(bookId, insertedClauses.map { it.id })
        }
        if (openedNewPage) {
            delay(1600L)
            uiState.update { it.copy(newlyStampedPage = null) }
        }
    }

    private fun dispatch(toolId: ToolId, params: Map<String, Any?>): ToolResult.Success? =
        toolBus.dispatch(AgentTaskRole.USER_DIRECT, ToolRequest(toolId, params)) as? ToolResult.Success
}
