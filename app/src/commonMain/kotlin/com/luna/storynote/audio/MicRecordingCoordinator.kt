package com.luna.storynote.audio

import com.luna.storynote.PlatformEnvironment
import com.luna.storynote.PlatformPcmMic
import com.luna.storynote.model.MicIntent
import com.luna.storynote.storage.StoryBookRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class MicRecordingCoordinator(
    private val env: PlatformEnvironment,
    private val authRepo: DoubaoAuthRepository,
    private val httpClient: OkHttpClient,
    private val repo: StoryBookRepository,
    private val scope: CoroutineScope,
    private val ensureMicPermission: ((onGranted: () -> Unit) -> Unit)?,
    private val onStateStarted: (MicIntent) -> Unit,
    private val onLiveDraft: (draftText: String, voiceEnergy: Float) -> Unit,
    private val onUtteranceCommitted: (bookId: String, intent: MicIntent, committed: CommittedUtterance) -> Unit,
    private val onRecordingCleared: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private var pcmMic: PlatformPcmMic? = null
    private var streamRecorder: UtteranceStreamRecorder? = null
    private var micStartedAtMs = 0L

    val canDebounceStop: Boolean
        get() = System.currentTimeMillis() - micStartedAtMs < 650L

    fun startWithIntent(bookId: String?, intent: MicIntent) {
        if (bookId == null) return
        val permGate = ensureMicPermission
        if (permGate != null) {
            permGate { launchInternal(bookId, intent) }
        } else {
            launchInternal(bookId, intent)
        }
    }

    private fun launchInternal(bookId: String, intent: MicIntent) {
        stopRecording()
        val singleShot = intent !is MicIntent.WriteAtCursor
        val mic = env.createPcmMic()
        val stream = UtteranceStreamRecorder(
            authRepo = authRepo,
            httpClient = httpClient,
            newTakeWavFile = { takeId -> repo.newTakeWavFile(bookId, takeId) },
            singleUtteranceMode = singleShot,
            onLiveDraft = onLiveDraft,
            onUtteranceCommitted = { committed -> onUtteranceCommitted(bookId, intent, committed) },
            onAutoFinishedSingle = { scope.launch { finishSingleShotCleanup() } },
            onError = { err -> scope.launch { onError(err) } },
        )

        try {
            mic.start { bytes, len -> stream.pushMicPcm(bytes, len) }
            env.setKeepScreenAwake(true)
            pcmMic = mic
            streamRecorder = stream
            micStartedAtMs = System.currentTimeMillis()
            onStateStarted(intent)
        } catch (e: Exception) {
            stream.cancel()
            env.setKeepScreenAwake(false)
            onError(e.message ?: "无法开启麦克风")
        }
    }

    private fun finishSingleShotCleanup() {
        pcmMic?.stop()
        pcmMic = null
        streamRecorder = null
        env.setKeepScreenAwake(false)
        onRecordingCleared()
    }

    fun stopRecording() {
        val mic = pcmMic
        val rec = streamRecorder
        if (mic == null && rec == null) return
        pcmMic = null
        streamRecorder = null
        env.setKeepScreenAwake(false)
        onRecordingCleared()
        scope.launch(Dispatchers.IO) {
            mic?.stop()
            rec?.finishGracefully(5000L)
        }
    }
}
