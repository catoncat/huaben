package com.luna.storynote

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.PowerManager
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.FileProvider
import java.io.File

@Composable
actual fun rememberPlatformBookFontFamily(): FontFamily = FontFamily.Serif

class AndroidPlatformEnvironment(
    private val appContext: Context,
    private val activityProvider: () -> Activity?,
) : PlatformEnvironment {
    override val storageRoot: File = appContext.getExternalFilesDir(null) ?: appContext.filesDir
    override val authCacheFile: File = File(appContext.filesDir, "doubao_auth.json")
    override val shareActionLabel: String = "分享原声与故事稿"

    private var wakeLock: PowerManager.WakeLock? = null

    override fun createMicCapture(): PlatformMicCapture = AndroidMicCapture()

    override fun createAudioPlayer(): PlatformAudioPlayer = AndroidMediaPlayerWrapper()

    override fun setKeepScreenAwake(keepAwake: Boolean) {
        val act = activityProvider()
        act?.runOnUiThread {
            if (keepAwake) {
                act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        if (keepAwake) {
            releaseWakeLock()
            try {
                val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StoryNote::RecWakeLock").apply {
                    acquire(45 * 60 * 1000L)
                }
            } catch (_: Exception) {
            }
        } else {
            releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    override fun shareOrRevealNote(note: StoryNote) {
        val act = activityProvider() ?: return
        try {
            val authority = "${appContext.packageName}.fileprovider"
            val wavUri = FileProvider.getUriForFile(appContext, authority, note.audioFile)
            val body = buildString {
                appendLine("《${note.title}》")
                appendLine()
                appendLine(note.fullText)
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_SUBJECT, "《${note.title}》")
                putExtra(Intent.EXTRA_TEXT, body)
                putExtra(Intent.EXTRA_STREAM, wavUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            act.startActivity(Intent.createChooser(intent, "分享故事"))
        } catch (_: Exception) {
        }
    }
}

private class AndroidMicCapture : PlatformMicCapture {
    @Volatile
    private var running = false
    @Volatile
    private var paused = false
    @Volatile
    private var insertBreathOnResume = false

    private var thread: Thread? = null
    private var writer: WavFileStreamWriter? = null

    @SuppressLint("MissingPermission")
    override fun start(
        outputWavFile: File,
        onPcmChunk: (bytes: ByteArray, length: Int) -> Unit,
        onProgress: (elapsedSec: Double, voiceLevel: Float) -> Unit,
    ) {
        val minBuf = AudioRecord.getMinBufferSize(
            FrontierProtocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) throw IllegalStateException("设备不支持 16kHz 麦克风采集")
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            FrontierProtocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 2, 6400),
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw IllegalStateException("无法打开麦克风，请检查录音权限")
        }

        val wav = WavFileStreamWriter(outputWavFile)
        writer = wav
        running = true
        paused = false
        audioRecord.startRecording()

        thread = Thread(
            {
                val buf = ByteArray(3200)
                try {
                    while (running) {
                        val n = audioRecord.read(buf, 0, buf.size)
                        if (n > 0 && !paused) {
                            if (insertBreathOnResume) {
                                insertBreathOnResume = false
                                wav.appendSilenceMs(350)
                            }
                            wav.appendPcm(buf, n)
                            onPcmChunk(buf.copyOf(n), n)
                            onProgress(wav.durationSec, WavFileStreamWriter.computeNormalizedRms(buf, n))
                        }
                    }
                } finally {
                    try { audioRecord.stop() } catch (_: Exception) {}
                    audioRecord.release()
                }
            },
            "android-story-mic",
        ).apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    override fun setPaused(paused: Boolean) {
        if (this.paused && !paused) {
            insertBreathOnResume = true
        }
        this.paused = paused
    }

    override fun stop(): Double {
        running = false
        thread?.join(2000L)
        thread = null
        val d = writer?.close() ?: 0.0
        writer = null
        return d
    }
}

private class AndroidMediaPlayerWrapper : PlatformAudioPlayer {
    private var mp: MediaPlayer? = null

    override fun play(wavFile: File, onCompleted: () -> Unit) {
        stop()
        mp = MediaPlayer().apply {
            setDataSource(wavFile.absolutePath)
            prepare()
            setOnCompletionListener { onCompleted() }
            start()
        }
    }

    override fun pause() {
        mp?.let { if (it.isPlaying) it.pause() }
    }

    override fun resume() {
        mp?.let { if (!it.isPlaying) it.start() }
    }

    override fun seekToSec(sec: Float) {
        val player = mp ?: return
        try {
            player.seekTo((sec * 1000f).toInt().coerceIn(0, player.duration))
        } catch (_: Exception) {
        }
    }

    override fun stop() {
        mp?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        mp = null
    }

    override val isPlaying: Boolean
        get() = try { mp?.isPlaying == true } catch (_: Exception) { false }

    override val currentPositionSec: Float
        get() = try { (mp?.currentPosition ?: 0) / 1000f } catch (_: Exception) { 0f }

    override val durationSec: Float
        get() = try { (mp?.duration ?: 0) / 1000f } catch (_: Exception) { 0f }
}
