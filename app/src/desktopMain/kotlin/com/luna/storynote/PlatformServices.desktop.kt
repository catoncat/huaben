package com.luna.storynote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import com.luna.storynote.audio.FrontierProtocol
import com.luna.storynote.audio.WavAudioFiles
import com.luna.storynote.model.AiGatewayConfig
import com.luna.storynote.model.StoryBook
import com.luna.storynote.storage.BookExportMode
import com.luna.storynote.storage.BookExporter
import com.luna.storynote.storage.BookJsonCodec
import org.jetbrains.skia.Image as SkiaImage
import org.json.JSONObject
import java.awt.Desktop
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineEvent
import javax.sound.sampled.TargetDataLine

@Composable
actual fun rememberPlatformBookFontFamily(): FontFamily = remember {
    loadDesktopBookFontFamily()
}

actual fun decodeFileToImageBitmap(file: File): ImageBitmap? {
    if (!file.isFile || !file.canRead() || file.length() <= 0L) return null
    return try {
        SkiaImage.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
    } catch (_: Throwable) {
        null
    }
}

fun loadDesktopBookFontFamily(): FontFamily {
    for (path in listOf("/System/Library/Fonts/Supplemental/Songti.ttc", "/System/Library/Fonts/PingFang.ttc")) {
        val f = File(path)
        if (f.isFile) {
            try {
                return FontFamily(Font(f))
            } catch (_: Exception) {
            }
        }
    }
    return FontFamily.Serif
}

class DesktopPlatformEnvironment(
    override val storageRoot: File = File(System.getProperty("user.home"), ".local/share/story-note-v3").apply { mkdirs() },
    override val authCacheFile: File = File(System.getProperty("user.home"), ".config/story-note/doubao_auth.json"),
    override val privateConfigDir: File = File(System.getProperty("user.home"), ".config/story-note").apply { mkdirs() },
    private val piAgentDir: File = File(System.getProperty("user.home"), ".pi/agent"),
) : PlatformEnvironment {
    private var activeSayProcess: Process? = null
    private val sayVoice: String? by lazy { resolveMandarinSayVoice() }

    override fun createPcmMic(): PlatformPcmMic = DesktopPcmMic()

    override fun createAudioPlayer(): PlatformAudioPlayer = DesktopWavPlayer()

    override fun speakStandardWord(word: String) {
        if (word.isBlank()) return
        try {
            activeSayProcess?.destroy()
            val cmd = buildList {
                add("/usr/bin/say")
                sayVoice?.let {
                    add("-v")
                    add(it)
                }
                add("-r")
                add("165")
                add(word)
            }
            activeSayProcess = ProcessBuilder(cmd).start()
        } catch (_: Exception) {
        }
    }

    override fun stopSpeaking() {
        try {
            activeSayProcess?.destroyForcibly()
        } catch (_: Exception) {
        }
        activeSayProcess = null
    }

    override fun loadDefaultAiConfig(): AiGatewayConfig {
        val modelsFile = File(piAgentDir, "models.json")
        if (!modelsFile.isFile) return AiGatewayConfig()
        return try {
            val modelsJson = JSONObject(modelsFile.readText(Charsets.UTF_8))
            val magpie = modelsJson.optJSONObject("providers")?.optJSONObject("magpie-railway")
                ?: return AiGatewayConfig()
            val baseUrl = magpie.optString("baseUrl", "").trim()
            val apiKey = magpie.optString("apiKey", "").trim()
            if (baseUrl.isEmpty() || apiKey.isEmpty()) return AiGatewayConfig()

            val settingsFile = File(piAgentDir, "settings.json")
            val defaultModel = if (settingsFile.isFile) {
                try {
                    val settingsObj = JSONObject(settingsFile.readText(Charsets.UTF_8))
                    val defaultProvider = settingsObj.optString("defaultProvider", "").trim()
                    if (defaultProvider.isEmpty() || defaultProvider == "magpie-railway") {
                        settingsObj.optString("defaultModel", "").trim()
                    } else {
                        ""
                    }
                } catch (_: Exception) {
                    ""
                }
            } else {
                ""
            }
            val fallbackModel = magpie.optJSONArray("models")
                ?.optJSONObject(0)
                ?.optString("id", "")
                ?.trim()
                .orEmpty()
            val textModel = defaultModel.ifEmpty { fallbackModel.ifEmpty { "auto/deepseek-v4.1-flash" } }
            AiGatewayConfig(
                baseUrl = baseUrl,
                apiKey = apiKey,
                textModel = textModel,
                imageModel = "codex/gpt-image-2",
            )
        } catch (_: Exception) {
            AiGatewayConfig()
        }
    }

    override fun loadDefaultGithubToken(): String {
        val envToken = System.getenv("GITHUB_TOKEN")?.trim().orEmpty()
        if (envToken.isNotEmpty()) return envToken
        return try {
            val proc = ProcessBuilder("/opt/homebrew/bin/gh", "auth", "token")
                .redirectErrorStream(false)
                .start()
            val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
            if (proc.waitFor() == 0 && out.startsWith("gh")) out else ""
        } catch (_: Exception) {
            ""
        }
    }

    override fun openStrokeAssetStream(): InputStream? =
        DesktopPlatformEnvironment::class.java.getResourceAsStream("/strokes.bin.gz")

    override fun setKeepScreenAwake(keepAwake: Boolean) = Unit

    override fun exportBook(book: StoryBook, mode: BookExportMode): String {
        val downloadsDir = File(System.getProperty("user.home"), "Downloads/StoryNoteExports")
        val safeTitle = BookExporter.sanitizeFileName(book.title)
        val outDir = File(downloadsDir, safeTitle).apply { mkdirs() }
        return try {
            val highlightTarget: File
            val message: String
            when (mode) {
                BookExportMode.TEXT_ONLY -> {
                    val mdFile = File(outDir, "$safeTitle.md")
                    BookJsonCodec.atomicWriteText(mdFile, BookJsonCodec.renderBookMarkdown(book))
                    val htmlFile = File(outDir, "${safeTitle}_带拼音绘本.html")
                    BookJsonCodec.atomicWriteText(htmlFile, BookExporter.renderBookRubyHtml(book))
                    highlightTarget = htmlFile
                    message = "已导出文稿与拼音绘本到 Downloads/StoryNoteExports/$safeTitle"
                }
                BookExportMode.AUDIO_ONLY -> {
                    val wavFile = File(outDir, "${safeTitle}_完整录音.wav")
                    val merged = BookExporter.exportMergedAudio(book, wavFile)
                        ?: return "这本书还没有录制原声"
                    highlightTarget = merged.first
                    val secs = merged.second.toInt().coerceAtLeast(1)
                    message = "已合并完整录音（约 ${secs} 秒）到 Downloads/StoryNoteExports/$safeTitle"
                }
                BookExportMode.FULL_BUNDLE -> {
                    val res = BookExporter.exportBookBundle(book, outDir)
                    highlightTarget = res.zipBundleFile
                    message = if (res.mergedAudioFile != null) {
                        "已导出录音、拼音绘本与故事包到 Downloads/StoryNoteExports/$safeTitle"
                    } else {
                        "已导出拼音绘本与故事包到 Downloads/StoryNoteExports/$safeTitle"
                    }
                }
            }
            revealInFinder(highlightTarget)
            message
        } catch (e: Exception) {
            "导出失败：${e.message ?: "请检查磁盘空间"}"
        }
    }

    override fun installUpdateApk(apkFile: File): String {
        revealInFinder(apkFile)
        return "新版 APK 已下载，已在 Finder 中定位（可传至安卓平板/手机安装）"
    }

    override fun openExternalUrl(url: String) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(url))
            }
        } catch (_: Exception) {
        }
    }

    private fun revealInFinder(target: File) {
        try {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            if (os.contains("mac")) {
                ProcessBuilder("/usr/bin/open", "-R", target.absolutePath).start()
            } else if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(target.parentFile ?: target)
            }
        } catch (_: Exception) {
        }
    }

    private fun resolveMandarinSayVoice(): String? {
        return try {
            val proc = ProcessBuilder("/usr/bin/say", "-v", "?").redirectErrorStream(true).start()
            val lines = proc.inputStream.bufferedReader(Charsets.UTF_8).readLines()
            proc.waitFor()
            val voices = lines.mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2) parts[0] to parts[1] else null
            }
            voices.firstOrNull { it.first.equals("Tingting", ignoreCase = true) }?.first
                ?: voices.firstOrNull { it.second.startsWith("zh_CN", ignoreCase = true) || it.second.startsWith("zh-CN", ignoreCase = true) }?.first
                ?: voices.firstOrNull { it.second.startsWith("zh", ignoreCase = true) }?.first
        } catch (_: Exception) {
            null
        }
    }
}

private class DesktopPcmMic : PlatformPcmMic {
    @Volatile
    private var running = false
    private var line: TargetDataLine? = null
    private var thread: Thread? = null

    override fun start(onPcmChunk: (bytes: ByteArray, length: Int) -> Unit) {
        val fmt = AudioFormat(FrontierProtocol.SAMPLE_RATE.toFloat(), 16, 1, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, fmt)
        if (!AudioSystem.isLineSupported(info)) {
            throw IllegalStateException("当前电脑麦克风不可用")
        }
        val target = (AudioSystem.getLine(info) as TargetDataLine).apply {
            open(fmt, 4800)
            start()
        }
        line = target
        running = true
        thread = Thread(
            {
                val buf = ByteArray(1600)
                try {
                    while (running) {
                        val n = target.read(buf, 0, buf.size)
                        if (n > 0) onPcmChunk(buf.copyOf(n), n)
                    }
                } finally {
                    try { target.stop() } catch (_: Exception) {}
                    try { target.close() } catch (_: Exception) {}
                }
            },
            "desktop-pcm-mic",
        ).apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        running = false
        line?.stop()
        thread?.join(1500L)
        thread = null
        line = null
    }
}

private class DesktopWavPlayer : PlatformAudioPlayer {
    private val lock = Any()
    private val playLock = Any()
    private val clipCloser = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "desktop-wav-clip-closer").apply { isDaemon = true }
    }

    private var clip: Clip? = null
    private var currentToken: Any? = null
    private var manuallyStopped = false
    private var activePlaying = false
    private var watcherThread: Thread? = null

    private fun closeClipAsync(c: Clip) {
        try {
            clipCloser.execute {
                try { c.close() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            try { c.close() } catch (_: Exception) {}
        }
    }

    override fun playRange(wavFile: File, startMs: Long, endMs: Long, onCompleted: () -> Unit) {
        var immediateCompletionToFire: (() -> Unit)? = null
        synchronized(playLock) {
            stop()
            if (!wavFile.isFile || !wavFile.canRead() || wavFile.length() <= 44L) {
                return
            }
            val token = Any()
            synchronized(lock) {
                currentToken = token
                manuallyStopped = false
            }
            val completedFired = AtomicBoolean(false)

            var openedClip: Clip? = null
            var installed = false
            try {
                // Decode the exact requested range into an in-memory AudioInputStream so playback always starts
                // cleanly at frame 0 and runs to exact EOF without macOS CoreAudio microsecondPosition race bugs.
                val slice = WavAudioFiles.readPcmRange(wavFile, startMs, endMs)
                if (slice != null && slice.pcmBytes.isEmpty()) {
                    val shouldComplete = synchronized(lock) {
                        val active = !manuallyStopped && currentToken === token
                        if (currentToken === token) {
                            currentToken = null
                            activePlaying = false
                        }
                        active
                    }
                    if (shouldComplete && completedFired.compareAndSet(false, true)) {
                        immediateCompletionToFire = onCompleted
                    }
                    return@synchronized
                }

                val c: Clip = if (slice != null) {
                    val fmt = AudioFormat(
                        slice.format.sampleRate.toFloat(),
                        slice.format.bitsPerSample,
                        slice.format.channels,
                        slice.format.bitsPerSample > 8,
                        false,
                    )
                    val frameCount = (slice.pcmBytes.size / slice.format.blockAlign).toLong()
                    AudioInputStream(ByteArrayInputStream(slice.pcmBytes), fmt, frameCount).use { stream ->
                        val candidate = AudioSystem.getClip()
                        openedClip = candidate
                        candidate.open(stream)
                        candidate
                    }
                } else {
                    AudioSystem.getAudioInputStream(wavFile).use { stream ->
                        val candidate = AudioSystem.getClip()
                        openedClip = candidate
                        candidate.open(stream)
                        candidate
                    }
                }

                val totalUs = c.microsecondLength.coerceAtLeast(0L)
                if (totalUs <= 10_000L) {
                    closeClipAsync(c)
                    openedClip = null
                    val shouldComplete = synchronized(lock) {
                        val active = !manuallyStopped && currentToken === token
                        if (currentToken === token) {
                            currentToken = null
                            activePlaying = false
                        }
                        active
                    }
                    if (shouldComplete && completedFired.compareAndSet(false, true)) {
                        immediateCompletionToFire = onCompleted
                    }
                    return@synchronized
                }

                c.framePosition = 0
                c.addLineListener { ev ->
                    if (ev.type == LineEvent.Type.STOP) {
                        var clipToClose: Clip? = null
                        var shouldNotify = false
                        synchronized(lock) {
                            if (currentToken === token && clip === c) {
                                activePlaying = false
                                if (!manuallyStopped) {
                                    shouldNotify = true
                                    clip = null
                                    currentToken = null
                                    clipToClose = c
                                }
                            }
                        }
                        clipToClose?.let { closeClipAsync(it) }
                        if (shouldNotify && completedFired.compareAndSet(false, true)) {
                            onCompleted()
                        }
                    }
                }

                val canStart = synchronized(lock) {
                    if (manuallyStopped || currentToken !== token) {
                        false
                    } else {
                        clip = c
                        activePlaying = true
                        watcherThread = null
                        installed = true
                        true
                    }
                }
                if (!canStart) {
                    closeClipAsync(c)
                    openedClip = null
                    return@synchronized
                }

                c.start()
            } catch (_: Throwable) {
                if (!installed) {
                    synchronized(lock) {
                        if (currentToken === token) {
                            currentToken = null
                            activePlaying = false
                        }
                    }
                    openedClip?.let {
                        try { it.stop() } catch (_: Exception) {}
                        closeClipAsync(it)
                    }
                } else {
                    var detachedClip: Clip? = null
                    var detachedWt: Thread? = null
                    synchronized(lock) {
                        if (currentToken === token && clip === openedClip) {
                            detachedClip = clip
                            clip = null
                            detachedWt = watcherThread
                            watcherThread = null
                            activePlaying = false
                            currentToken = null
                        }
                    }
                    detachedClip?.let {
                        try { it.stop() } catch (_: Exception) {}
                        closeClipAsync(it)
                    }
                    val wt = detachedWt
                    if (wt != null && wt !== Thread.currentThread()) {
                        wt.interrupt()
                        try { wt.join(150L) } catch (_: InterruptedException) {}
                    }
                }
            }
        }
        immediateCompletionToFire?.invoke()
    }

    override fun stop() {
        var detachedClip: Clip? = null
        var detachedWt: Thread? = null
        synchronized(lock) {
            manuallyStopped = true
            activePlaying = false
            currentToken = null
            detachedClip = clip
            clip = null
            detachedWt = watcherThread
            watcherThread = null
        }
        detachedClip?.let {
            try { it.stop() } catch (_: Exception) {}
            closeClipAsync(it)
        }
        val wt = detachedWt
        if (wt != null && wt !== Thread.currentThread()) {
            wt.interrupt()
            try { wt.join(150L) } catch (_: InterruptedException) {}
        }
    }

    override val isPlaying: Boolean
        get() = synchronized(lock) {
            activePlaying || clip?.isRunning == true
        }
}
