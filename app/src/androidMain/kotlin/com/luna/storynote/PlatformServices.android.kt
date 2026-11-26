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
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.FileProvider
import com.luna.storynote.audio.FrontierProtocol
import com.luna.storynote.audio.WavAudioFiles
import com.luna.storynote.model.AiGatewayConfig
import com.luna.storynote.model.StoryBook
import com.luna.storynote.storage.BookExportMode
import com.luna.storynote.storage.BookExporter
import com.luna.storynote.storage.BookJsonCodec
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

    override fun createAudioPlayer(): PlatformAudioPlayer = AndroidClipPlayer(appContext.cacheDir)

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

    override fun exportBook(book: StoryBook, mode: BookExportMode): String {
        val act = activityProvider() ?: return "当前无法调起系统分享"
        val authority = "${appContext.packageName}.fileprovider"
        val exportRoot = File(storageRoot, "exports").apply { mkdirs() }
        val safeTitle = BookExporter.sanitizeFileName(book.title)
        return try {
            when (mode) {
                BookExportMode.TEXT_ONLY -> {
                    val text = BookJsonCodec.renderBookMarkdown(book)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "《${book.title}》")
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    act.runOnUiThread {
                        act.startActivity(Intent.createChooser(intent, "分享故事文字"))
                    }
                    "已打开故事文字分享"
                }
                BookExportMode.AUDIO_ONLY -> {
                    val wavDest = File(exportRoot, "${safeTitle}_完整录音.wav")
                    val merged = BookExporter.exportMergedAudio(book, wavDest)
                        ?: return "这本书还没有录制原声"
                    val uri = FileProvider.getUriForFile(appContext, authority, merged.first)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "audio/wav"
                        putExtra(Intent.EXTRA_SUBJECT, "《${book.title}》完整录音")
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    act.runOnUiThread {
                        act.startActivity(Intent.createChooser(intent, "分享完整故事录音"))
                    }
                    val secs = merged.second.toInt().coerceAtLeast(1)
                    "完整录音已合成（约 ${secs} 秒），请选择分享方式"
                }
                BookExportMode.FULL_BUNDLE -> {
                    val bundleDir = File(exportRoot, safeTitle)
                    val res = BookExporter.exportBookBundle(book, bundleDir)
                    val uri = FileProvider.getUriForFile(appContext, authority, res.zipBundleFile)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_SUBJECT, "《${book.title}》故事包（录音+图文）")
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    act.runOnUiThread {
                        act.startActivity(Intent.createChooser(intent, "分享整本故事包"))
                    }
                    if (res.mergedAudioFile != null) "已打包完整录音、拼音绘本与插画" else "已打包拼音绘本与插画"
                }
            }
        } catch (e: Exception) {
            "导出失败：${e.message ?: "请检查存储权限"}"
        }
    }

    override fun installUpdateApk(apkFile: File): String? {
        if (!apkFile.isFile) return "安装包文件不存在"
        val act = activityProvider() ?: return "无法启动系统安装界面"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !appContext.packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${appContext.packageName}"),
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                act.runOnUiThread { act.startActivity(settingsIntent) }
                return "请先开启「允许安装未知应用」，开启后再点一次安装"
            }
            val authority = "${appContext.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(appContext, authority, apkFile)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            act.runOnUiThread { act.startActivity(installIntent) }
            "已唤起系统安装器"
        } catch (e: Exception) {
            "唤起安装器失败：${e.message ?: "未知错误"}"
        }
    }

    override fun openExternalUrl(url: String) {
        val act = activityProvider() ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            act.runOnUiThread { act.startActivity(intent) }
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

private class AndroidClipPlayer(
    private val cacheDir: File,
) : PlatformAudioPlayer {
    private val lock = Any()

    @Volatile
    private var mp: MediaPlayer? = null

    @Volatile
    private var tempSliceFile: File? = null

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

        val playFile: File
        var createdTemp: File? = null
        if (startMs > 0L || endMs > 0L) {
            val playDir = File(cacheDir, "playback_slices").apply { mkdirs() }
            val tmp = File(playDir, "slice_${System.nanoTime()}.wav")
            val durMs = try {
                WavAudioFiles.sliceWavToFile(wavFile, tmp, startMs, endMs)
            } catch (_: Throwable) {
                0L
            }
            if (durMs <= 20L || !tmp.isFile || tmp.length() <= 44L) {
                tmp.delete()
                if (startMs <= 0L) {
                    playFile = wavFile
                } else {
                    if (completedFired.compareAndSet(false, true)) onCompleted()
                    return
                }
            } else {
                createdTemp = tmp
                playFile = tmp
            }
        } else {
            playFile = wavFile
        }

        var player: MediaPlayer? = null
        try {
            val created = MediaPlayer()
            player = created
            created.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            created.setDataSource(playFile.absolutePath)
            created.prepare()
            created.setOnCompletionListener { finished ->
                var fileToDelete: File? = null
                synchronized(lock) {
                    if (mp === finished) {
                        mp = null
                        activePlaying = false
                        fileToDelete = tempSliceFile
                        tempSliceFile = null
                    }
                }
                try { finished.release() } catch (_: Exception) {}
                fileToDelete?.delete()
                if (!stopped && completedFired.compareAndSet(false, true)) {
                    onCompleted()
                }
            }
            created.setOnErrorListener { failedPlayer, _, _ ->
                var fileToDelete: File? = null
                synchronized(lock) {
                    if (mp === failedPlayer) {
                        mp = null
                        activePlaying = false
                        fileToDelete = tempSliceFile
                        tempSliceFile = null
                    }
                }
                try { failedPlayer.release() } catch (_: Exception) {}
                fileToDelete?.delete()
                if (!stopped && completedFired.compareAndSet(false, true)) {
                    onCompleted()
                }
                true
            }
            synchronized(lock) {
                if (stopped) {
                    try { created.release() } catch (_: Exception) {}
                    createdTemp?.delete()
                    return
                }
                mp = created
                tempSliceFile = createdTemp
                activePlaying = true
            }
            created.start()
        } catch (_: Throwable) {
            synchronized(lock) {
                if (mp === player) mp = null
                activePlaying = false
            }
            try { player?.release() } catch (_: Exception) {}
            createdTemp?.delete()
            stop()
        }
    }

    override fun stop() {
        var detachedPlayer: MediaPlayer?
        var fileToDelete: File?
        synchronized(lock) {
            stopped = true
            activePlaying = false
            detachedPlayer = mp
            mp = null
            fileToDelete = tempSliceFile
            tempSliceFile = null
        }
        detachedPlayer?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        fileToDelete?.delete()
    }

    override val isPlaying: Boolean
        get() = synchronized(lock) {
            activePlaying || (try { mp?.isPlaying == true } catch (_: Exception) { false })
        }
}
