package com.luna.storynote

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import com.luna.storynote.model.AiGatewayConfig
import com.luna.storynote.model.StoryBook
import java.io.File
import java.io.InputStream

interface PlatformPcmMic {
    fun start(onPcmChunk: (bytes: ByteArray, length: Int) -> Unit)
    fun stop()
}

interface PlatformAudioPlayer {
    fun playRange(wavFile: File, startMs: Long, endMs: Long, onCompleted: () -> Unit)

    fun play(wavFile: File, onCompleted: () -> Unit) {
        playRange(wavFile, 0L, -1L, onCompleted)
    }

    fun stop()
    val isPlaying: Boolean
}

interface PlatformEnvironment {
    val storageRoot: File
    val authCacheFile: File
    val privateConfigDir: File

    fun createPcmMic(): PlatformPcmMic
    fun createAudioPlayer(): PlatformAudioPlayer
    fun speakStandardWord(word: String)
    fun stopSpeaking()
    fun loadDefaultAiConfig(): AiGatewayConfig
    fun openStrokeAssetStream(): InputStream?
    fun setKeepScreenAwake(keepAwake: Boolean)
    fun exportBook(book: StoryBook)
}

@Composable
expect fun rememberPlatformBookFontFamily(): FontFamily

expect fun decodeFileToImageBitmap(file: File): ImageBitmap?
