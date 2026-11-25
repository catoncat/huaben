package com.luna.storynote

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import java.io.File
import java.io.InputStream

interface PlatformPcmMic {
    fun start(onPcmChunk: (bytes: ByteArray, length: Int) -> Unit)
    fun stop()
}

interface PlatformAudioPlayer {
    fun play(wavFile: File, onCompleted: () -> Unit)
    fun stop()
    val isPlaying: Boolean
}

interface PlatformEnvironment {
    val storageRoot: File
    val authCacheFile: File
    fun createPcmMic(): PlatformPcmMic
    fun createAudioPlayer(): PlatformAudioPlayer
    fun speakStandardWord(word: String)
    fun openStrokeAssetStream(): InputStream?
    fun setKeepScreenAwake(keepAwake: Boolean)
    fun exportBook(book: StoryBook)
}

@Composable
expect fun rememberPlatformBookFontFamily(): FontFamily
