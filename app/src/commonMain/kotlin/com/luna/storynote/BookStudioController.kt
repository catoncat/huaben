package com.luna.storynote

import com.luna.storynote.agent.AgentTaskRole
import com.luna.storynote.agent.CommittedUtteranceHandler
import com.luna.storynote.agent.ToolBus
import com.luna.storynote.agent.ToolId
import com.luna.storynote.agent.ToolRequest
import com.luna.storynote.agent.ToolResult
import com.luna.storynote.ai.AiConnectionTestResult
import com.luna.storynote.ai.AiGatewayClient
import com.luna.storynote.ai.AiSettingsCoordinator
import com.luna.storynote.ai.BookAiCoordinator
import com.luna.storynote.audio.AudioPlaybackCoordinator
import com.luna.storynote.audio.DoubaoAuthRepository
import com.luna.storynote.audio.MicRecordingCoordinator
import com.luna.storynote.literacy.HanziStrokeRepository
import com.luna.storynote.literacy.JiebaWordSegmenter
import com.luna.storynote.literacy.LiteracyEngine
import com.luna.storynote.literacy.WordSpan
import com.luna.storynote.model.ActiveWordInspection
import com.luna.storynote.model.AiGatewayConfig
import com.luna.storynote.model.Clause
import com.luna.storynote.model.CollectedWordCard
import com.luna.storynote.model.MicIntent
import com.luna.storynote.model.StoryBook
import com.luna.storynote.model.StudioDestination
import com.luna.storynote.model.StudioUiState
import com.luna.storynote.model.UiMotionEvent
import com.luna.storynote.model.UndoDeletedClause
import com.luna.storynote.storage.AiConfigStore
import com.luna.storynote.storage.BookExportMode
import com.luna.storynote.storage.ClauseHistoryEntry
import com.luna.storynote.storage.StoryBookRepository
import com.luna.storynote.update.AppUpdateConfig
import com.luna.storynote.update.AppUpdateCoordinator
import com.luna.storynote.update.AppUpdateManager
import com.luna.storynote.update.AvailableAppUpdate
import com.luna.storynote.update.UpdateConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class BookStudioController(
    val env: PlatformEnvironment,
    ensureMicPermission: ((onGranted: () -> Unit) -> Unit)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val httpClient = DoubaoAuthRepository.defaultHttpClient()
    private val authRepo = DoubaoAuthRepository(env.authCacheFile, httpClient)
    val repo = StoryBookRepository(env.storageRoot)
    val toolBus = ToolBus(repo) { bookId, _ -> refreshBookState(bookId) }
    val aiConfigStore = AiConfigStore(env.privateConfigDir)
    val aiGatewayClient = AiGatewayClient()

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()
    val isAiConfigured: Boolean get() = _uiState.value.aiConfig.isConfigured

    private val aiSettings = AiSettingsCoordinator(env, aiConfigStore, aiGatewayClient, scope, _uiState)
    private val aiCoordinator = BookAiCoordinator(env, toolBus, aiGatewayClient, scope, _uiState, ::refreshBookState, ::postNotice)
    private val updateCoordinator = AppUpdateCoordinator(
        env = env,
        configStore = UpdateConfigStore(env.privateConfigDir),
        updateManager = AppUpdateManager(),
        scope = scope,
        uiState = _uiState,
        postNotice = ::postNotice,
    )
    private val utteranceHandler = CommittedUtteranceHandler(
        repo = repo, toolBus = toolBus, scope = scope, uiState = _uiState, refreshBookState = ::refreshBookState,
        onSpeechLanded = { bId, cIds -> aiCoordinator.scheduleSilentCopyEdit(bId, cIds) },
        onVoiceRevision = { bId, cId, instr -> aiCoordinator.submitVoiceRevision(bId, cId, instr) },
    )

    private val audioCoordinator = AudioPlaybackCoordinator(
        env = env, scope = scope,
        onStateChanged = { clauseId, fullBook, targetPage ->
            _uiState.update { st ->
                st.copy(
                    playingClauseId = clauseId, isFullBookPlaying = fullBook,
                    currentPageIndex = targetPage ?: st.currentPageIndex,
                    selectedClauseId = if (fullBook) null else st.selectedClauseId,
                    inspectedWord = if (fullBook) null else st.inspectedWord,
                )
            }
        },
        getBookById = { id -> _uiState.value.books.firstOrNull { it.id == id } },
        isStillFullBookPlaying = { _uiState.value.isFullBookPlaying },
        getPlayingClauseId = { _uiState.value.playingClauseId },
    )

    private val micCoordinator = MicRecordingCoordinator(
        env = env, authRepo = authRepo, httpClient = httpClient, repo = repo, scope = scope,
        ensureMicPermission = ensureMicPermission,
        onStateStarted = { intent ->
            _uiState.update {
                it.copy(
                    activeMicIntent = intent, liveDraftText = "", voiceEnergy = 0f,
                    inspectedWord = if (intent is MicIntent.ReplaceWord) it.inspectedWord else null,
                )
            }
        },
        onLiveDraft = { draft, level ->
            _uiState.update { st ->
                if (!st.isRecording) st else st.copy(liveDraftText = draft, voiceEnergy = if (level > 0f) level else st.voiceEnergy * 0.85f)
            }
        },
        onUtteranceCommitted = { bookId, intent, committed -> utteranceHandler.handle(bookId, intent, committed) },
        onRecordingCleared = { _uiState.update { it.copy(activeMicIntent = null, liveDraftText = "", voiceEnergy = 0f) } },
        onError = { err -> postNotice(err) },
    )

    init {
        JiebaWordSegmenter.warmUpAsync(scope)
        scope.launch(Dispatchers.IO) {
            HanziStrokeRepository.ensureLoaded { env.openStrokeAssetStream() }
            val books = repo.listBooks()
            val words = repo.listCollectedWords()
            val aiCfg = aiConfigStore.loadOrDefault(env)
            _uiState.update { it.copy(books = books, collectedWords = words, aiConfig = aiCfg) }
            runCatching { authRepo.resolve(forceRefresh = false) }
        }
        updateCoordinator.initAndMaybeAutoCheck()
    }

    private fun postNotice(msg: String) {
        _uiState.update { it.copy(subtleNotice = msg) }
        scope.launch {
            delay(2800L)
            _uiState.update { st -> if (st.subtleNotice == msg) st.copy(subtleNotice = null) else st }
        }
    }

    private fun refreshBookState(bookId: String, extra: (StudioUiState) -> StudioUiState = { it }) {
        val books = repo.listBooks()
        val snapshots = repo.timeMachine.listSnapshots(bookId)
        val bible = repo.bookBibles.read(bookId)
        _uiState.update { st ->
            val base = if (st.activeBookId == bookId) st.copy(books = books, timeMachineSnapshots = snapshots, bookBible = bible) else st.copy(books = books)
            extra(base)
        }
    }

    private fun dispatchUserTool(toolId: ToolId, vararg params: Pair<String, Any?>): ToolResult.Success? =
        toolBus.dispatch(AgentTaskRole.USER_DIRECT, ToolRequest(toolId, params.toMap())) as? ToolResult.Success

    private fun StudioUiState.withResetOverlayState(
        dest: StudioDestination,
        bookId: String?,
        cursorId: String? = null,
    ): StudioUiState = copy(
        destination = dest, activeBookId = bookId, currentPageIndex = 0, cursorAfterClauseId = cursorId,
        selectedClauseId = null, inspectedWord = null, isTimeMachineOpen = false, timeMachineTargetClauseId = null,
        isFullTextEditorOpen = false,
        illustratingPageIndex = null, isVoiceRevising = false, isVoiceSparkSpeaking = false, activeSparkQuestion = null,
    )

    fun openBook(bookId: String) {
        stopAllAudioAndMic()
        _uiState.value.activeBookId?.takeIf { it != bookId }?.let { aiCoordinator.cancelTransientOnSwitch(it) }
        val book = _uiState.value.books.firstOrNull { it.id == bookId } ?: return
        _uiState.update {
            it.withResetOverlayState(StudioDestination.BOOK, bookId, book.clauses.lastOrNull()?.id)
                .copy(bookBible = repo.bookBibles.read(bookId), timeMachineSnapshots = repo.timeMachine.listSnapshots(bookId))
        }
    }

    fun createAndOpenNewBook() {
        stopAllAudioAndMic()
        _uiState.value.activeBookId?.let { aiCoordinator.cancelTransientOnSwitch(it) }
        scope.launch(Dispatchers.IO) {
            val created = repo.createBlankBook()
            refreshBookState(created.id) {
                it.withResetOverlayState(StudioDestination.BOOK, created.id)
                    .copy(bookBible = repo.bookBibles.read(created.id), timeMachineSnapshots = emptyList())
            }
        }
    }

    fun returnToShelf() {
        stopAllAudioAndMic()
        _uiState.value.activeBookId?.let { aiCoordinator.cancelTransientOnSwitch(it) }
        _uiState.update { it.withResetOverlayState(StudioDestination.SHELF, null) }
    }

    fun openWordBox() {
        stopAllAudioAndMic()
        _uiState.update { it.copy(destination = StudioDestination.WORD_BOX, inspectedWord = null, isTimeMachineOpen = false, timeMachineTargetClauseId = null, isFullTextEditorOpen = false) }
    }

    fun setCurrentPage(pageIndex: Int) {
        val book = _uiState.value.activeBook ?: return
        val maxPage = (book.paginate().size - 1).coerceAtLeast(0)
        _uiState.update {
            it.copy(currentPageIndex = pageIndex.coerceIn(0, maxPage), selectedClauseId = null, inspectedWord = null, activeSparkQuestion = null)
        }
    }

    fun cycleCoverStyle(bookId: String) {
        val book = _uiState.value.books.firstOrNull { it.id == bookId } ?: return
        scope.launch(Dispatchers.IO) {
            repo.updateBookTitleAndCover(bookId, coverColorIndex = (book.coverColorIndex + 1) % 6, emblemIndex = (book.emblemIndex + 1) % 6)
            _uiState.update { it.copy(books = repo.listBooks()) }
        }
    }

    fun playBookTitleVoice(book: StoryBook) = audioCoordinator.playBookTitleVoice(book)

    fun moveCursorAfter(clauseId: String?) {
        _uiState.update { it.copy(cursorAfterClauseId = clauseId, selectedClauseId = null, inspectedWord = null) }
    }

    fun onClauseDotTapped(clause: Clause) {
        val st = _uiState.value
        val book = st.activeBook ?: return
        if (st.isRecording) stopRecording()
        if (st.selectedClauseId == clause.id && st.playingClauseId == clause.id) {
            stopAudioPlayback()
            _uiState.update { it.copy(selectedClauseId = null, activeSparkQuestion = null) }
            return
        }
        _uiState.update { it.copy(selectedClauseId = clause.id, cursorAfterClauseId = clause.id, inspectedWord = null, activeSparkQuestion = null) }
        audioCoordinator.playSingleClause(book, clause)
    }

    fun playClauseById(clauseId: String) {
        val book = _uiState.value.activeBook ?: return
        val clause = book.clauses.firstOrNull { it.id == clauseId } ?: return
        if (_uiState.value.isRecording) stopRecording()
        audioCoordinator.playSingleClause(book, clause)
    }

    fun dismissSelectionAndPopovers() {
        if (_uiState.value.playingClauseId != null && !_uiState.value.isFullBookPlaying) stopAudioPlayback()
        _uiState.update { it.copy(selectedClauseId = null, inspectedWord = null, activeSparkQuestion = null) }
    }

    fun toggleFullBookReadAlong() {
        val st = _uiState.value
        if (st.isFullBookPlaying) { stopAudioPlayback(); return }
        val book = st.activeBook ?: return
        if (book.clauses.isEmpty()) return
        if (st.isRecording) stopRecording()
        audioCoordinator.playBookFromClauseIndex(book.id, 0)
    }

    fun playCollectedWordExample(card: CollectedWordCard) = audioCoordinator.playCollectedWordExample(card)

    fun playClauseHistoryEntry(entry: ClauseHistoryEntry) {
        val wav = entry.wavFile ?: return
        audioCoordinator.playWavRange(wav, entry.audioSpan?.startMs ?: 0L, entry.audioSpan?.endMs ?: -1L)
    }

    fun stopAudioPlayback() = audioCoordinator.stop()

    fun togglePrimaryMicButton() {
        if (_uiState.value.isRecording) {
            if (!micCoordinator.canDebounceStop) stopRecording()
        } else {
            startMicWithIntent(MicIntent.WriteAtCursor)
        }
    }

    fun startRespeakClause(clauseId: String) = startMicWithIntent(MicIntent.ReplaceClause(clauseId))

    fun startInsertAfterClause(clauseId: String) {
        _uiState.update { it.copy(cursorAfterClauseId = clauseId, selectedClauseId = null) }
        startMicWithIntent(MicIntent.WriteAtCursor)
    }

    fun startRespeakWord(clauseId: String, span: WordSpan) =
        startMicWithIntent(MicIntent.ReplaceWord(clauseId, span.startCharIndex, span.endCharIndex))

    fun startVoiceRevision(clauseId: String) {
        if (!isAiConfigured) return
        _uiState.update { it.copy(selectedClauseId = null, inspectedWord = null) }
        startMicWithIntent(MicIntent.VoiceRevision(clauseId))
    }

    fun submitVoiceRevision(clauseId: String, instruction: String) {
        val bookId = _uiState.value.activeBookId ?: return
        aiCoordinator.submitVoiceRevision(bookId, clauseId, instruction)
    }

    fun illustrateCurrentPage() = aiCoordinator.illustrateCurrentPage()

    fun triggerVoiceSpark() = aiCoordinator.triggerVoiceSpark()

    fun startSpeakBookTitle() = startMicWithIntent(MicIntent.SpeakBookTitle)

    private fun startMicWithIntent(intent: MicIntent) {
        stopAudioPlayback()
        _uiState.update { it.copy(activeSparkQuestion = null) }
        micCoordinator.startWithIntent(_uiState.value.activeBookId, intent)
    }

    fun stopRecording() = micCoordinator.stopRecording()

    fun eraseClause(clauseId: String) {
        val book = _uiState.value.activeBook ?: return
        val idx = book.clauses.indexOfFirst { it.id == clauseId }
        if (idx < 0) return
        val prevId = if (idx == 0) "__START__" else book.clauses[idx - 1].id
        stopAudioPlayback()
        scope.launch(Dispatchers.IO) {
            val res = dispatchUserTool(ToolId.ERASE_CLAUSE, "bookId" to book.id, "clauseId" to clauseId) ?: return@launch
            val updated = res.book ?: return@launch
            val undo = (res.data["removedClause"] as? Clause)?.let { UndoDeletedClause(book.id, prevId, it) }
            val maxPage = (updated.paginate().size - 1).coerceAtLeast(0)
            refreshBookState(book.id) {
                it.copy(
                    currentPageIndex = it.currentPageIndex.coerceAtMost(maxPage), selectedClauseId = null,
                    cursorAfterClauseId = if (prevId == "__START__") null else prevId,
                    undoBanner = undo, lastMotionEvent = UiMotionEvent.ClauseErased(clauseId),
                )
            }
            if (undo != null) {
                delay(4500L)
                _uiState.update { st -> if (st.undoBanner === undo) st.copy(undoBanner = null) else st }
            }
        }
    }

    fun undoEraseClause() {
        val undo = _uiState.value.undoBanner ?: return
        _uiState.update { it.copy(undoBanner = null) }
        scope.launch(Dispatchers.IO) {
            val snap = repo.timeMachine.listSnapshots(undo.bookId).firstOrNull { s -> s.clauses.any { it.id == undo.clause.id } }
            if (snap != null) {
                dispatchUserTool(ToolId.RESTORE_HISTORY_SNAPSHOT, "bookId" to undo.bookId, "snapshotId" to snap.id, "clauseId" to undo.clause.id)
            } else {
                val folder = File(File(env.storageRoot, "books"), undo.bookId)
                val sp = undo.clause.audioSpan
                val take = sp?.let { repo.getBook(undo.bookId)?.takeById(it.takeId) }
                dispatchUserTool(
                    ToolId.INSERT_CLAUSE_AFTER, "bookId" to undo.bookId, "afterClauseId" to undo.afterClauseId,
                    "text" to undo.clause.text, "takeId" to sp?.takeId.orEmpty(),
                    "takeFile" to take?.let { File(folder, it.wavFileName) },
                    "uttStartMs" to (sp?.startMs ?: 0L), "uttEndMs" to (sp?.endMs ?: 0L),
                    "takeDurationMs" to (take?.durationMs ?: 0L), "paragraphBreakAfter" to undo.clause.paragraphBreakAfter,
                )
            }
            refreshBookState(undo.bookId)
        }
    }

    fun findClauseHistory(clauseId: String): List<ClauseHistoryEntry> =
        _uiState.value.activeBook?.let { repo.timeMachine.findClauseHistory(it, clauseId) }.orEmpty()

    fun openTimeMachine(clauseId: String? = null) {
        stopAudioPlayback()
        val bookId = _uiState.value.activeBookId ?: return
        val snaps = repo.timeMachine.listSnapshots(bookId)
        _uiState.update { it.copy(timeMachineSnapshots = snaps, isTimeMachineOpen = true, timeMachineTargetClauseId = clauseId, isFullTextEditorOpen = false, selectedClauseId = null, inspectedWord = null, activeSparkQuestion = null) }
    }

    fun closeTimeMachine() { stopAudioPlayback(); _uiState.update { it.copy(isTimeMachineOpen = false, timeMachineTargetClauseId = null) } }

    fun openFullTextEditor() {
        stopAllAudioAndMic()
        if (_uiState.value.activeBookId == null) return
        _uiState.update {
            it.copy(
                isFullTextEditorOpen = true,
                isTimeMachineOpen = false,
                timeMachineTargetClauseId = null,
                selectedClauseId = null,
                inspectedWord = null,
                activeSparkQuestion = null,
            )
        }
    }

    fun closeFullTextEditor() {
        _uiState.update { it.copy(isFullTextEditorOpen = false) }
    }

    fun saveFullTextEdit(newTitle: String, newFullText: String) {
        val bookId = _uiState.value.activeBookId ?: return
        stopAllAudioAndMic()
        scope.launch(Dispatchers.IO) {
            val res = dispatchUserTool(
                ToolId.EDIT_FULL_TEXT,
                "bookId" to bookId,
                "title" to newTitle,
                "fullText" to newFullText,
            )
            val changed = (res?.data?.get("changed") as? Boolean) == true
            refreshBookState(bookId) { st ->
                val active = st.activeBook
                val maxPage = ((active?.paginate()?.size ?: 1) - 1).coerceAtLeast(0)
                val nextCursor = st.cursorAfterClauseId?.takeIf { cid -> active?.clauses?.any { it.id == cid } == true }
                    ?: active?.clauses?.lastOrNull()?.id
                st.copy(
                    currentPageIndex = st.currentPageIndex.coerceAtMost(maxPage),
                    cursorAfterClauseId = nextCursor,
                    selectedClauseId = null,
                    inspectedWord = null,
                    activeSparkQuestion = null,
                    isFullTextEditorOpen = false,
                )
            }
            if (changed) postNotice("已保存校对")
        }
    }
    fun restoreSnapshot(snapshotId: String) = restoreHistory(snapshotId, clauseId = null)
    fun restoreClauseFromSnapshot(snapshotId: String, clauseId: String) = restoreHistory(snapshotId, clauseId)

    private fun restoreHistory(snapshotId: String, clauseId: String?) {
        val bookId = _uiState.value.activeBookId ?: return
        stopAudioPlayback()
        scope.launch(Dispatchers.IO) {
            val params = buildList { add("bookId" to bookId); add("snapshotId" to snapshotId); if (clauseId != null) add("clauseId" to clauseId) }
            dispatchUserTool(ToolId.RESTORE_HISTORY_SNAPSHOT, *params.toTypedArray())
            refreshBookState(bookId) { st ->
                val maxPage = ((st.activeBook?.paginate()?.size ?: 1) - 1).coerceAtLeast(0)
                st.copy(currentPageIndex = st.currentPageIndex.coerceAtMost(maxPage), selectedClauseId = null, inspectedWord = null, isTimeMachineOpen = false, timeMachineTargetClauseId = null)
            }
        }
    }

    fun toggleParagraphBreak(clauseId: String) {
        val bookId = _uiState.value.activeBookId ?: return
        scope.launch(Dispatchers.IO) { dispatchUserTool(ToolId.TOGGLE_PARAGRAPH_BREAK, "bookId" to bookId, "clauseId" to clauseId); refreshBookState(bookId) }
    }

    fun selectPageIllustration(pageIndex: Int, illustrationId: String) {
        val bookId = _uiState.value.activeBookId ?: return
        scope.launch(Dispatchers.IO) { dispatchUserTool(ToolId.SELECT_PAGE_ILLUSTRATION, "bookId" to bookId, "pageIndex" to pageIndex, "illustrationId" to illustrationId); refreshBookState(bookId) }
    }

    fun onCharacterTapped(clause: Clause, charIndexInClause: Int) {
        if (_uiState.value.isRecording) return
        val span = LiteracyEngine.findWordAt(clause.text, charIndexInClause, clause.contextualPhrases) ?: return
        val homophones = LiteracyEngine.suggestHomophones(span.word, span.pinyins)
        val validAnns = clause.softAnnotations.filter { it.charIndex >= 0 && it.length > 0 && !it.suggestedWord.isNullOrBlank() }
        val softAnn = validAnns.firstOrNull { charIndexInClause in it.charIndex until (it.charIndex + it.length) }
            ?: validAnns.firstOrNull { it.charIndex < span.endCharIndex && (it.charIndex + it.length) > span.startCharIndex }
        val lastIdx = (span.word.length - 1).coerceAtLeast(0)
        val inspection = ActiveWordInspection(
            clauseId = clause.id, span = span, homophones = homophones, softSuggestion = softAnn?.suggestedWord,
            initialCharIndexInWord = (charIndexInClause - span.startCharIndex).coerceIn(0, lastIdx),
            softAnnotationOffsetInWord = softAnn?.let { (it.charIndex - span.startCharIndex).coerceIn(0, lastIdx) },
            softAnnotationLength = softAnn?.length ?: 0,
        )
        env.speakStandardWord(span.word)
        _uiState.update { it.copy(selectedClauseId = null, inspectedWord = inspection, activeSparkQuestion = null) }
    }

    fun applyHomophoneReplacement(inspection: ActiveWordInspection, candidate: String) =
        replaceSpan(inspection.clauseId, inspection.span.startCharIndex, inspection.span.endCharIndex, candidate)

    fun applySingleCharHomophoneReplacement(inspection: ActiveWordInspection, charOffsetInWord: Int, candidateChar: String) {
        val charStart = inspection.span.startCharIndex + charOffsetInWord
        if (charStart in inspection.span.startCharIndex until inspection.span.endCharIndex) replaceSpan(inspection.clauseId, charStart, charStart + 1, candidateChar)
    }

    fun applyClauseSpanReplacement(clauseId: String, startIndex: Int, endIndex: Int, replacement: String) =
        replaceSpan(clauseId, startIndex, endIndex, replacement)

    private fun replaceSpan(clauseId: String, startIdx: Int, endIdx: Int, replacement: String) {
        val bookId = _uiState.value.activeBookId ?: return
        scope.launch(Dispatchers.IO) {
            dispatchUserTool(ToolId.REPLACE_SPAN, "bookId" to bookId, "clauseId" to clauseId, "startIndex" to startIdx, "endIndex" to endIdx, "replacement" to replacement)
            refreshBookState(bookId) { it.copy(inspectedWord = null) }
        }
    }

    fun toggleCollectWord(inspection: ActiveWordInspection) {
        val book = _uiState.value.activeBook ?: return
        val clause = book.clauses.firstOrNull { it.id == inspection.clauseId } ?: return
        val alreadyCollected = _uiState.value.collectedWords.any { it.word == inspection.span.word }
        scope.launch(Dispatchers.IO) {
            val (updatedWords, added) = if (!alreadyCollected) {
                val res = dispatchUserTool(ToolId.ADD_WORD_BOX_CARD, "bookId" to book.id, "clauseId" to clause.id, "word" to inspection.span.word, "pinyins" to inspection.span.pinyins)
                @Suppress("UNCHECKED_CAST")
                val words = res?.data?.get("collectedWords") as? List<CollectedWordCard> ?: repo.listCollectedWords()
                words to (res?.data?.get("added") as? Boolean ?: false)
            } else repo.toggleCollectWord(inspection.span.word, inspection.span.pinyins, book, clause)
            _uiState.update { it.copy(collectedWords = updatedWords, lastMotionEvent = if (added) UiMotionEvent.WordBookmarked(inspection.span.word) else it.lastMotionEvent) }
        }
    }

    fun updateBookBible(bookId: String, traits: List<String> = emptyList(), scenesAndProps: List<String> = emptyList(), plotSummary: String? = null) {
        scope.launch(Dispatchers.IO) {
            dispatchUserTool(ToolId.UPDATE_BOOK_BIBLE, "bookId" to bookId, "protagonistTraits" to traits, "keyScenesAndProps" to scenesAndProps, "plotSummary" to plotSummary)
            refreshBookState(bookId)
        }
    }

    fun openParentSettings() = aiSettings.open()
    fun closeParentSettings() = aiSettings.close()
    fun saveAiConfig(config: AiGatewayConfig) = aiSettings.save(config)
    fun clearAiConfig() = aiSettings.clear()
    fun resetAiConfigToDefaults() = aiSettings.resetToDefaults()
    suspend fun testAiConnection(config: AiGatewayConfig): AiConnectionTestResult = aiSettings.testConnection(config)
    fun testAiConnectivity(config: AiGatewayConfig) = aiSettings.testConnectivity(config)

    fun exportBook(book: StoryBook, mode: BookExportMode = BookExportMode.FULL_BUNDLE) {
        postNotice(
            when (mode) {
                BookExportMode.AUDIO_ONLY -> "正在合并完整录音…"
                BookExportMode.FULL_BUNDLE -> "正在打包故事录音与图文…"
                BookExportMode.TEXT_ONLY -> "正在导出故事文字…"
            },
        )
        scope.launch(Dispatchers.IO) {
            val latest = repo.getBook(book.id) ?: book
            val msg = runCatching { env.exportBook(latest, mode) }
                .getOrElse { "导出失败：${it.message ?: "请稍后重试"}" }
            if (msg.isNotBlank()) postNotice(msg)
        }
    }

    fun checkForAppUpdate(silent: Boolean = false) = updateCoordinator.checkNow(silent)
    fun saveUpdateConfig(config: AppUpdateConfig) = updateCoordinator.saveUpdateConfig(config)
    fun downloadAndInstallUpdate(update: AvailableAppUpdate) = updateCoordinator.downloadAndInstall(update)
    fun installReadyUpdateApk(apkFile: File) = updateCoordinator.triggerInstallReadyApk(apkFile)
    fun openReleasePage(url: String) = updateCoordinator.openReleasePage(url)

    fun deleteBook(bookId: String) {
        stopAllAudioAndMic(); aiCoordinator.cancelForBook(bookId)
        scope.launch(Dispatchers.IO) {
            repo.deleteBook(bookId)
            _uiState.update { st ->
                val base = if (st.activeBookId == bookId) st.withResetOverlayState(StudioDestination.SHELF, null) else st
                base.copy(books = repo.listBooks())
            }
        }
    }

    private fun stopAllAudioAndMic() { stopAudioPlayback(); if (_uiState.value.isRecording) stopRecording() }
    fun dispose() { stopAllAudioAndMic(); aiCoordinator.cancelAll(); updateCoordinator.cancelAll(); scope.cancel() }
}
