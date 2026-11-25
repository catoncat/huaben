package com.luna.storynote.e2e.fixtures

import com.luna.storynote.DesktopPlatformEnvironment
import com.luna.storynote.PlatformAudioPlayer
import com.luna.storynote.PlatformEnvironment
import com.luna.storynote.PlatformPcmMic
import com.luna.storynote.audio.FrontierProtocol
import com.luna.storynote.audio.WavFileStreamWriter
import com.luna.storynote.model.StoryBook
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

data class WavPlayRecord(
    val file: File,
    val startMs: Long,
    val endMs: Long,
    val timestampMs: Long = System.currentTimeMillis(),
)

class FakePcmMic : PlatformPcmMic {
    @Volatile
    var isStarted: Boolean = false
        private set

    @Volatile
    var isStopped: Boolean = false
        private set

    @Volatile
    private var chunkConsumer: ((ByteArray, Int) -> Unit)? = null

    val pushedByteCount: Long
        get() = _pushedBytes

    @Volatile
    private var _pushedBytes: Long = 0L

    override fun start(onPcmChunk: (bytes: ByteArray, length: Int) -> Unit) {
        chunkConsumer = onPcmChunk
        isStarted = true
        isStopped = false
    }

    override fun stop() {
        isStarted = false
        isStopped = true
        chunkConsumer = null
    }

    fun pushPcm(bytes: ByteArray, length: Int = bytes.size) {
        val consumer = chunkConsumer ?: return
        if (length <= 0) return
        _pushedBytes += length
        consumer(bytes.copyOf(length), length)
    }

    fun pushToneMs(
        durationMs: Int,
        freqHz: Double = 440.0,
        amplitude: Double = 0.35,
        chunkMs: Int = 50,
    ) {
        val pcm = FakePlatformEnvironment.synthesizeSinePcm16(durationMs, freqHz, amplitude)
        pushChunked(pcm, chunkMs)
    }

    fun pushSilenceMs(durationMs: Int, chunkMs: Int = 50) {
        val sampleCount = (FrontierProtocol.SAMPLE_RATE * durationMs) / 1000
        if (sampleCount <= 0) return
        pushChunked(ByteArray(sampleCount * 2), chunkMs)
    }

    private fun pushChunked(pcm: ByteArray, chunkMs: Int) {
        val chunkBytes = ((FrontierProtocol.SAMPLE_RATE * chunkMs) / 1000 * 2).coerceAtLeast(320)
        var offset = 0
        while (offset < pcm.size && isStarted) {
            val len = minOf(chunkBytes, pcm.size - offset)
            pushPcm(pcm.copyOfRange(offset, offset + len), len)
            offset += len
        }
    }
}

class FakeAudioPlayer(
    private val base: PlatformAudioPlayer,
    var autoCompleteDelayMs: Long = 40L,
) : PlatformAudioPlayer by base {
    val playedCalls: MutableList<WavPlayRecord> = Collections.synchronizedList(mutableListOf())

    @Volatile
    private var playingFlag: Boolean = false

    @Volatile
    private var pendingCallback: (() -> Unit)? = null

    @Volatile
    private var pendingTask: ScheduledFuture<*>? = null

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "fake-audio-player").apply { isDaemon = true }
    }

    override val isPlaying: Boolean
        get() = playingFlag

    override fun play(wavFile: File, onCompleted: () -> Unit) {
        playRange(wavFile, 0L, -1L, onCompleted)
    }

    override fun playRange(wavFile: File, startMs: Long, endMs: Long, onCompleted: () -> Unit) {
        stop()
        playedCalls.add(WavPlayRecord(file = wavFile, startMs = startMs, endMs = endMs))
        playingFlag = true
        pendingCallback = onCompleted
        if (autoCompleteDelayMs >= 0L) {
            pendingTask = scheduler.schedule(
                { completeIfActive() },
                autoCompleteDelayMs,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    fun completeNow() {
        completeIfActive()
    }

    private fun completeIfActive() {
        val cb: (() -> Unit)?
        synchronized(this) {
            if (!playingFlag) return
            playingFlag = false
            pendingTask = null
            cb = pendingCallback
            pendingCallback = null
        }
        cb?.invoke()
    }

    override fun stop() {
        synchronized(this) {
            playingFlag = false
            pendingTask?.cancel(false)
            pendingTask = null
            pendingCallback = null
        }
    }
}

class FakePlatformEnvironment(
    val rootDir: File,
    private val base: DesktopPlatformEnvironment = DesktopPlatformEnvironment(
        storageRoot = File(rootDir, "storage").apply { mkdirs() },
        authCacheFile = File(rootDir, "doubao_auth.json"),
        privateConfigDir = File(rootDir, "config").apply { mkdirs() },
        piAgentDir = File(rootDir, ".pi/agent"),
    ),
) : PlatformEnvironment by base {
    val fakeConfigDir: File get() = base.privateConfigDir
    override val privateConfigDir: File get() = base.privateConfigDir
    val createdMics: MutableList<FakePcmMic> = Collections.synchronizedList(mutableListOf())
    val createdPlayers: MutableList<FakeAudioPlayer> = Collections.synchronizedList(mutableListOf())
    val spokenWords: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val exportedBookIds: MutableList<String> = Collections.synchronizedList(mutableListOf())

    var autoCompleteAudioDelayMs: Long = 40L

    override fun createPcmMic(): FakePcmMic =
        FakePcmMic().also { createdMics.add(it) }

    override fun createAudioPlayer(): FakeAudioPlayer =
        FakeAudioPlayer(base.createAudioPlayer(), autoCompleteDelayMs = autoCompleteAudioDelayMs)
            .also { createdPlayers.add(it) }

    override fun speakStandardWord(word: String) {
        if (word.isNotBlank()) {
            spokenWords.add(word)
        }
    }

    override fun exportBook(book: StoryBook) {
        exportedBookIds.add(book.id)
    }

    fun writeDeterministicWav(
        targetFile: File,
        durationMs: Int,
        freqHz: Double = 440.0,
    ): File {
        targetFile.parentFile?.mkdirs()
        val pcm = synthesizeSinePcm16(durationMs, freqHz, amplitude = 0.3)
        val header = WavFileStreamWriter.buildWavHeader(
            sampleRate = FrontierProtocol.SAMPLE_RATE,
            clampedDataBytes = pcm.size,
            riffSize = 36 + pcm.size,
        )
        FileOutputStream(targetFile).use { fos ->
            fos.write(header)
            fos.write(pcm)
            fos.fd.sync()
        }
        return targetFile
    }

    fun writeV3LegacyBook(
        bookId: String,
        title: String,
        sentences: List<Pair<String, Int>>,
        bookNumber: Int = 1,
    ): File {
        val booksDir = File(storageRoot, "books").apply { mkdirs() }
        val bookDir = File(booksDir, bookId).apply { mkdirs() }
        val clipsDir = File(bookDir, "clips").apply { mkdirs() }

        writeDeterministicWav(File(bookDir, "title.wav"), durationMs = 600, freqHz = 523.25)

        val sentArr = JSONArray()
        sentences.forEachIndexed { idx, (text, durMs) ->
            val sid = "s_$idx"
            val relAudio = "clips/$sid.wav"
            writeDeterministicWav(
                targetFile = File(clipsDir, "$sid.wav"),
                durationMs = durMs,
                freqHz = 330.0 + (idx * 40.0),
            )
            sentArr.put(
                JSONObject()
                    .put("id", sid)
                    .put("text", text)
                    .put("audio_file", relAudio)
                    .put("duration_sec", durMs / 1000.0)
                    .put("paragraph_break_after", false),
            )
        }

        val now = System.currentTimeMillis()
        val meta = JSONObject()
            .put("version", 3)
            .put("id", bookId)
            .put("book_number", bookNumber)
            .put("title", title)
            .put("title_audio_file", "title.wav")
            .put("cover_color_index", 0)
            .put("emblem_index", 0)
            .put("sentences", sentArr)
            .put("created_at_ms", now)
            .put("updated_at_ms", now)

        val bookJson = File(bookDir, "book.json")
        bookJson.writeText(meta.toString(2) + "\n", Charsets.UTF_8)
        return bookJson
    }

    companion object {
        fun synthesizeSinePcm16(
            durationMs: Int,
            freqHz: Double = 440.0,
            amplitude: Double = 0.35,
        ): ByteArray {
            val sampleRate = FrontierProtocol.SAMPLE_RATE
            val totalSamples = ((sampleRate.toLong() * durationMs.coerceAtLeast(0)) / 1000L).toInt()
            val out = ByteArray(totalSamples * 2)
            val amp = (amplitude.coerceIn(0.0, 1.0) * 32767.0)
            for (i in 0 until totalSamples) {
                val sample = (sin(2.0 * PI * freqHz * i / sampleRate) * amp).toInt().coerceIn(-32768, 32767)
                out[i * 2] = (sample and 0xFF).toByte()
                out[i * 2 + 1] = ((sample ushr 8) and 0xFF).toByte()
            }
            return out
        }
    }
}
