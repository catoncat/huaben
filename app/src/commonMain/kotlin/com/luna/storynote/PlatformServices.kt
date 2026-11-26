package com.luna.storynote

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import com.luna.storynote.model.AiGatewayConfig
import com.luna.storynote.model.StoryBook
import com.luna.storynote.storage.BookExportMode
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
    fun loadDefaultGithubToken(): String = ""
    fun openStrokeAssetStream(): InputStream?
    fun setKeepScreenAwake(keepAwake: Boolean)
    fun exportBook(book: StoryBook) = exportBook(book, BookExportMode.FULL_BUNDLE).let {}
    fun exportBook(book: StoryBook, mode: BookExportMode): String = ""
    fun installUpdateApk(apkFile: File): String? = null
    fun openExternalUrl(url: String) = Unit
}

@Composable
expect fun rememberPlatformBookFontFamily(): FontFamily

expect fun decodeFileToImageBitmap(file: File): ImageBitmap?
