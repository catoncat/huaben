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
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import java.io.File
import java.io.InputStream
import java.util.Locale

@Composable
actual fun rememberPlatformBookFontFamily(): FontFamily = FontFamily.Serif

class AndroidPlatformEnvironment(
    private val appContext: Context,
    private val activityProvider: () -> Activity?,
) : PlatformEnvironment {
    override val storageRoot: File = appContext.getExternalFilesDir(null) ?: appContext.filesDir
    override val authCacheFile: File = File(appContext.filesDir, "doubao_auth.json")

    private var wakeLock: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINA
                tts?.setSpeechRate(0.86f)
                ttsReady = true
            }
        }
    }

    override fun createPcmMic(): PlatformPcmMic = AndroidPcmMic()

    override fun createAudioPlayer(): PlatformAudioPlayer = AndroidClipPlayer()

    override fun speakStandardWord(word: String) {
        if (ttsReady && word.isNotBlank()) {
            tts?.speak(word, TextToSpeech.QUEUE_FLUSH, null, "word_tts")
        }
    }

    override fun openStrokeAssetStream(): InputStream? =
        try {
            appContext.assets.open("strokes.bin.gz")
        } catch (_: Exception) {
            null
        }

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
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StoryNote::BookRecLock").apply {
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

    override fun exportBook(book: StoryBook) {
        val act = activityProvider() ?: return
        try {
            val mdFile = File(book.bookDir, "story.md")
            val text = if (mdFile.isFile) mdFile.readText(Charsets.UTF_8) else book.sentences.joinToString("\n") { it.text }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "《${book.title}》")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            act.startActivity(Intent.createChooser(intent, "导出故事书"))
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        releaseWakeLock()
        tts?.shutdown()
        tts = null
    }
}

private class AndroidPcmMic : PlatformPcmMic {
    @Volatile
    private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    override fun start(onPcmChunk: (bytes: ByteArray, length: Int) -> Unit) {
        val minBuf = AudioRecord.getMinBufferSize(
            FrontierProtocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) throw IllegalStateException("设备麦克风不支持 16kHz")
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            FrontierProtocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 2, 6400),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("无法访问麦克风")
        }
        running = true
        record.startRecording()
        thread = Thread(
            {
                val buf = ByteArray(1600) // 50ms low-latency chunks
                try {
                    while (running) {
                        val n = record.read(buf, 0, buf.size)
                        if (n > 0) onPcmChunk(buf.copyOf(n), n)
                    }
                } finally {
                    try { record.stop() } catch (_: Exception) {}
                    record.release()
                }
            },
            "story-pcm-mic",
        ).apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    override fun stop() {
        running = false
        thread?.join(1500L)
        thread = null
    }
}

private class AndroidClipPlayer : PlatformAudioPlayer {
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

    override fun stop() {
        mp?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        mp = null
    }

    override val isPlaying: Boolean
        get() = try { mp?.isPlaying == true } catch (_: Exception) { false }
}
