package com.luna.storynote

import com.luna.storynote.audio.AudioPlaybackCoordinator
import com.luna.storynote.audio.WavFileStreamWriter
import com.luna.storynote.model.AiGatewayConfig
import com.luna.storynote.model.AudioSpan
import com.luna.storynote.model.AudioTake
import com.luna.storynote.model.Clause
import com.luna.storynote.model.CollectedWordCard
import com.luna.storynote.model.StoryBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory

class AudioPlaybackAndPlayerTest {
    @Test
    fun desktopPlayerHandlesMissingDirectoryEmptyTruncatedCorruptAndValidWavsSafely() {
        val tmp = createTempDirectory("desktop-player-test-").toFile()
        try {
            val env = DesktopPlatformEnvironment(
                storageRoot = File(tmp, "storage").apply { mkdirs() },
                authCacheFile = File(tmp, "auth.json"),
                privateConfigDir = File(tmp, "cfg").apply { mkdirs() },
                piAgentDir = File(tmp, "pi_missing"),
            )
            val player = env.createAudioPlayer()

            val missing = File(tmp, "missing.wav")
            val dir = File(tmp, "dir_as_wav").apply { mkdirs() }
            val empty = File(tmp, "empty.wav").apply { writeBytes(ByteArray(0)) }
            val garbage = File(tmp, "corrupt.wav").apply {
                writeText("NOT_A_WAV_FILE_JUST_CORRUPT_GARBAGE_BYTES_OVER_44_BYTES_LONG_1234567890")
            }
            val truncated20 = File(tmp, "truncated.wav").apply {
                writeBytes(WavFileStreamWriter.buildWavHeader(16_000, 1600, 1636).copyOf(20))
            }
            val headerOnly44 = File(tmp, "header_only.wav").apply {
                writeBytes(WavFileStreamWriter.buildWavHeader(16_000, 0, 36))
            }

            for (bad in listOf(missing, dir, empty, garbage, truncated20, headerOnly44)) {
                player.playRange(bad, 0L, 500L) {}
                assertFalse("Expected isPlaying == false for ${bad.name}", player.isPlaying)
                player.play(bad) {}
                assertFalse("Expected isPlaying == false after play() for ${bad.name}", player.isPlaying)
            }

            val validWav = File(tmp, "valid_silent.wav")
            WavFileStreamWriter(validWav).apply {
                appendSilenceMs(120)
                close()
            }
            val latch = CountDownLatch(1)
            val completedCount = AtomicInteger(0)
            player.playRange(validWav, 0L, 100L) {
                completedCount.incrementAndGet()
                latch.countDown()
            }
            assertTrue("Expected valid 100ms WAV playback to complete", latch.await(3, TimeUnit.SECONDS))
            Thread.sleep(40L)
            assertEquals(1, completedCount.get())
            player.stop()
            assertFalse(player.isPlaying)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun coordinatorResetsStateSafelyAcrossAllCallSitesOnCorruptAndMissingFiles() {
        val tmp = createTempDirectory("coord-corrupt-test-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val env = DesktopPlatformEnvironment(
                storageRoot = File(tmp, "storage").apply { mkdirs() },
                authCacheFile = File(tmp, "auth.json"),
                privateConfigDir = File(tmp, "cfg").apply { mkdirs() },
                piAgentDir = File(tmp, "pi_missing"),
            )
            val bookDir = File(tmp, "book").apply { mkdirs() }
            val corruptWav = File(bookDir, "takes/corrupt.wav").apply {
                parentFile.mkdirs()
                writeText("CORRUPT_AUDIO_PAYLOAD_LONG_ENOUGH_TO_EXCEED_44_BYTES_HEADER_SIZE")
            }
            val corruptTitleWav = File(bookDir, "title.wav").apply {
                writeText("CORRUPT_TITLE_PAYLOAD_LONG_ENOUGH_TO_EXCEED_44_BYTES_HEADER_SIZE")
            }

            val clauses = (0 until 50).map { idx ->
                Clause(
                    id = "c_$idx",
                    text = "小恐龙吃草莓。",
                    audioSpan = AudioSpan(takeId = "t_corrupt", startMs = 0L, endMs = 500L),
                )
            }
            val book = StoryBook(
                id = "b_corrupt",
                bookNumber = 1,
                title = "小恐龙",
                titleAudioFileName = "title.wav",
                coverColorIndex = 0,
                emblemIndex = 0,
                clauses = clauses,
                takes = listOf(AudioTake("t_corrupt", "takes/corrupt.wav", 500L, 1L)),
                createdAtMs = 1L,
                updatedAtMs = 1L,
                bookDir = bookDir,
            )

            var playingClauseId: String? = null
            var isFullBookPlaying = false
            var targetPage: Int? = null
            val coordinator = AudioPlaybackCoordinator(
                env = env,
                scope = scope,
                onStateChanged = { cId, full, page ->
                    playingClauseId = cId
                    isFullBookPlaying = full
                    targetPage = page
                },
                getBookById = { id -> if (id == book.id) book else null },
                isStillFullBookPlaying = { isFullBookPlaying },
                getPlayingClauseId = { playingClauseId },
            )

            coordinator.playBookTitleVoice(book)
            assertNull(playingClauseId)
            assertFalse(isFullBookPlaying)
            assertNull(targetPage)

            coordinator.playSingleClause(book, clauses.first())
            assertNull(playingClauseId)
            assertFalse(isFullBookPlaying)
            assertNull(targetPage)

            // Corrupt audio during full-book read-along cleanly falls back to TTS instead of skipping/crashing
            coordinator.playBookFromClauseIndex(book.id, 0)
            assertEquals(clauses.first().id, playingClauseId)
            assertTrue(isFullBookPlaying)
            coordinator.stop()
            assertNull(playingClauseId)
            assertFalse(isFullBookPlaying)
            assertNull(targetPage)

            val badCard = CollectedWordCard(
                word = "草莓",
                pinyins = listOf("cǎo", "méi"),
                bookId = book.id,
                clauseId = "c_0",
                clauseText = "小恐龙吃草莓。",
                takeAudioPath = corruptWav.absolutePath,
                takeStartMs = 0L,
                takeEndMs = 500L,
                collectedAtMs = 1L,
            )
            coordinator.playCollectedWordExample(badCard)
            assertNull(playingClauseId)
            assertFalse(isFullBookPlaying)
            assertNull(targetPage)

            coordinator.playWavRange(corruptWav, 0L, 500L)
            assertNull(playingClauseId)
            assertFalse(isFullBookPlaying)
            assertNull(targetPage)

            // Also verify missing/empty files do not leak or hang state.
            coordinator.playWavRange(File(bookDir, "missing.wav"), 0L, 500L)
            assertNull(playingClauseId)
            assertFalse(isFullBookPlaying)
            assertFalse(corruptTitleWav.length() <= 44L)
        } finally {
            scope.cancel()
            tmp.deleteRecursively()
        }
    }

    @Test
    fun coordinatorCatchesExceptionsFromThrowingPlayerAtAllCallSites() {
        val tmp = createTempDirectory("coord-throwing-test-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val throwingEnv = object : PlatformEnvironment {
                override val storageRoot: File = tmp
                override val authCacheFile: File = File(tmp, "auth.json")
                override val privateConfigDir: File = tmp
                override fun createPcmMic(): PlatformPcmMic = throw UnsupportedOperationException()
                override fun createAudioPlayer(): PlatformAudioPlayer = object : PlatformAudioPlayer {
                    override fun playRange(wavFile: File, startMs: Long, endMs: Long, onCompleted: () -> Unit) {
                        throw IllegalStateException("Simulated audio backend failure")
                    }
                    override fun stop() = Unit
                    override val isPlaying: Boolean = false
                }
                override fun speakStandardWord(word: String) = Unit
                override fun stopSpeaking() = Unit
                override fun loadDefaultAiConfig(): AiGatewayConfig = AiGatewayConfig()
                override fun openStrokeAssetStream(): InputStream? = null
                override fun setKeepScreenAwake(keepAwake: Boolean) = Unit
                override fun exportBook(book: StoryBook) = Unit
            }

            val bookDir = File(tmp, "book").apply { mkdirs() }
            val validWav = File(bookDir, "takes/valid.wav").apply {
                parentFile.mkdirs()
                WavFileStreamWriter(this).apply {
                    appendSilenceMs(200)
                    close()
                }
            }
            val titleWav = File(bookDir, "title.wav").apply {
                WavFileStreamWriter(this).apply {
                    appendSilenceMs(200)
                    close()
                }
            }
            assertTrue(titleWav.length() > 44L)

            val clause = Clause(
                id = "c1",
                text = "小恐龙笑了。",
                audioSpan = AudioSpan("t1", 0L, 200L),
            )
            val book = StoryBook(
                id = "b_throw",
                bookNumber = 1,
                title = "恐龙",
                titleAudioFileName = "title.wav",
                coverColorIndex = 0,
                emblemIndex = 0,
                clauses = listOf(clause),
                takes = listOf(AudioTake("t1", "takes/valid.wav", 200L, 1L)),
                createdAtMs = 1L,
                updatedAtMs = 1L,
                bookDir = bookDir,
            )

            var playingClauseId: String? = null
            var isFullBookPlaying = false
            var targetPage: Int? = null
            val coordinator = AudioPlaybackCoordinator(
                env = throwingEnv,
                scope = scope,
                onStateChanged = { cId, full, page ->
                    playingClauseId = cId
                    isFullBookPlaying = full
                    targetPage = page
                },
                getBookById = { book },
                isStillFullBookPlaying = { isFullBookPlaying },
                getPlayingClauseId = { playingClauseId },
            )

            coordinator.playBookTitleVoice(book)
            assertNull(playingClauseId); assertFalse(isFullBookPlaying); assertNull(targetPage)

            coordinator.playSingleClause(book, clause)
            assertNull(playingClauseId); assertFalse(isFullBookPlaying); assertNull(targetPage)

            coordinator.playBookFromClauseIndex(book.id, 0)
            assertEquals(clause.id, playingClauseId)
            assertTrue(isFullBookPlaying)
            coordinator.stop()
            assertNull(playingClauseId); assertFalse(isFullBookPlaying); assertNull(targetPage)

            coordinator.playCollectedWordExample(
                CollectedWordCard("恐龙", listOf("kǒng", "lóng"), book.id, "c1", clause.text, validWav.absolutePath, 0L, 200L, 1L),
            )
            assertNull(playingClauseId); assertFalse(isFullBookPlaying); assertNull(targetPage)

            coordinator.playWavRange(validWav, 0L, 200L)
            assertNull(playingClauseId); assertFalse(isFullBookPlaying); assertNull(targetPage)
        } finally {
            scope.cancel()
            tmp.deleteRecursively()
        }
    }

    @Test
    fun desktopPlayerNearCompletionPlayRangeAndStopDoesNotDeadlock() {
        val tmp = createTempDirectory("desktop-near-completion-test-").toFile()
        try {
            val env = DesktopPlatformEnvironment(
                storageRoot = File(tmp, "storage").apply { mkdirs() },
                authCacheFile = File(tmp, "auth.json"),
                privateConfigDir = File(tmp, "cfg").apply { mkdirs() },
                piAgentDir = File(tmp, "pi_missing"),
            )
            val valid100 = File(tmp, "valid_100.wav")
            WavFileStreamWriter(valid100).apply {
                appendSilenceMs(100)
                close()
            }
            val player = env.createAudioPlayer()
            val error = AtomicReference<Throwable?>(null)
            val completed = AtomicInteger(0)
            val runner = Thread(
                {
                    try {
                        for (i in 0 until 40) {
                            player.playRange(valid100, 0L, 30L) {}
                            Thread.sleep((i % 5 + 26).toLong())
                            player.stop()
                            completed.incrementAndGet()
                        }
                    } catch (t: Throwable) {
                        error.set(t)
                    }
                },
                "near-completion-stress",
            ).apply {
                isDaemon = true
                start()
            }
            runner.join(30_000L)
            assertFalse("Expected near-completion stress thread not to deadlock", runner.isAlive)
            assertEquals("Expected all 40 near-completion iterations to finish", 40, completed.get())
            assertNull("Unexpected exception during near-completion stress", error.get())
            assertFalse("Expected player stopped after stress loop", player.isPlaying)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun desktopPlayerConcurrentTwoThreadPlayRangeAndStopCompletesCleanly() {
        val tmp = createTempDirectory("desktop-concurrent-test-").toFile()
        try {
            val env = DesktopPlatformEnvironment(
                storageRoot = File(tmp, "storage").apply { mkdirs() },
                authCacheFile = File(tmp, "auth.json"),
                privateConfigDir = File(tmp, "cfg").apply { mkdirs() },
                piAgentDir = File(tmp, "pi_missing"),
            )
            val valid100 = File(tmp, "valid_100.wav")
            WavFileStreamWriter(valid100).apply {
                appendSilenceMs(100)
                close()
            }
            val player = env.createAudioPlayer()
            val error1 = AtomicReference<Throwable?>(null)
            val error2 = AtomicReference<Throwable?>(null)
            val done1 = AtomicInteger(0)
            val done2 = AtomicInteger(0)
            val startGate = CountDownLatch(1)

            fun spawnWorker(
                name: String,
                offsetMs: Long,
                errRef: AtomicReference<Throwable?>,
                doneCount: AtomicInteger,
            ): Thread =
                Thread(
                    {
                        try {
                            startGate.await(2, TimeUnit.SECONDS)
                            for (i in 0 until 20) {
                                player.playRange(valid100, 0L, 30L + ((i + offsetMs) % 25L)) {}
                                Thread.sleep((i % 5 + 1).toLong())
                                player.stop()
                                doneCount.incrementAndGet()
                            }
                        } catch (t: Throwable) {
                            errRef.set(t)
                        }
                    },
                    name,
                ).apply {
                    isDaemon = true
                    start()
                }

            val t1 = spawnWorker("concurrent-player-1", 0L, error1, done1)
            val t2 = spawnWorker("concurrent-player-2", 7L, error2, done2)
            startGate.countDown()
            t1.join(30_000L)
            t2.join(30_000L)

            assertFalse("Expected thread 1 to finish without deadlock", t1.isAlive)
            assertFalse("Expected thread 2 to finish without deadlock", t2.isAlive)
            assertEquals("Expected thread 1 to finish all 20 iterations", 20, done1.get())
            assertEquals("Expected thread 2 to finish all 20 iterations", 20, done2.get())
            assertNull("Unexpected error on thread 1", error1.get())
            assertNull("Unexpected error on thread 2", error2.get())
            player.stop()
            assertFalse("Expected player stopped after concurrent stress", player.isPlaying)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun desktopPlayerRapidConsecutivePlayRangeIsolatesCallbacksAndPlayingState() {
        val tmp = createTempDirectory("desktop-rapid-swap-test-").toFile()
        try {
            val env = DesktopPlatformEnvironment(
                storageRoot = File(tmp, "storage").apply { mkdirs() },
                authCacheFile = File(tmp, "auth.json"),
                privateConfigDir = File(tmp, "cfg").apply { mkdirs() },
                piAgentDir = File(tmp, "pi_missing"),
            )
            val valid100 = File(tmp, "valid_100.wav")
            WavFileStreamWriter(valid100).apply {
                appendSilenceMs(100)
                close()
            }
            val player = env.createAudioPlayer()
            val firstCompleted = AtomicInteger(0)
            val secondCompleted = AtomicInteger(0)
            val secondLatch = CountDownLatch(1)

            player.playRange(valid100, 0L, 80L) {
                firstCompleted.incrementAndGet()
            }
            player.playRange(valid100, 0L, 60L) {
                secondCompleted.incrementAndGet()
                secondLatch.countDown()
            }
            assertTrue("Expected second playRange to report isPlaying immediately", player.isPlaying)

            Thread.sleep(15L)
            assertTrue("Expected first STOP event not to clobber second isPlaying", player.isPlaying)
            assertEquals("Expected canceled first playback not to fire onCompleted", 0, firstCompleted.get())

            assertTrue("Expected second playRange to complete", secondLatch.await(3, TimeUnit.SECONDS))
            Thread.sleep(40L)
            assertEquals(0, firstCompleted.get())
            assertEquals(1, secondCompleted.get())
            player.stop()
            assertFalse(player.isPlaying)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
