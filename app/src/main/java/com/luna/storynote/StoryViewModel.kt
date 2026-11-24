package com.luna.storynote

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.PowerManager
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class RecMode {
    IDLE,
    RECORDING,
    SAVING,
}

data class StoryUiState(
    val recMode: RecMode = RecMode.IDLE,
    val elapsedSec: Double = 0.0,
    val voiceLevel: Float = 0f,
    val liveTranscript: String = "",
    val notes: List<StoryNote> = emptyList(),
    val playingNoteId: String? = null,
    val playPositionSec: Float = 0f,
    val playDurationSec: Float = 0f,
    val retranscribingIds: Set<String> = emptySet(),
    val toastMessage: String? = null,
    val toastIsPositive: Boolean = false,
)

class StoryViewModel(app: Application) : AndroidViewModel(app) {
    private val httpClient = DoubaoAuthRepository.defaultHttpClient()
    private val authRepo = DoubaoAuthRepository(
        cacheFile = File(app.filesDir, "doubao_auth.json"),
        httpClient = httpClient,
    )
    private val storageRoot: File = app.getExternalFilesDir(null) ?: app.filesDir
    private val noteRepo = NoteRepository(storageRoot)

    private val _uiState = MutableStateFlow(StoryUiState())
    val uiState: StateFlow<StoryUiState> = _uiState.asStateFlow()

    private var activeRecId: String? = null
    private var recStartedAtMs: Long = 0L
    private var audioRecorder: AudioWavRecorder? = null
    private var rollingAsr: RollingDoubaoTranscriber? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var mediaPlayer: MediaPlayer? = null
    private var playbackTickerJob: Job? = null
    private var toastClearJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = noteRepo.listNotes()
            _uiState.update { it.copy(notes = loaded) }
            // Pre-warm Doubao virtual Android credentials in background so first tap is instant
            try {
                authRepo.resolve(forceRefresh = false)
            } catch (_: Exception) {
            }
        }
    }

    fun showToast(message: String, positive: Boolean = false) {
        toastClearJob?.cancel()
        _uiState.update { it.copy(toastMessage = message, toastIsPositive = positive) }
        toastClearJob = viewModelScope.launch {
            delay(3800L)
            _uiState.update { it.copy(toastMessage = null) }
        }
    }

    fun startRecording() {
        if (_uiState.value.recMode != RecMode.IDLE) return
        stopPlayback()

        val recId = noteRepo.newRecordingId()
        val wavFile = noteRepo.stagingWavFile(recId)

        val transcriber = RollingDoubaoTranscriber(
            authRepo = authRepo,
            httpClient = httpClient,
            onTranscriptChanged = { latest ->
                _uiState.update { state ->
                    if (state.recMode == RecMode.RECORDING) state.copy(liveTranscript = latest) else state
                }
            },
        )

        val recorder = AudioWavRecorder(
            outputWavFile = wavFile,
            onPcmChunk = { bytes, len ->
                transcriber.feedPcm(bytes, len)
            },
            onProgress = { elapsed, rms ->
                _uiState.update { state ->
                    if (state.recMode == RecMode.RECORDING) {
                        state.copy(elapsedSec = elapsed, voiceLevel = rms)
                    } else {
                        state
                    }
                }
            },
        )

        try {
            recorder.start()
            acquireCpuWakeLock()
            activeRecId = recId
            recStartedAtMs = System.currentTimeMillis()
            audioRecorder = recorder
            rollingAsr = transcriber
            _uiState.update {
                it.copy(
                    recMode = RecMode.RECORDING,
                    elapsedSec = 0.0,
                    voiceLevel = 0f,
                    liveTranscript = "",
                )
            }
        } catch (e: Exception) {
            transcriber.cancel()
            noteRepo.discardStaging(recId)
            releaseCpuWakeLock()
            showToast(e.message ?: "启动录音失败")
        }
    }

    fun finishRecording() {
        if (_uiState.value.recMode != RecMode.RECORDING) return
        // Guard against excited double-tap right after pressing Start
        if (System.currentTimeMillis() - recStartedAtMs < 850L) return

        val recId = activeRecId ?: return
        val recorder = audioRecorder
        val transcriber = rollingAsr
        activeRecId = null
        audioRecorder = null
        rollingAsr = null

        _uiState.update { it.copy(recMode = RecMode.SAVING, voiceLevel = 0f) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val durationSec = recorder?.stop() ?: 0.0
                val (text, rollovers, asrErr) = transcriber?.finish(timeoutMs = 12_000L) ?: Triple("", 0, "")
                releaseCpuWakeLock()

                if (durationSec < 0.35 && text.isBlank()) {
                    noteRepo.discardStaging(recId)
                    _uiState.update { it.copy(recMode = RecMode.IDLE, elapsedSec = 0.0) }
                    showToast("录音太短啦，再多讲两句吧！")
                    return@launch
                }

                val saved = noteRepo.finalizeNote(
                    recId = recId,
                    durationSec = durationSec,
                    text = text,
                    rollovers = rollovers,
                    asrError = asrErr,
                )
                val updatedList = noteRepo.listNotes()
                _uiState.update {
                    it.copy(
                        recMode = RecMode.IDLE,
                        elapsedSec = 0.0,
                        liveTranscript = saved.text,
                        notes = updatedList,
                    )
                }
                showToast("🎉 第 ${saved.storyNumber} 篇故事存好啦！", positive = true)
            } catch (e: Exception) {
                releaseCpuWakeLock()
                _uiState.update { it.copy(recMode = RecMode.IDLE, elapsedSec = 0.0) }
                showToast("保存失败：${e.message ?: "未知错误"}")
            }
        }
    }

    fun cancelRecording() {
        if (_uiState.value.recMode != RecMode.RECORDING) return
        val recId = activeRecId
        val recorder = audioRecorder
        val transcriber = rollingAsr
        activeRecId = null
        audioRecorder = null
        rollingAsr = null
        releaseCpuWakeLock()

        _uiState.update {
            it.copy(recMode = RecMode.IDLE, elapsedSec = 0.0, voiceLevel = 0f, liveTranscript = "")
        }

        viewModelScope.launch(Dispatchers.IO) {
            recorder?.stop()
            transcriber?.cancel()
            if (recId != null) noteRepo.discardStaging(recId)
        }
        showToast("已取消刚才这段录音")
    }

    fun togglePlay(note: StoryNote) {
        if (_uiState.value.playingNoteId == note.id) {
            stopPlayback()
            return
        }
        stopPlayback()
        if (!note.audioFile.isFile) {
            showToast("找不到录音文件")
            return
        }
        try {
            val mp = MediaPlayer().apply {
                setDataSource(note.audioFile.absolutePath)
                prepare()
                setOnCompletionListener {
                    stopPlayback()
                }
                start()
            }
            mediaPlayer = mp
            val durSec = (mp.duration / 1000f).takeIf { it > 0f } ?: note.durationSec.toFloat()
            _uiState.update {
                it.copy(
                    playingNoteId = note.id,
                    playPositionSec = 0f,
                    playDurationSec = maxOf(0.5f, durSec),
                )
            }
            playbackTickerJob = viewModelScope.launch {
                while (isActive && mediaPlayer === mp) {
                    val pos = try {
                        if (mp.isPlaying) mp.currentPosition / 1000f else _uiState.value.playPositionSec
                    } catch (_: Exception) {
                        0f
                    }
                    _uiState.update { it.copy(playPositionSec = pos) }
                    delay(100L)
                }
            }
        } catch (e: Exception) {
            stopPlayback()
            showToast("无法播放录音：${e.message ?: ""}")
        }
    }

    fun seekPlayback(noteId: String, targetSec: Float) {
        if (_uiState.value.playingNoteId != noteId) return
        val mp = mediaPlayer ?: return
        try {
            val targetMs = (targetSec * 1000f).toInt().coerceIn(0, mp.duration)
            mp.seekTo(targetMs)
            _uiState.update { it.copy(playPositionSec = targetMs / 1000f) }
        } catch (_: Exception) {
        }
    }

    fun stopPlayback() {
        playbackTickerJob?.cancel()
        playbackTickerJob = null
        mediaPlayer?.let { mp ->
            try {
                if (mp.isPlaying) mp.stop()
            } catch (_: Exception) {
            }
            try {
                mp.release()
            } catch (_: Exception) {
            }
        }
        mediaPlayer = null
        _uiState.update { it.copy(playingNoteId = null, playPositionSec = 0f, playDurationSec = 0f) }
    }

    fun cycleSticker(note: StoryNote) {
        val idx = STORY_STICKERS.indexOf(note.sticker)
        val next = STORY_STICKERS[(idx + 1) % STORY_STICKERS.size]
        viewModelScope.launch(Dispatchers.IO) {
            noteRepo.updateNote(note.id, newSticker = next)
            _uiState.update { it.copy(notes = noteRepo.listNotes()) }
        }
    }

    fun saveNoteEdit(noteId: String, title: String, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            noteRepo.updateNote(noteId, newTitle = title, newText = text)
            _uiState.update { it.copy(notes = noteRepo.listNotes()) }
            withContext(Dispatchers.Main) {
                showToast("已保存文字修改", positive = true)
            }
        }
    }

    fun retranscribeNote(note: StoryNote) {
        if (note.id in _uiState.value.retranscribingIds) return
        _uiState.update { it.copy(retranscribingIds = it.retranscribingIds + note.id) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (freshText, rolls) = RollingDoubaoTranscriber.transcribeWavFile(
                    wavFile = note.audioFile,
                    authRepo = authRepo,
                    httpClient = httpClient,
                )
                noteRepo.updateNote(
                    noteId = note.id,
                    newText = freshText,
                    newRollovers = rolls,
                    newAsrError = "",
                )
                _uiState.update {
                    it.copy(
                        notes = noteRepo.listNotes(),
                        retranscribingIds = it.retranscribingIds - note.id,
                    )
                }
                showToast("✨ 第 ${note.storyNumber} 篇文字已重新识别！", positive = true)
            } catch (e: Exception) {
                _uiState.update { it.copy(retranscribingIds = it.retranscribingIds - note.id) }
                showToast("重新识别失败：${e.message ?: "请检查网络"}")
            }
        }
    }

    fun deleteNote(note: StoryNote) {
        if (_uiState.value.playingNoteId == note.id) {
            stopPlayback()
        }
        viewModelScope.launch(Dispatchers.IO) {
            noteRepo.deleteNote(note.id)
            _uiState.update { it.copy(notes = noteRepo.listNotes()) }
        }
    }

    fun shareNote(context: Context, note: StoryNote) {
        try {
            val appCtx = getApplication<Application>()
            val authority = "${appCtx.packageName}.fileprovider"
            val wavUri = FileProvider.getUriForFile(appCtx, authority, note.audioFile)
            val shareText = buildString {
                appendLine("${note.sticker} 第 ${note.storyNumber} 篇：${note.title}")
                if (note.text.isNotBlank()) {
                    appendLine()
                    appendLine(note.text)
                }
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_SUBJECT, "${note.sticker} ${note.title}")
                putExtra(Intent.EXTRA_TEXT, shareText)
                putExtra(Intent.EXTRA_STREAM, wavUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享故事录音与文字"))
        } catch (e: Exception) {
            showToast("分享失败：${e.message ?: ""}")
        }
    }

    private fun acquireCpuWakeLock() {
        releaseCpuWakeLock()
        try {
            val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StoryNote::RecordingLock").apply {
                acquire(45 * 60 * 1000L) // up to 45 min per single story
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseCpuWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    override fun onCleared() {
        stopPlayback()
        audioRecorder?.stop()
        rollingAsr?.cancel()
        releaseCpuWakeLock()
        super.onCleared()
    }
}
