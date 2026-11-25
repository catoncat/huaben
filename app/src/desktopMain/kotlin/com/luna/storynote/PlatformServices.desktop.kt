package com.luna.storynote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import java.awt.Desktop
import java.io.File
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
    val candidates = listOf(
        "/System/Library/Fonts/Supplemental/Songti.ttc",
        "/System/Library/Fonts/PingFang.ttc",
    )
    for (path in candidates) {
        val file = File(path)
        if (file.isFile) {
            try {
                return FontFamily(Font(file))
            } catch (_: Exception) {
            }
        }
    }
    return FontFamily.Serif
}

class DesktopPlatformEnvironment(
    override val storageRoot: File = File(System.getProperty("user.home"), ".local/share/story-note").apply { mkdirs() },
    override val authCacheFile: File = File(System.getProperty("user.home"), ".config/story-note/doubao_auth.json"),
) : PlatformEnvironment {
    override val shareActionLabel: String = "在访达中打开文件"

    override fun createMicCapture(): PlatformMicCapture = DesktopMicCapture()

    override fun createAudioPlayer(): PlatformAudioPlayer = DesktopClipAudioPlayer()

    override fun setKeepScreenAwake(keepAwake: Boolean) {
        // Desktop preview window stays open naturally
    }

    override fun shareOrRevealNote(note: StoryNote) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(note.audioFile.parentFile)
            }
        } catch (_: Exception) {
        }
    }
}

private class DesktopMicCapture : PlatformMicCapture {
    @Volatile
    private var running = false
    @Volatile
    private var paused = false
    @Volatile
    private var insertBreath = false

    private var line: TargetDataLine? = null
    private var thread: Thread? = null
    private var writer: WavFileStreamWriter? = null

    override fun start(
        outputWavFile: File,
        onPcmChunk: (bytes: ByteArray, length: Int) -> Unit,
        onProgress: (elapsedSec: Double, voiceLevel: Float) -> Unit,
    ) {
        val format = AudioFormat(FrontierProtocol.SAMPLE_RATE.toFloat(), 16, 1, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) {
            throw IllegalStateException("当前电脑麦克风不支持 16kHz 音频采集")
        }
        val targetLine = (AudioSystem.getLine(info) as TargetDataLine).apply {
            open(format, 6400)
            start()
        }
        line = targetLine
        val wav = WavFileStreamWriter(outputWavFile)
        writer = wav
        running = true
        paused = false

        thread = Thread(
            {
                val buf = ByteArray(3200)
                try {
                    while (running) {
                        val n = targetLine.read(buf, 0, buf.size)
                        if (n > 0 && !paused) {
                            if (insertBreath) {
                                insertBreath = false
                                wav.appendSilenceMs(350)
                            }
                            wav.appendPcm(buf, n)
                            onPcmChunk(buf.copyOf(n), n)
                            onProgress(wav.durationSec, WavFileStreamWriter.computeNormalizedRms(buf, n))
                        }
                    }
                } finally {
                    try { targetLine.stop() } catch (_: Exception) {}
                    try { targetLine.close() } catch (_: Exception) {}
                }
            },
            "desktop-story-mic",
        ).apply {
            isDaemon = true
            start()
        }
    }

    override fun setPaused(paused: Boolean) {
        if (this.paused && !paused) insertBreath = true
        this.paused = paused
    }

    override fun stop(): Double {
        running = false
        line?.stop()
        thread?.join(2000L)
        thread = null
        line = null
        val d = writer?.close() ?: 0.0
        writer = null
        return d
    }
}

private class DesktopClipAudioPlayer : PlatformAudioPlayer {
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

    override fun pause() {
        clip?.stop()
    }

    override fun resume() {
        clip?.start()
    }

    override fun seekToSec(sec: Float) {
        val c = clip ?: return
        val targetUs = (sec * 1_000_000f).toLong().coerceIn(0L, c.microsecondLength)
        c.microsecondPosition = targetUs
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

    override val currentPositionSec: Float
        get() = (clip?.microsecondPosition ?: 0L) / 1_000_000f

    override val durationSec: Float
        get() = (clip?.microsecondLength ?: 0L) / 1_000_000f
}
