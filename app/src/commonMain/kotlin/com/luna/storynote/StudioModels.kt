package com.luna.storynote

enum class StudioDestination {
    SHELF,
    BOOK,
    WORD_BOX,
}

sealed interface MicIntent {
    data object WriteAtCursor : MicIntent
    data class ReplaceSentence(val sentenceId: String) : MicIntent
    data class ReplaceWord(val sentenceId: String, val startIdx: Int, val endIdx: Int) : MicIntent
    data object SpeakBookTitle : MicIntent
}

data class ActiveWordInspection(
    val sentenceId: String,
    val span: WordSpan,
    val homophones: List<String>,
)

data class UndoDeletedSentence(
    val bookId: String,
    val afterSentenceId: String?,
    val clip: SentenceClip,
)

data class StudioUiState(
    val destination: StudioDestination = StudioDestination.SHELF,
    val books: List<StoryBook> = emptyList(),
    val activeBookId: String? = null,
    val currentPageIndex: Int = 0,
    // Cursor sits right after `cursorAfterSentenceId` (`null` = end of book, `"__START__"` = start of book)
    val cursorAfterSentenceId: String? = null,
    // Sentence selected via its micro-acoustic dot
    val selectedSentenceId: String? = null,
    // Currently playing sentence (either single-sentence tap or full-book read-along)
    val playingSentenceId: String? = null,
    val isFullBookPlaying: Boolean = false,
    // Live voice capture state directly on the book page
    val activeMicIntent: MicIntent? = null,
    val liveDraftText: String = "",
    val voiceEnergy: Float = 0f,
    // Active word card (`田字格字卡`)
    val inspectedWord: ActiveWordInspection? = null,
    val collectedWords: List<CollectedWordCard> = emptyList(),
    // Subtle page-stamp pulse when a new page opens
    val newlyStampedPage: Int? = null,
    val undoBanner: UndoDeletedSentence? = null,
    val subtleNotice: String? = null,
) {
    val activeBook: StoryBook?
        get() = books.firstOrNull { it.id == activeBookId }

    val isRecording: Boolean
        get() = activeMicIntent != null
}
