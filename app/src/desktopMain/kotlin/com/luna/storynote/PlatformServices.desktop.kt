package com.luna.storynote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import java.awt.Desktop
import java.io.File
import java.io.InputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineEvent
import javax.sound.sampled.TargetDataLine

@Composable
actual fun rememberPlatformBookFontFamily(): FontFamily = remember {
    loadDesktopBookFontFamily()
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
) : PlatformEnvironment {
    private var activeSayProcess: Process? = null

    override fun createPcmMic(): PlatformPcmMic = DesktopPcmMic()

    override fun createAudioPlayer(): PlatformAudioPlayer = DesktopWavPlayer()

    override fun speakStandardWord(word: String) {
        if (word.isBlank()) return
        try {
            activeSayProcess?.destroy()
            activeSayProcess = ProcessBuilder("/usr/bin/say", "-r", "165", word).start()
        } catch (_: Exception) {
        }
    }

    override fun openStrokeAssetStream(): InputStream? =
        DesktopPlatformEnvironment::class.java.getResourceAsStream("/strokes.bin.gz")

    override fun setKeepScreenAwake(keepAwake: Boolean) = Unit

    override fun exportBook(book: StoryBook) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(book.bookDir)
            }
        } catch (_: Exception) {
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
    private var clip: Clip? = null

    override fun play(wavFile: File, onCompleted: () -> Unit) {
        stop()
        val stream = AudioSystem.getAudioInputStream(wavFile)
        val c = AudioSystem.getClip()
        c.open(stream)
        c.addLineListener { ev ->
            if (ev.type == LineEvent.Type.STOP && c.microsecondPosition >= c.microsecondLength - 50_000L) {
                onCompleted()
            }
        }
        c.start()
        clip = c
    }

    override fun stop() {
        clip?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.close() } catch (_: Exception) {}
        }
        clip = null
    }

    override val isPlaying: Boolean
        get() = clip?.isRunning == true
}
