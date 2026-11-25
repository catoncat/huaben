package com.luna.storynote

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
    private val ensureMicPermission: ((onGranted: () -> Unit) -> Unit)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val httpClient = DoubaoAuthRepository.defaultHttpClient()
    private val authRepo = DoubaoAuthRepository(env.authCacheFile, httpClient)
    val repo = StoryBookRepository(env.storageRoot)

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    private var pcmMic: PlatformPcmMic? = null
    private var streamRecorder: UtteranceStreamRecorder? = null
    private var micStartedAtMs = 0L

    private var audioPlayer: PlatformAudioPlayer? = null

    init {
        scope.launch(Dispatchers.IO) {
            HanziStrokeRepository.ensureLoaded { env.openStrokeAssetStream() }
            val books = repo.listBooks()
            val words = repo.listCollectedWords()
            _uiState.update { it.copy(books = books, collectedWords = words) }
            try {
                authRepo.resolve(forceRefresh = false)
            } catch (_: Exception) {
            }
        }
    }

    private fun postNotice(msg: String) {
        _uiState.update { it.copy(subtleNotice = msg) }
        scope.launch {
            delay(2800L)
            _uiState.update { st -> if (st.subtleNotice == msg) st.copy(subtleNotice = null) else st }
        }
    }

    fun openBook(bookId: String) {
        stopAllAudioAndMic()
        val book = _uiState.value.books.firstOrNull { it.id == bookId } ?: return
        val lastSentenceId = book.sentences.lastOrNull()?.id
        _uiState.update {
            it.copy(
                destination = StudioDestination.BOOK,
                activeBookId = bookId,
                currentPageIndex = 0,
                cursorAfterSentenceId = lastSentenceId,
                selectedSentenceId = null,
                inspectedWord = null,
            )
        }
    }

    fun createAndOpenNewBook() {
        stopAllAudioAndMic()
        scope.launch(Dispatchers.IO) {
            val created = repo.createBlankBook()
            val all = repo.listBooks()
            _uiState.update {
                it.copy(
                    books = all,
                    destination = StudioDestination.BOOK,
                    activeBookId = created.id,
                    currentPageIndex = 0,
                    cursorAfterSentenceId = null,
                    selectedSentenceId = null,
                    inspectedWord = null,
                )
            }
        }
    }

    fun returnToShelf() {
        stopAllAudioAndMic()
        _uiState.update {
            it.copy(
                destination = StudioDestination.SHELF,
                activeBookId = null,
                selectedSentenceId = null,
                inspectedWord = null,
            )
        }
    }

    fun openWordBox() {
        stopAllAudioAndMic()
        _uiState.update {
            it.copy(destination = StudioDestination.WORD_BOX, inspectedWord = null)
        }
    }

    fun setCurrentPage(pageIndex: Int) {
        val book = _uiState.value.activeBook ?: return
        val maxPage = (book.paginate().size - 1).coerceAtLeast(0)
        _uiState.update { it.copy(currentPageIndex = pageIndex.coerceIn(0, maxPage)) }
    }

    fun cycleCoverStyle(bookId: String) {
        val book = _uiState.value.books.firstOrNull { it.id == bookId } ?: return
        scope.launch(Dispatchers.IO) {
            repo.updateBookTitleAndCover(
                bookId = bookId,
                coverColorIndex = (book.coverColorIndex + 1) % 6,
                emblemIndex = (book.emblemIndex + 1) % 6,
            )
            _uiState.update { it.copy(books = repo.listBooks()) }
        }
    }

    fun playBookTitleVoice(book: StoryBook) {
        val titleWav = book.titleAudioFile()
        if (titleWav != null) {
            stopAudioPlayback()
            val p = env.createAudioPlayer()
            audioPlayer = p
            p.play(titleWav) { scope.launch { stopAudioPlayback() } }
        } else {
            env.speakStandardWord(book.title)
        }
    }

    fun moveCursorAfter(sentenceId: String?) {
        _uiState.update {
            it.copy(
                cursorAfterSentenceId = sentenceId,
                selectedSentenceId = null,
                inspectedWord = null,
            )
        }
    }

    // --- Sentence Audio & In-Place Editing ---

    fun onSentenceDotTapped(sentence: SentenceClip) {
        val st = _uiState.value
        val book = st.activeBook ?: return
        if (st.isRecording) stopRecording()

        val alreadySelected = st.selectedSentenceId == sentence.id
        if (alreadySelected && st.playingSentenceId == sentence.id) {
            stopAudioPlayback()
            _uiState.update { it.copy(selectedSentenceId = null) }
            return
        }

        _uiState.update {
            it.copy(
                selectedSentenceId = sentence.id,
                cursorAfterSentenceId = sentence.id,
                inspectedWord = null,
            )
        }
        playSingleSentenceClip(book, sentence)
    }

    fun dismissSelectionAndPopovers() {
        if (_uiState.value.playingSentenceId != null && !_uiState.value.isFullBookPlaying) {
            stopAudioPlayback()
        }
        _uiState.update { it.copy(selectedSentenceId = null, inspectedWord = null) }
    }

    private fun playSingleSentenceClip(book: StoryBook, sentence: SentenceClip) {
        stopAudioPlayback()
        val wav = book.audioFileFor(sentence)
        if (wav == null) {
            env.speakStandardWord(sentence.text)
            return
        }
        try {
            val player = env.createAudioPlayer()
            audioPlayer = player
            _uiState.update { it.copy(playingSentenceId = sentence.id, isFullBookPlaying = false) }
            player.play(wav) {
                scope.launch {
                    if (_uiState.value.playingSentenceId == sentence.id && !_uiState.value.isFullBookPlaying) {
                        stopAudioPlayback()
                    }
                }
            }
        } catch (_: Exception) {
            stopAudioPlayback()
        }
    }

    fun toggleFullBookReadAlong() {
        val st = _uiState.value
        if (st.isFullBookPlaying) {
            stopAudioPlayback()
            return
        }
        val book = st.activeBook ?: return
        if (book.sentences.isEmpty()) return
        if (st.isRecording) stopRecording()

        playBookFromSentenceIndex(book.id, 0)
    }

    private fun playBookFromSentenceIndex(bookId: String, index: Int) {
        val book = _uiState.value.books.firstOrNull { it.id == bookId } ?: return
        if (index !in book.sentences.indices) {
            stopAudioPlayback()
            return
        }
        val sentence = book.sentences[index]
        val targetPage = book.pageIndexOfSentence(sentence.id)

        stopAudioPlayback()
        _uiState.update {
            it.copy(
                currentPageIndex = targetPage,
                playingSentenceId = sentence.id,
                isFullBookPlaying = true,
                selectedSentenceId = null,
                inspectedWord = null,
            )
        }

        val wav = book.audioFileFor(sentence)
        if (wav != null) {
            val player = env.createAudioPlayer()
            audioPlayer = player
            player.play(wav) {
                scope.launch {
                    if (_uiState.value.isFullBookPlaying) {
                        delay(220L)
                        playBookFromSentenceIndex(bookId, index + 1)
                    }
                }
            }
        } else {
            env.speakStandardWord(sentence.text)
            scope.launch {
                delay((sentence.text.length * 260L).coerceIn(1200L, 4500L))
                if (_uiState.value.isFullBookPlaying) {
                    playBookFromSentenceIndex(bookId, index + 1)
                }
            }
        }
    }

    fun playCollectedWordExample(card: CollectedWordCard) {
        stopAudioPlayback()
        val f = File(card.sentenceAudioPath)
        if (f.isFile) {
            val player = env.createAudioPlayer()
            audioPlayer = player
            player.play(f) { scope.launch { stopAudioPlayback() } }
        } else {
            env.speakStandardWord(card.word)
        }
    }

    fun stopAudioPlayback() {
        audioPlayer?.stop()
        audioPlayer = null
        _uiState.update { it.copy(playingSentenceId = null, isFullBookPlaying = false) }
    }

    // --- Hands-Free Page Recording & In-Place Voice Editing ---

    fun togglePrimaryMicButton() {
        if (_uiState.value.isRecording) {
            if (System.currentTimeMillis() - micStartedAtMs < 650L) return
            stopRecording()
        } else {
            startMicWithIntent(MicIntent.WriteAtCursor)
        }
    }

    fun startRespeakSentence(sentenceId: String) {
        startMicWithIntent(MicIntent.ReplaceSentence(sentenceId))
    }

    fun startInsertAfterSentence(sentenceId: String) {
        _uiState.update { it.copy(cursorAfterSentenceId = sentenceId, selectedSentenceId = null) }
        startMicWithIntent(MicIntent.WriteAtCursor)
    }

    fun startRespeakWord(sentenceId: String, span: WordSpan) {
        startMicWithIntent(MicIntent.ReplaceWord(sentenceId, span.startCharIndex, span.endCharIndex))
    }

    fun startSpeakBookTitle() {
        startMicWithIntent(MicIntent.SpeakBookTitle)
    }

    private fun startMicWithIntent(intent: MicIntent) {
        stopAudioPlayback()
        val permGate = ensureMicPermission
        if (permGate != null) {
            permGate { launchMicInternal(intent) }
        } else {
            launchMicInternal(intent)
        }
    }

    private fun launchMicInternal(intent: MicIntent) {
        if (_uiState.value.isRecording) stopRecording()
        val bookId = _uiState.value.activeBookId ?: return

        val singleShot = intent !is MicIntent.WriteAtCursor
        val mic = env.createPcmMic()
        val stream = UtteranceStreamRecorder(
            authRepo = authRepo,
            httpClient = httpClient,
            newTempWavFile = { repo.newStagingWav() },
            singleUtteranceMode = singleShot,
            onLiveDraft = { draft, level ->
                _uiState.update { st ->
                    if (!st.isRecording) st else st.copy(
                        liveDraftText = draft,
                        voiceEnergy = if (level > 0f) level else st.voiceEnergy * 0.85f,
                    )
                }
            },
            onUtteranceCommitted = { text, wavFile, durSec ->
                handleCommittedUtterance(bookId, intent, text, wavFile, durSec)
            },
            onAutoFinishedSingle = {
                scope.launch { finishSingleShotCleanup() }
            },
            onError = { err ->
                scope.launch { postNotice(err) }
            },
        )

        try {
            mic.start { bytes, len -> stream.pushMicPcm(bytes, len) }
            env.setKeepScreenAwake(true)
            pcmMic = mic
            streamRecorder = stream
            micStartedAtMs = System.currentTimeMillis()
            _uiState.update {
                it.copy(
                    activeMicIntent = intent,
                    liveDraftText = "",
                    voiceEnergy = 0f,
                    inspectedWord = if (intent is MicIntent.ReplaceWord) it.inspectedWord else null,
                )
            }
        } catch (e: Exception) {
            stream.cancel()
            env.setKeepScreenAwake(false)
            postNotice(e.message ?: "无法开启麦克风")
        }
    }

    private fun handleCommittedUtterance(
        bookId: String,
        intent: MicIntent,
        text: String,
        wavFile: File,
        durSec: Double,
    ) {
        scope.launch(Dispatchers.IO) {
            when (intent) {
                is MicIntent.WriteAtCursor -> {
                    val prevPageCount = repo.getBook(bookId)?.paginate()?.size ?: 1
                    val afterId = _uiState.value.cursorAfterSentenceId
                    val (updatedBook, newClip) = repo.insertSentenceClip(
                        bookId = bookId,
                        afterSentenceId = afterId,
                        rawText = text,
                        tempWavFile = wavFile,
                        durationSec = durSec,
                    )
                    val newPages = updatedBook.paginate()
                    val clipPage = updatedBook.pageIndexOfSentence(newClip.id)
                    val openedNewPage = newPages.size > prevPageCount
                    val allBooks = repo.listBooks()

                    _uiState.update {
                        it.copy(
                            books = allBooks,
                            cursorAfterSentenceId = newClip.id,
                            currentPageIndex = clipPage,
                            newlyStampedPage = if (openedNewPage) clipPage else it.newlyStampedPage,
                        )
                    }
                    if (openedNewPage) {
                        delay(1600L)
                        _uiState.update { it.copy(newlyStampedPage = null) }
                    }
                }
                is MicIntent.ReplaceSentence -> {
                    repo.replaceSentenceClip(bookId, intent.sentenceId, text, wavFile, durSec)
                    val allBooks = repo.listBooks()
                    _uiState.update {
                        it.copy(books = allBooks, selectedSentenceId = null)
                    }
                }
                is MicIntent.ReplaceWord -> {
                    wavFile.delete()
                    repo.replaceRangeInSentence(bookId, intent.sentenceId, intent.startIdx, intent.endIdx, text)
                    val allBooks = repo.listBooks()
                    _uiState.update {
                        it.copy(books = allBooks, inspectedWord = null)
                    }
                }
                is MicIntent.SpeakBookTitle -> {
                    repo.updateBookTitleAndCover(bookId = bookId, newTitle = text, titleTempWav = wavFile)
                    val allBooks = repo.listBooks()
                    _uiState.update { it.copy(books = allBooks) }
                }
            }
        }
    }

    private fun finishSingleShotCleanup() {
        pcmMic?.stop()
        pcmMic = null
        streamRecorder = null
        env.setKeepScreenAwake(false)
        _uiState.update { it.copy(activeMicIntent = null, liveDraftText = "", voiceEnergy = 0f) }
    }

    fun stopRecording() {
        val mic = pcmMic
        val rec = streamRecorder
        pcmMic = null
        streamRecorder = null
        env.setKeepScreenAwake(false)

        _uiState.update { it.copy(activeMicIntent = null, liveDraftText = "", voiceEnergy = 0f) }
        scope.launch(Dispatchers.IO) {
            mic?.stop()
            rec?.finishGracefully(5000L)
        }
    }

    fun eraseSentence(sentenceId: String) {
        val book = _uiState.value.activeBook ?: return
        val idx = book.sentences.indexOfFirst { it.id == sentenceId }
        if (idx < 0) return
        val prevId = if (idx == 0) "__START__" else book.sentences[idx - 1].id
        stopAudioPlayback()

        scope.launch(Dispatchers.IO) {
            val res = repo.removeSentence(book.id, sentenceId) ?: return@launch
            val (updated, removedClip) = res
            val maxPage = (updated.paginate().size - 1).coerceAtLeast(0)
            val undo = removedClip?.let { UndoDeletedSentence(book.id, prevId, it) }
            _uiState.update {
                it.copy(
                    books = repo.listBooks(),
                    currentPageIndex = it.currentPageIndex.coerceAtMost(maxPage),
                    selectedSentenceId = null,
                    cursorAfterSentenceId = if (prevId == "__START__") null else prevId,
                    undoBanner = undo,
                )
            }
            if (undo != null) {
                delay(4500L)
                _uiState.update { st -> if (st.undoBanner === undo) st.copy(undoBanner = null) else st }
            }
        }
    }

    fun undoEraseSentence() {
        val undo = _uiState.value.undoBanner ?: return
        _uiState.update { it.copy(undoBanner = null) }
        scope.launch(Dispatchers.IO) {
            val folder = File(File(env.storageRoot, "books"), undo.bookId)
            val existingWav = if (undo.clip.audioFileName.isNotBlank()) File(folder, undo.clip.audioFileName) else null
            repo.insertSentenceClip(
                bookId = undo.bookId,
                afterSentenceId = undo.afterSentenceId,
                rawText = undo.clip.text,
                tempWavFile = existingWav,
                durationSec = undo.clip.durationSec,
                forceParagraphBreak = undo.clip.paragraphBreakAfter,
            )
            _uiState.update { it.copy(books = repo.listBooks()) }
        }
    }

    // --- Word Inspection (`田字格字卡`) & Word Box ---

    fun onCharacterTapped(sentence: SentenceClip, charIndexInSentence: Int) {
        if (_uiState.value.isRecording) return
        val span = LiteracyEngine.findWordAt(sentence.text, charIndexInSentence) ?: return
        val homophones = LiteracyEngine.suggestHomophones(span.word, span.pinyins)
        env.speakStandardWord(span.word)
        _uiState.update {
            it.copy(
                selectedSentenceId = null,
                inspectedWord = ActiveWordInspection(
                    sentenceId = sentence.id,
                    span = span,
                    homophones = homophones,
                ),
            )
        }
    }

    fun applyHomophoneReplacement(inspection: ActiveWordInspection, candidate: String) {
        val bookId = _uiState.value.activeBookId ?: return
        scope.launch(Dispatchers.IO) {
            repo.replaceRangeInSentence(
                bookId = bookId,
                sentenceId = inspection.sentenceId,
                startIdx = inspection.span.startCharIndex,
                endIdx = inspection.span.endCharIndex,
                replacementWord = candidate,
            )
            _uiState.update {
                it.copy(books = repo.listBooks(), inspectedWord = null)
            }
        }
    }

    fun applySingleCharHomophoneReplacement(
        inspection: ActiveWordInspection,
        charOffsetInWord: Int,
        candidateChar: String,
    ) {
        val bookId = _uiState.value.activeBookId ?: return
        val charStart = inspection.span.startCharIndex + charOffsetInWord
        if (charStart !in inspection.span.startCharIndex until inspection.span.endCharIndex) return
        scope.launch(Dispatchers.IO) {
            repo.replaceRangeInSentence(
                bookId = bookId,
                sentenceId = inspection.sentenceId,
                startIdx = charStart,
                endIdx = charStart + 1,
                replacementWord = candidateChar,
            )
            _uiState.update {
                it.copy(books = repo.listBooks(), inspectedWord = null)
            }
        }
    }

    fun toggleCollectWord(inspection: ActiveWordInspection) {
        val book = _uiState.value.activeBook ?: return
        val sentence = book.sentences.firstOrNull { it.id == inspection.sentenceId } ?: return
        scope.launch(Dispatchers.IO) {
            val (updatedWords, _) = repo.toggleCollectWord(
                word = inspection.span.word,
                pinyins = inspection.span.pinyins,
                book = book,
                sentence = sentence,
            )
            _uiState.update { it.copy(collectedWords = updatedWords) }
        }
    }

    fun deleteBook(bookId: String) {
        stopAllAudioAndMic()
        scope.launch(Dispatchers.IO) {
            repo.deleteBook(bookId)
            _uiState.update {
                it.copy(
                    books = repo.listBooks(),
                    destination = StudioDestination.SHELF,
                    activeBookId = null,
                )
            }
        }
    }

    private fun stopAllAudioAndMic() {
        stopAudioPlayback()
        if (_uiState.value.isRecording) stopRecording()
    }

    fun dispose() {
        stopAllAudioAndMic()
        scope.cancel()
    }
}
