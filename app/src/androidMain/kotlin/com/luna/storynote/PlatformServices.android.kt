package com.luna.storynote

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import com.luna.storynote.audio.FrontierProtocol
import com.luna.storynote.audio.WavAudioFiles
import com.luna.storynote.model.AiGatewayConfig
import com.luna.storynote.model.StoryBook
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

@Composable
actual fun rememberPlatformBookFontFamily(): FontFamily = FontFamily.Serif

actual fun decodeFileToImageBitmap(file: File): ImageBitmap? {
    if (!file.isFile || !file.canRead() || file.length() <= 0L) return null
    return try {
        BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}

class AndroidPlatformEnvironment(
    private val appContext: Context,
    private val activityProvider: () -> Activity?,
) : PlatformEnvironment {
    override val storageRoot: File = appContext.getExternalFilesDir(null) ?: appContext.filesDir
    override val privateConfigDir: File = appContext.filesDir
    override val authCacheFile: File = File(privateConfigDir, "doubao_auth.json")

    private var wakeLock: PowerManager.WakeLock? = null
    private val ttsLock = Any()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingUtterance: String? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale.CHINA)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.CHINESE)
                }
                tts?.setSpeechRate(0.86f)
                val queued = synchronized(ttsLock) {
                    ttsReady = true
                    val q = pendingUtterance
                    pendingUtterance = null
                    q
                }
                if (!queued.isNullOrBlank()) {
                    tts?.speak(queued, TextToSpeech.QUEUE_FLUSH, null, "word_tts")
                }
            }
        }
    }

    override fun createPcmMic(): PlatformPcmMic = AndroidPcmMic()

    override fun createAudioPlayer(): PlatformAudioPlayer = AndroidClipPlayer()

    override fun speakStandardWord(word: String) {
        if (word.isBlank()) return
        synchronized(ttsLock) {
            if (ttsReady) {
                tts?.speak(word, TextToSpeech.QUEUE_FLUSH, null, "word_tts")
            } else {
                pendingUtterance = word
            }
        }
    }

    override fun stopSpeaking() {
        synchronized(ttsLock) {
            pendingUtterance = null
            tts?.stop()
        }
    }

    override fun loadDefaultAiConfig(): AiGatewayConfig = AiGatewayConfig()

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
            val text = if (mdFile.isFile) mdFile.readText(Charsets.UTF_8) else book.clauses.joinToString("\n") { it.text }
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
        synchronized(ttsLock) {
            pendingUtterance = null
            ttsReady = false
            tts?.shutdown()
            tts = null
        }
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
                val buf = ByteArray(1600)
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
    @Volatile
    private var mp: MediaPlayer? = null

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var playbackThread: Thread? = null

    @Volatile
    private var stopped = false

    @Volatile
    private var activePlaying = false

    override fun playRange(wavFile: File, startMs: Long, endMs: Long, onCompleted: () -> Unit) {
        stop()
        if (!wavFile.isFile || !wavFile.canRead() || wavFile.length() <= 44L) {
            return
        }
        stopped = false
        val completedFired = AtomicBoolean(false)

        var createdTrack: AudioTrack? = null
        try {
            val slice = WavAudioFiles.readPcmRange(wavFile, startMs, endMs)
            if (slice == null) {
                playViaMediaPlayer(wavFile, onCompleted, completedFired)
                return
            }
            if (slice.pcmBytes.isEmpty()) {
                if (completedFired.compareAndSet(false, true)) onCompleted()
                return
            }

            val chMask = if (slice.format.channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val enc = if (slice.format.bitsPerSample == 8) AudioFormat.ENCODING_PCM_8BIT else AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioTrack.getMinBufferSize(slice.format.sampleRate, chMask, enc).coerceAtLeast(4096)
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(slice.format.sampleRate)
                        .setChannelMask(chMask)
                        .setEncoding(enc)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf)
                .build()
            createdTrack = audioTrack
            if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
                try { audioTrack.release() } catch (_: Exception) {}
                stop()
                return
            }

            track = audioTrack
            activePlaying = true
            playbackThread = Thread(
                {
                    try {
                        audioTrack.play()
                        var off = 0
                        val pcm = slice.pcmBytes
                        while (!stopped && off < pcm.size) {
                            val len = minOf(4096, pcm.size - off)
                            val written = audioTrack.write(pcm, off, len)
                            if (written <= 0) break
                            off += written
                        }
                        if (!stopped) {
                            try { audioTrack.stop() } catch (_: Exception) {}
                        }
                    } catch (_: Throwable) {
                    } finally {
                        activePlaying = false
                        try { audioTrack.release() } catch (_: Exception) {}
                        if (track === audioTrack) track = null
                        if (!stopped && completedFired.compareAndSet(false, true)) {
                            onCompleted()
                        }
                    }
                },
                "android-pcm-range-player",
            ).apply {
                isDaemon = true
                start()
            }
        } catch (_: Throwable) {
            activePlaying = false
            if (createdTrack != null && track !== createdTrack) {
                try { createdTrack.release() } catch (_: Exception) {}
            }
            stop()
        }
    }

    private fun playViaMediaPlayer(wavFile: File, onCompleted: () -> Unit, completedFired: AtomicBoolean) {
        var player: MediaPlayer? = null
        try {
            val created = MediaPlayer()
            player = created
            created.apply {
                setDataSource(wavFile.absolutePath)
                prepare()
                setOnCompletionListener {
                    activePlaying = false
                    try { it.release() } catch (_: Exception) {}
                    if (mp === it) mp = null
                    if (!stopped && completedFired.compareAndSet(false, true)) {
                        onCompleted()
                    }
                }
                setOnErrorListener { failedPlayer, _, _ ->
                    activePlaying = false
                    try { failedPlayer.release() } catch (_: Exception) {}
                    if (mp === failedPlayer) mp = null
                    true
                }
                mp = this
                activePlaying = true
                start()
            }
        } catch (_: Throwable) {
            activePlaying = false
            if (mp === player) mp = null
            try { player?.release() } catch (_: Exception) {}
            stop()
        }
    }

    override fun stop() {
        stopped = true
        activePlaying = false
        track?.let {
            try { it.pause() } catch (_: Exception) {}
            try { it.flush() } catch (_: Exception) {}
            try { it.stop() } catch (_: Exception) {}
        }
        playbackThread?.join(500L)
        playbackThread = null
        track = null
        mp?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        mp = null
    }

    override val isPlaying: Boolean
        get() = activePlaying || (try { mp?.isPlaying == true } catch (_: Exception) { false })
}
