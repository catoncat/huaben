package com.luna.storynote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class RecStatus {
    IDLE,
    RECORDING,
    PAUSED,
    SAVING,
}

data class StoryAppUiState(
    val notes: List<StoryNote> = emptyList(),
    val selectedNoteId: String? = null,
    val recorderOpen: Boolean = false,
    val appendTargetNoteId: String? = null,
    val recStatus: RecStatus = RecStatus.IDLE,
    val recElapsedSec: Double = 0.0,
    val voiceLevel: Float = 0f,
    val liveTranscript: String = "",
    val playingNoteId: String? = null,
    val isAudioPaused: Boolean = false,
    val playPositionSec: Float = 0f,
    val playDurationSec: Float = 0f,
    val retranscribingIds: Set<String> = emptySet(),
    val toastMessage: String? = null,
    val toastPositive: Boolean = false,
) {
    val selectedNote: StoryNote?
        get() = notes.firstOrNull { it.id == selectedNoteId }

    val appendTargetNote: StoryNote?
        get() = notes.firstOrNull { it.id == appendTargetNoteId }
}

class StoryController(
    val env: PlatformEnvironment,
    private val externalEnsureMicPermission: ((onGranted: () -> Unit) -> Unit)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val httpClient = DoubaoAuthRepository.defaultHttpClient()
    private val authRepo = DoubaoAuthRepository(env.authCacheFile, httpClient)
    val noteRepo = NoteRepository(env.storageRoot)

    private val _uiState = MutableStateFlow(StoryAppUiState())
    val uiState: StateFlow<StoryAppUiState> = _uiState.asStateFlow()

    private var activeRecId: String? = null
    private var recStartedAtMs: Long = 0L
    private var micCapture: PlatformMicCapture? = null
    private var rollingAsr: RollingDoubaoTranscriber? = null

    private var audioPlayer: PlatformAudioPlayer? = null
    private var playbackPollJob: Job? = null
    private var toastJob: Job? = null

    init {
        scope.launch(Dispatchers.IO) {
            val existing = noteRepo.listNotes()
            _uiState.update { it.copy(notes = existing) }
            try {
                authRepo.resolve(forceRefresh = false)
            } catch (_: Exception) {
            }
        }
    }

    fun showToast(msg: String, positive: Boolean = false) {
        toastJob?.cancel()
        _uiState.update { it.copy(toastMessage = msg, toastPositive = positive) }
        toastJob = scope.launch {
            delay(3600L)
            _uiState.update { it.copy(toastMessage = null) }
        }
    }

    fun openStory(noteId: String) {
        stopPlayback()
        _uiState.update { it.copy(selectedNoteId = noteId) }
    }

    fun closeStoryToShelf() {
        stopPlayback()
        _uiState.update { it.copy(selectedNoteId = null) }
    }

    fun openNewStoryRecorder() {
        stopPlayback()
        requestMicAndStart(appendNoteId = null)
    }

    fun openAppendRecorder(noteId: String) {
        stopPlayback()
        requestMicAndStart(appendNoteId = noteId)
    }

    private fun requestMicAndStart(appendNoteId: String?) {
        val launcher = externalEnsureMicPermission
        if (launcher != null) {
            launcher { startRecordingInternal(appendNoteId) }
        } else {
            startRecordingInternal(appendNoteId)
        }
    }

    private fun startRecordingInternal(appendNoteId: String?) {
        if (_uiState.value.recStatus != RecStatus.IDLE) return

        val recId = noteRepo.newRecordingId()
        val wavFile = noteRepo.stagingWavFile(recId)
        val capture = env.createMicCapture()

        val transcriber = RollingDoubaoTranscriber(
            authRepo = authRepo,
            httpClient = httpClient,
            onTranscriptChanged = { latest ->
                _uiState.update { st ->
                    if (st.recStatus == RecStatus.RECORDING || st.recStatus == RecStatus.PAUSED) {
                        st.copy(liveTranscript = latest)
                    } else {
                        st
                    }
                }
            },
        )

        try {
            capture.start(
                outputWavFile = wavFile,
                onPcmChunk = { pcm, len -> transcriber.feedPcm(pcm, len) },
                onProgress = { sec, level ->
                    _uiState.update { st ->
                        if (st.recStatus == RecStatus.RECORDING) {
                            st.copy(recElapsedSec = sec, voiceLevel = level)
                        } else {
                            st
                        }
                    }
                },
            )
            env.setKeepScreenAwake(true)
            activeRecId = recId
            recStartedAtMs = System.currentTimeMillis()
            micCapture = capture
            rollingAsr = transcriber

            _uiState.update {
                it.copy(
                    recorderOpen = true,
                    appendTargetNoteId = appendNoteId,
                    recStatus = RecStatus.RECORDING,
                    recElapsedSec = 0.0,
                    voiceLevel = 0f,
                    liveTranscript = "",
                )
            }
        } catch (e: Exception) {
            transcriber.cancel()
            noteRepo.discardStaging(recId)
            env.setKeepScreenAwake(false)
            showToast(e.message ?: "无法开启麦克风录音")
        }
    }

    fun togglePauseRecording() {
        val status = _uiState.value.recStatus
        val capture = micCapture ?: return
        if (status == RecStatus.RECORDING) {
            capture.setPaused(true)
            rollingAsr?.notifyPaused()
            _uiState.update { it.copy(recStatus = RecStatus.PAUSED, voiceLevel = 0f) }
        } else if (status == RecStatus.PAUSED) {
            capture.setPaused(false)
            _uiState.update { it.copy(recStatus = RecStatus.RECORDING) }
        }
    }

    fun finishAndTypesetStory() {
        val st = _uiState.value
        if (st.recStatus != RecStatus.RECORDING && st.recStatus != RecStatus.PAUSED) return
        if (System.currentTimeMillis() - recStartedAtMs < 750L) return

        val recId = activeRecId ?: return
        val appendTargetId = st.appendTargetNoteId
        val capture = micCapture
        val transcriber = rollingAsr
        activeRecId = null
        micCapture = null
        rollingAsr = null

        _uiState.update { it.copy(recStatus = RecStatus.SAVING, voiceLevel = 0f) }

        scope.launch(Dispatchers.IO) {
            try {
                val durSec = capture?.stop() ?: 0.0
                val (rawText, rollovers, asrErr) = transcriber?.finish(timeoutMs = 12_000L) ?: Triple("", 0, "")
                env.setKeepScreenAwake(false)

                if (durSec < 0.35 && rawText.isBlank()) {
                    noteRepo.discardStaging(recId)
                    _uiState.update {
                        it.copy(recorderOpen = false, appendTargetNoteId = null, recStatus = RecStatus.IDLE)
                    }
                    showToast("录音太短啦，多讲两句吧！")
                    return@launch
                }

                val savedNote = if (appendTargetId != null) {
                    noteRepo.appendToExistingNote(
                        noteId = appendTargetId,
                        stagingRecId = recId,
                        appendedRawText = rawText,
                        extraRollovers = rollovers,
                        asrError = asrErr,
                    )
                } else {
                    noteRepo.finalizeNewNote(
                        recId = recId,
                        durationSec = durSec,
                        rawRecognizedText = rawText,
                        rollovers = rollovers,
                        asrError = asrErr,
                    )
                }

                val refreshed = noteRepo.listNotes()
                _uiState.update {
                    it.copy(
                        notes = refreshed,
                        selectedNoteId = savedNote.id, // Open published Pinyin book page right away!
                        recorderOpen = false,
                        appendTargetNoteId = null,
                        recStatus = RecStatus.IDLE,
                        recElapsedSec = 0.0,
                        liveTranscript = "",
                    )
                }
                val msg = if (appendTargetId != null) "✨ 已续写进故事书！" else "📖 新故事排版好啦！"
                showToast(msg, positive = true)
            } catch (e: Exception) {
                env.setKeepScreenAwake(false)
                _uiState.update {
                    it.copy(recorderOpen = false, appendTargetNoteId = null, recStatus = RecStatus.IDLE)
                }
                showToast("保存失败：${e.message ?: ""}")
            }
        }
    }

    fun cancelRecording() {
        val recId = activeRecId
        val capture = micCapture
        val transcriber = rollingAsr
        activeRecId = null
        micCapture = null
        rollingAsr = null
        env.setKeepScreenAwake(false)

        _uiState.update {
            it.copy(
                recorderOpen = false,
                appendTargetNoteId = null,
                recStatus = RecStatus.IDLE,
                recElapsedSec = 0.0,
                voiceLevel = 0f,
                liveTranscript = "",
            )
        }
        scope.launch(Dispatchers.IO) {
            capture?.stop()
            transcriber?.cancel()
            if (recId != null) noteRepo.discardStaging(recId)
        }
    }

    fun togglePlayAudio(note: StoryNote) {
        val st = _uiState.value
        val existingPlayer = audioPlayer
        if (st.playingNoteId == note.id && existingPlayer != null) {
            if (st.isAudioPaused) {
                existingPlayer.resume()
                _uiState.update { it.copy(isAudioPaused = false) }
            } else {
                existingPlayer.pause()
                _uiState.update { it.copy(isAudioPaused = true) }
            }
            return
        }

        stopPlayback()
        if (!note.audioFile.isFile) {
            showToast("找不到原声录音文件")
            return
        }

        try {
            val player = env.createAudioPlayer()
            audioPlayer = player
            player.play(note.audioFile) {
                scope.launch { stopPlayback() }
            }
            val total = player.durationSec.takeIf { it > 0.1f } ?: note.durationSec.toFloat()
            _uiState.update {
                it.copy(
                    playingNoteId = note.id,
                    isAudioPaused = false,
                    playPositionSec = 0f,
                    playDurationSec = maxOf(0.5f, total),
                )
            }
            playbackPollJob = scope.launch {
                while (isActive && audioPlayer === player) {
                    _uiState.update { cur ->
                        cur.copy(playPositionSec = player.currentPositionSec)
                    }
                    delay(90L)
                }
            }
        } catch (e: Exception) {
            stopPlayback()
            showToast("播放失败：${e.message ?: ""}")
        }
    }

    fun seekAudio(noteId: String, sec: Float) {
        if (_uiState.value.playingNoteId != noteId) return
        audioPlayer?.seekToSec(sec)
        _uiState.update { it.copy(playPositionSec = sec) }
    }

    fun stopPlayback() {
        playbackPollJob?.cancel()
        playbackPollJob = null
        audioPlayer?.stop()
        audioPlayer = null
        _uiState.update {
            it.copy(playingNoteId = null, isAudioPaused = false, playPositionSec = 0f, playDurationSec = 0f)
        }
    }

    fun saveStoryEdits(noteId: String, title: String, rawText: String) {
        scope.launch(Dispatchers.IO) {
            noteRepo.updateNote(noteId = noteId, newTitle = title, newRawText = rawText)
            _uiState.update { it.copy(notes = noteRepo.listNotes()) }
            showToast("已更新文字与拼音排版", positive = true)
        }
    }

    fun retranscribeStory(note: StoryNote) {
        if (note.id in _uiState.value.retranscribingIds) return
        _uiState.update { it.copy(retranscribingIds = it.retranscribingIds + note.id) }

        scope.launch(Dispatchers.IO) {
            try {
                val (freshText, rolls) = RollingDoubaoTranscriber.transcribeWavFile(
                    wavFile = note.audioFile,
                    authRepo = authRepo,
                    httpClient = httpClient,
                )
                noteRepo.updateNote(
                    noteId = note.id,
                    newRawText = freshText,
                    newRollovers = rolls,
                    newAsrError = "",
                )
                _uiState.update {
                    it.copy(
                        notes = noteRepo.listNotes(),
                        retranscribingIds = it.retranscribingIds - note.id,
                    )
                }
                showToast("✨ 已重新识别并分段注音！", positive = true)
            } catch (e: Exception) {
                _uiState.update { it.copy(retranscribingIds = it.retranscribingIds - note.id) }
                showToast("重新识别失败：${e.message ?: "请检查网络"}")
            }
        }
    }

    fun deleteStory(note: StoryNote) {
        if (_uiState.value.playingNoteId == note.id) stopPlayback()
        scope.launch(Dispatchers.IO) {
            noteRepo.deleteNote(note.id)
            val remaining = noteRepo.listNotes()
            _uiState.update { st ->
                st.copy(
                    notes = remaining,
                    selectedNoteId = if (st.selectedNoteId == note.id) null else st.selectedNoteId,
                )
            }
        }
    }

    fun shareOrReveal(note: StoryNote) {
        env.shareOrRevealNote(note)
    }

    fun dispose() {
        stopPlayback()
        micCapture?.stop()
        rollingAsr?.cancel()
        env.setKeepScreenAwake(false)
        scope.cancel()
    }
}
