package com.luna.storynote.model

import com.luna.storynote.literacy.WordSpan
import com.luna.storynote.update.AppUpdateConfig
import com.luna.storynote.update.AppUpdateState
import java.net.URI

enum class StudioDestination {
    SHELF,
    BOOK,
    WORD_BOX,
}

enum class DeviceFormFactor {
    PHONE,
    TABLET_PORTRAIT,
    TABLET_LANDSCAPE_OR_DESKTOP,
}

sealed interface UiMotionEvent {
    data object None : UiMotionEvent
    data class ClauseLanded(val clauseId: String) : UiMotionEvent
    data class ClauseRevised(val clauseId: String) : UiMotionEvent
    data class ClauseErased(val clauseId: String) : UiMotionEvent
    data class WordBookmarked(val word: String) : UiMotionEvent
    data class PageStamped(val pageIndex: Int) : UiMotionEvent
}

data class AiGatewayConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val textModel: String = "",
    val imageBaseUrl: String = "",
    val imageApiKey: String = "",
    val imageModel: String = "",
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && textModel.isNotBlank()

    val effectiveImageBaseUrl: String
        get() = imageBaseUrl.trim().ifEmpty { baseUrl.trim() }

    val effectiveImageApiKey: String
        get() = imageApiKey.trim().ifEmpty { apiKey.trim() }

    val isImageConfigured: Boolean
        get() = effectiveImageBaseUrl.isNotBlank() &&
            effectiveImageApiKey.isNotBlank() &&
            imageModel.isNotBlank()

    fun redactedSummary(): String {
        val host = try {
            URI(baseUrl.trim()).host ?: baseUrl.trim()
        } catch (_: Exception) {
            "(invalid-url)"
        }
        val keyState = if (apiKey.isBlank()) "(unset)" else "sk-***"
        val imgKeyState = if (effectiveImageApiKey.isBlank()) "(unset)" else "sk-***"
        return "AiGatewayConfig(host=$host, textModel=${textModel.ifBlank { "(unset)" }}, " +
            "key=$keyState, imageModel=${imageModel.ifBlank { "(unset)" }}, imageKey=$imgKeyState)"
    }
}

sealed interface MicIntent {
    data object WriteAtCursor : MicIntent
    data class ReplaceClause(val clauseId: String) : MicIntent
    data class ReplaceWord(val clauseId: String, val startIdx: Int, val endIdx: Int) : MicIntent
    data object SpeakBookTitle : MicIntent
    data class VoiceRevision(val clauseId: String) : MicIntent
}

data class ActiveWordInspection(
    val clauseId: String,
    val span: WordSpan,
    val homophones: List<String>,
    val softSuggestion: String? = null,
    val initialCharIndexInWord: Int = 0,
    val softAnnotationOffsetInWord: Int? = null,
    val softAnnotationLength: Int = 0,
)

data class UndoDeletedClause(
    val bookId: String,
    val afterClauseId: String?,
    val clause: Clause,
)

data class StudioUiState(
    val destination: StudioDestination = StudioDestination.SHELF,
    val books: List<StoryBook> = emptyList(),
    val activeBookId: String? = null,
    val currentPageIndex: Int = 0,
    val cursorAfterClauseId: String? = null,
    val selectedClauseId: String? = null,
    val playingClauseId: String? = null,
    val isFullBookPlaying: Boolean = false,
    val activeMicIntent: MicIntent? = null,
    val liveDraftText: String = "",
    val voiceEnergy: Float = 0f,
    val inspectedWord: ActiveWordInspection? = null,
    val collectedWords: List<CollectedWordCard> = emptyList(),
    val newlyStampedPage: Int? = null,
    val undoBanner: UndoDeletedClause? = null,
    val subtleNotice: String? = null,
    val aiConfig: AiGatewayConfig = AiGatewayConfig(),
    val isParentSettingsOpen: Boolean = false,
    val isTestingAiConnectivity: Boolean = false,
    val aiConnectivityMessage: String? = null,
    val aiConnectivityOk: Boolean? = null,
    val discoveredAiModels: List<String> = emptyList(),
    val bookBible: BookBible? = null,
    val timeMachineSnapshots: List<TimeMachineSnapshot> = emptyList(),
    val isTimeMachineOpen: Boolean = false,
    val timeMachineTargetClauseId: String? = null,
    val isFullTextEditorOpen: Boolean = false,
    val illustratingPageIndex: Int? = null,
    val isVoiceRevising: Boolean = false,
    val isVoiceSparkSpeaking: Boolean = false,
    val activeSparkQuestion: String? = null,
    val lastMotionEvent: UiMotionEvent = UiMotionEvent.None,
    val updateConfig: AppUpdateConfig = AppUpdateConfig(),
    val updateState: AppUpdateState = AppUpdateState.Idle,
) {
    val activeBook: StoryBook?
        get() = books.firstOrNull { it.id == activeBookId }

    val isRecording: Boolean
        get() = activeMicIntent != null

    val isAiConfigured: Boolean
        get() = aiConfig.isConfigured

    val hasAvailableUpdate: Boolean
        get() = updateState is AppUpdateState.Available || updateState is AppUpdateState.ReadyToInstall
}
