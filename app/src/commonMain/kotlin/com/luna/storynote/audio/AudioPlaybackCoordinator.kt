package com.luna.storynote.audio

import com.luna.storynote.PlatformAudioPlayer
import com.luna.storynote.PlatformEnvironment
import com.luna.storynote.model.Clause
import com.luna.storynote.model.CollectedWordCard
import com.luna.storynote.model.StoryBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AudioPlaybackCoordinator(
    private val env: PlatformEnvironment,
    private val scope: CoroutineScope,
    private val onStateChanged: (playingClauseId: String?, isFullBookPlaying: Boolean, targetPage: Int?) -> Unit,
    private val getBookById: (String) -> StoryBook?,
    private val isStillFullBookPlaying: () -> Boolean,
    private val getPlayingClauseId: () -> String?,
) {
    @Volatile
    private var audioPlayer: PlatformAudioPlayer? = null
    private val playbackGeneration = AtomicLong(0L)

    fun playBookTitleVoice(book: StoryBook) {
        val titleWav = book.titleAudioFile()
        if (titleWav != null && titleWav.isFile && titleWav.canRead() && titleWav.length() > 44L) {
            stop()
            val gen = playbackGeneration.get()
            startRangePlayback(titleWav, 0L, -1L) {
                scope.launch {
                    if (playbackGeneration.get() == gen) stop()
                }
            }
        } else {
            env.speakStandardWord(book.title)
        }
    }

    fun playSingleClause(book: StoryBook, clause: Clause) {
        stop()
        val resolved = book.resolveAudio(clause)
        if (resolved == null) {
            env.speakStandardWord(clause.text)
            return
        }
        val (wav, span) = resolved
        val invalidSpan = span.endMs in 1L..(span.startMs + 5L)
        if (!wav.isFile || !wav.canRead() || wav.length() <= 44L || invalidSpan ||
            WavAudioFiles.isRangeNearSilent(wav, span.startMs, span.endMs)
        ) {
            env.speakStandardWord(clause.text)
            return
        }
        val gen = playbackGeneration.get()
        onStateChanged(clause.id, false, null)
        val started = startRangePlayback(wav, span.startMs, span.endMs) {
            scope.launch {
                if (playbackGeneration.get() == gen &&
                    getPlayingClauseId() == clause.id &&
                    !isStillFullBookPlaying()
                ) {
                    stop()
                }
            }
        }
        if (!started) {
            env.speakStandardWord(clause.text)
        }
    }

    fun playBookFromClauseIndex(bookId: String, index: Int) {
        val book = getBookById(bookId) ?: run {
            stop()
            return
        }
        if (index !in book.clauses.indices) {
            stop()
            return
        }
        val clause = book.clauses[index]
        val targetPage = book.pageIndexOfClause(clause.id)

        stop()
        val gen = playbackGeneration.get()
        onStateChanged(clause.id, true, targetPage)

        val resolved = book.resolveAudio(clause)
        if (resolved != null) {
            val (wav, span) = resolved
            startRangePlayback(wav, span.startMs, span.endMs) {
                scope.launch {
                    if (playbackGeneration.get() == gen && isStillFullBookPlaying()) {
                        delay(180L)
                        if (playbackGeneration.get() == gen && isStillFullBookPlaying()) {
                            playBookFromClauseIndex(bookId, index + 1)
                        }
                    }
                }
            }
        } else {
            env.speakStandardWord(clause.text)
            scope.launch {
                delay((clause.text.length * 260L).coerceIn(1200L, 4500L))
                if (playbackGeneration.get() == gen && isStillFullBookPlaying()) {
                    playBookFromClauseIndex(bookId, index + 1)
                }
            }
        }
    }

    fun playCollectedWordExample(card: CollectedWordCard) {
        stop()
        val path = card.takeAudioPath.trim()
        val f = if (path.isNotEmpty()) File(path) else null
        val endMs = if (card.takeEndMs > card.takeStartMs) card.takeEndMs else -1L
        val invalidSpan = card.takeEndMs in 1L..(card.takeStartMs + 5L)
        if (f == null || !f.isFile || !f.canRead() || f.length() <= 44L || invalidSpan ||
            WavAudioFiles.isRangeNearSilent(f, card.takeStartMs, endMs)
        ) {
            env.speakStandardWord(card.word)
            return
        }
        val gen = playbackGeneration.get()
        val started = startRangePlayback(f, card.takeStartMs, endMs) {
            scope.launch {
                if (playbackGeneration.get() == gen) stop()
            }
        }
        if (!started) {
            env.speakStandardWord(card.word)
        }
    }

    fun playWavRange(wavFile: File, startMs: Long, endMs: Long) {
        stop()
        val gen = playbackGeneration.get()
        startRangePlayback(wavFile, startMs, endMs) {
            scope.launch {
                if (playbackGeneration.get() == gen) stop()
            }
        }
    }

    fun stop() {
        playbackGeneration.incrementAndGet()
        audioPlayer?.stop()
        audioPlayer = null
        onStateChanged(null, false, null)
    }

    private fun startRangePlayback(
        wav: File,
        startMs: Long,
        endMs: Long,
        onCompleted: () -> Unit,
    ): Boolean {
        if (!wav.isFile || !wav.canRead() || wav.length() <= 44L) {
            stop()
            return false
        }
        val completed = AtomicBoolean(false)
        return try {
            val player = env.createAudioPlayer()
            audioPlayer = player
            player.playRange(wav, startMs, endMs) {
                if (completed.compareAndSet(false, true)) {
                    onCompleted()
                }
            }
            if (!player.isPlaying && !completed.get()) {
                stop()
                false
            } else {
                true
            }
        } catch (_: Throwable) {
            stop()
            false
        }
    }
}
