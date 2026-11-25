package com.luna.storynote

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import java.io.File

interface PlatformMicCapture {
    fun start(
        outputWavFile: File,
        onPcmChunk: (bytes: ByteArray, length: Int) -> Unit,
        onProgress: (elapsedSec: Double, voiceLevel: Float) -> Unit,
    )

    fun setPaused(paused: Boolean)

    fun stop(): Double
}

interface PlatformAudioPlayer {
    fun play(wavFile: File, onCompleted: () -> Unit)
    fun pause()
    fun resume()
    fun seekToSec(sec: Float)
    fun stop()
    val isPlaying: Boolean
    val currentPositionSec: Float
    val durationSec: Float
}

interface PlatformEnvironment {
    val storageRoot: File
    val authCacheFile: File
    val shareActionLabel: String
    fun createMicCapture(): PlatformMicCapture
    fun createAudioPlayer(): PlatformAudioPlayer
    fun setKeepScreenAwake(keepAwake: Boolean)
    fun shareOrRevealNote(note: StoryNote)
}

@Composable
expect fun rememberPlatformBookFontFamily(): FontFamily
