package com.luna.storynote.ai

import com.luna.storynote.PlatformEnvironment
import com.luna.storynote.agent.ToolBus
import com.luna.storynote.model.StudioUiState
import com.luna.storynote.model.UiMotionEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

internal class BookAiCoordinator(
    private val env: PlatformEnvironment,
    toolBus: ToolBus,
    aiGatewayClient: AiGatewayClient,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<StudioUiState>,
    private val refreshBookState: (String, (StudioUiState) -> StudioUiState) -> Unit,
    private val postNotice: (String) -> Unit,
) {
    private val copyEditor = SilentCopyEditorAgent(aiGatewayClient, toolBus)
    private val voiceReviser = VoiceRevisionAgent(aiGatewayClient, toolBus)
    private val illustrator = IllustrationAgent(aiGatewayClient, toolBus)
    private val voiceSpark = VoiceSparkAgent(aiGatewayClient, toolBus, env)

    private val copyEditJobs = ConcurrentHashMap<String, Job>()
    private val revisionJobs = ConcurrentHashMap<String, Job>()
    private val illustrationJobs = ConcurrentHashMap<String, Job>()
    private val sparkJobs = ConcurrentHashMap<String, Job>()

    fun scheduleSilentCopyEdit(bookId: String, targetClauseIds: List<String> = emptyList()) {
        if (!uiState.value.aiConfig.isConfigured) return
        copyEditJobs.compute(bookId) { _, prev ->
            prev?.cancel()
            scope.launch(Dispatchers.IO) {
                delay(COPY_EDIT_DEBOUNCE_MS)
                val cfg = uiState.value.aiConfig
                if (!cfg.isConfigured) return@launch
                val result = runCatching {
                    copyEditor.reviewBook(cfg, bookId, targetClauseIds)
                }.getOrNull() ?: return@launch
                if (result.annotatedClauses > 0 || result.bibleUpdated) {
                    refreshBookState(bookId) { it }
                }
            }
        }
    }

    fun submitVoiceRevision(bookId: String, clauseId: String, instruction: String) {
        val cfg = uiState.value.aiConfig
        if (!cfg.isConfigured || uiState.value.isVoiceRevising) return
        var launched = false
        revisionJobs.compute(bookId) { _, prev ->
            if (prev?.isActive == true) {
                prev
            } else {
                launched = true
                scope.launch(Dispatchers.IO) {
                    try {
                        val res = voiceReviser.reviseClause(cfg, bookId, clauseId, instruction)
                        if (res.applied) {
                            refreshBookState(bookId) { st ->
                                if (st.activeBookId == bookId) {
                                    st.copy(
                                        isVoiceRevising = false,
                                        selectedClauseId = null,
                                        lastMotionEvent = UiMotionEvent.ClauseRevised(clauseId),
                                    )
                                } else {
                                    st
                                }
                            }
                            delay(REVISED_HIGHLIGHT_MS)
                            uiState.update { st ->
                                if (st.activeBookId == bookId && st.lastMotionEvent == UiMotionEvent.ClauseRevised(clauseId)) {
                                    st.copy(lastMotionEvent = UiMotionEvent.None)
                                } else {
                                    st
                                }
                            }
                        } else {
                            uiState.update { st -> if (st.activeBookId == bookId) st.copy(isVoiceRevising = false) else st }
                            if (uiState.value.activeBookId == bookId) {
                                postNotice(AiGatewayClient.redactSecrets(res.errorMessage ?: "修改未成功", cfg.apiKey, cfg.imageApiKey))
                            }
                        }
                    } catch (e: CancellationException) {
                        uiState.update { st -> if (st.activeBookId == bookId) st.copy(isVoiceRevising = false) else st }
                        throw e
                    } catch (e: Exception) {
                        uiState.update { st -> if (st.activeBookId == bookId) st.copy(isVoiceRevising = false) else st }
                        if (uiState.value.activeBookId == bookId) {
                            postNotice(AiGatewayClient.redactSecrets(e.message ?: "修改未成功", cfg.apiKey, cfg.imageApiKey))
                        }
                    }
                }
            }
        }
        if (launched) {
            uiState.update { st -> if (st.activeBookId == bookId) st.copy(isVoiceRevising = true) else st }
        }
    }

    fun illustrateCurrentPage() {
        val st = uiState.value
        val bookId = st.activeBookId ?: return
        val cfg = st.aiConfig
        if (!cfg.isConfigured || st.illustratingPageIndex != null) return
        val pageIndex = st.currentPageIndex
        var launched = false
        illustrationJobs.compute(bookId) { _, prev ->
            if (prev?.isActive == true) {
                prev
            } else {
                launched = true
                scope.launch(Dispatchers.IO) {
                    try {
                        val res = illustrator.illustratePage(cfg, bookId, pageIndex)
                        if (res.success) {
                            refreshBookState(bookId) { cur ->
                                if (cur.activeBookId == bookId) cur.copy(illustratingPageIndex = null) else cur
                            }
                        } else {
                            uiState.update { cur -> if (cur.activeBookId == bookId) cur.copy(illustratingPageIndex = null) else cur }
                            if (uiState.value.activeBookId == bookId) {
                                postNotice(AiGatewayClient.redactSecrets(res.errorMessage ?: "插画生成失败", cfg.apiKey, cfg.imageApiKey))
                            }
                        }
                    } catch (e: CancellationException) {
                        uiState.update { cur -> if (cur.activeBookId == bookId) cur.copy(illustratingPageIndex = null) else cur }
                        throw e
                    } catch (_: Exception) {
                        uiState.update { cur -> if (cur.activeBookId == bookId) cur.copy(illustratingPageIndex = null) else cur }
                        if (uiState.value.activeBookId == bookId) {
                            postNotice("插画生成失败")
                        }
                    }
                }
            }
        }
        if (launched) {
            uiState.update { cur ->
                if (cur.activeBookId == bookId) {
                    cur.copy(illustratingPageIndex = pageIndex, selectedClauseId = null, inspectedWord = null)
                } else {
                    cur
                }
            }
        }
    }

    fun triggerVoiceSpark() {
        val st = uiState.value
        val bookId = st.activeBookId ?: return
        val cfg = st.aiConfig
        if (!cfg.isConfigured || st.isVoiceSparkSpeaking) return
        var launched = false
        sparkJobs.compute(bookId) { _, prev ->
            prev?.cancel()
            launched = true
            scope.launch(Dispatchers.IO) {
                var shownQuestion: String? = null
                try {
                    val res = voiceSpark.spark(cfg, bookId)
                    shownQuestion = res.spokenQuestion
                    if (shownQuestion == null) {
                        uiState.update { cur ->
                            if (cur.activeBookId == bookId) cur.copy(isVoiceSparkSpeaking = false, activeSparkQuestion = null) else cur
                        }
                        if (uiState.value.activeBookId == bookId) {
                            postNotice("灵感语音暂不可用")
                        }
                    } else {
                        uiState.update { cur ->
                            if (cur.activeBookId == bookId) {
                                cur.copy(isVoiceSparkSpeaking = false, activeSparkQuestion = shownQuestion)
                            } else {
                                cur
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    uiState.update { cur ->
                        if (cur.activeBookId == bookId) cur.copy(isVoiceSparkSpeaking = false, activeSparkQuestion = null) else cur
                    }
                    throw e
                } catch (_: Exception) {
                    uiState.update { cur ->
                        if (cur.activeBookId == bookId) cur.copy(isVoiceSparkSpeaking = false, activeSparkQuestion = null) else cur
                    }
                    if (uiState.value.activeBookId == bookId) {
                        postNotice("灵感语音暂不可用")
                    }
                }
                if (shownQuestion != null) {
                    delay(SPARK_QUESTION_VISIBLE_MS)
                    uiState.update { cur ->
                        if (cur.activeBookId == bookId &&
                            !cur.isVoiceSparkSpeaking &&
                            cur.activeSparkQuestion == shownQuestion
                        ) {
                            cur.copy(activeSparkQuestion = null)
                        } else {
                            cur
                        }
                    }
                }
            }
        }
        if (launched) {
            uiState.update { cur ->
                if (cur.activeBookId == bookId) {
                    cur.copy(
                        isVoiceSparkSpeaking = true,
                        activeSparkQuestion = null,
                        selectedClauseId = null,
                        inspectedWord = null,
                    )
                } else {
                    cur
                }
            }
        }
    }

    fun cancelTransientOnSwitch(bookId: String) {
        copyEditJobs.remove(bookId)?.cancel()
        sparkJobs.remove(bookId)?.cancel()
    }

    fun cancelForBook(bookId: String) {
        copyEditJobs.remove(bookId)?.cancel()
        revisionJobs.remove(bookId)?.cancel()
        illustrationJobs.remove(bookId)?.cancel()
        sparkJobs.remove(bookId)?.cancel()
    }

    fun cancelAll() {
        listOf(copyEditJobs, revisionJobs, illustrationJobs, sparkJobs).forEach { map ->
            map.values.forEach { it.cancel() }
            map.clear()
        }
        env.stopSpeaking()
    }

    companion object {
        const val COPY_EDIT_DEBOUNCE_MS = 1000L
        const val REVISED_HIGHLIGHT_MS = 1800L
        const val SPARK_QUESTION_VISIBLE_MS = 7000L
    }
}
