package com.luna.storynote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.luna.storynote.ui.StoryStudioRoot
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.io.path.createTempDirectory

fun main(args: Array<String>) {
    val idx = args.indexOf("--render-preview")
    if (idx >= 0 && idx + 1 < args.size) {
        renderVerificationSuite(File(args[idx + 1]))
        return
    }

    val env = DesktopPlatformEnvironment()
    seedShowcaseBookIfEmpty(env)
    val controller = BookStudioController(env)

    application {
        val windowState = rememberWindowState(size = DpSize(1180.dp, 840.dp))
        Window(
            onCloseRequest = {
                controller.dispose()
                exitApplication()
            },
            state = windowState,
            title = "小作家故事本",
        ) {
            var formFactor by remember { mutableIntStateOf(0) } // 0=平板横屏双页, 1=平板竖屏单页, 2=手机
            val uiState by controller.uiState.collectAsState()

            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFDAD1C2)),
                ) {
                    // Subtle dark studio switcher bar (Desktop preview only — not present in APK)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF221E1A))
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("平板对开 1140", "平板单页 760", "手机 400").forEachIndexed { i, label ->
                                Surface(
                                    shape = CircleShape,
                                    color = if (formFactor == i) Color(0xFFD44A2E) else Color(0xFF3A332C),
                                    modifier = Modifier.clickable { formFactor = i },
                                ) {
                                    Text(
                                        text = label,
                                        color = Color.White,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }

                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val mod = when (formFactor) {
                            1 -> Modifier
                                .padding(12.dp)
                                .width(760.dp)
                                .height(980.dp)
                                .shadow(12.dp, RoundedCornerShape(20.dp))
                                .clip(RoundedCornerShape(20.dp))
                            2 -> Modifier
                                .padding(12.dp)
                                .width(400.dp)
                                .height(850.dp)
                                .shadow(12.dp, RoundedCornerShape(24.dp))
                                .clip(RoundedCornerShape(24.dp))
                            else -> Modifier.fillMaxSize()
                        }
                        StoryStudioRoot(uiState = uiState, controller = controller, modifier = mod)
                    }
                }
            }
        }
    }
}

private fun seedShowcaseBookIfEmpty(env: DesktopPlatformEnvironment) {
    val repo = StoryBookRepository(env.storageRoot)
    if (repo.listBooks().isNotEmpty()) return

    val b = repo.createBlankBook()
    val demoSentences = listOf(
        "从前有一只蓝色的小恐龙，他住在一座会喷棉花糖的火山上。",
        "他慢慢长大了，每天早上最喜欢吃云朵味的冰淇淋。",
        "有一天晚上，小恐龙看着天上的月亮，觉得月亮像一块圆圆的大饼干。",
        "突然，一艘银色的飞船落在了家门口！",
        "飞船里跳出一只聪明的小狐狸，拿着星星地图来找他一起去探险。",
        "他们坐上飞船穿过彩虹，玩累了才回到温暖的山洞里睡觉。",
    )
    var afterId: String? = null
    for (s in demoSentences) {
        val wav = repo.newStagingWav()
        val w = WavFileStreamWriter(wav)
        w.appendSilenceMs(1800)
        w.close()
        val (_, clip) = repo.insertSentenceClip(b.id, afterId, s, wav, 1.8)
        afterId = clip.id
    }
    repo.updateBookTitleAndCover(b.id, newTitle = "蓝色小恐龙与飞船", coverColorIndex = 0, emblemIndex = 1)
    val fullBook = repo.getBook(b.id)!!
    repo.toggleCollectWord("恐龙", listOf("kǒng", "lóng"), fullBook, fullBook.sentences[0])
    repo.toggleCollectWord("飞船", listOf("fēi", "chuán"), fullBook, fullBook.sentences[3])
}

private fun renderVerificationSuite(outDir: File) {
    outDir.mkdirs()
    val tmp = createTempDirectory("studio-v3-shots-").toFile()
    try {
        val env = DesktopPlatformEnvironment(storageRoot = tmp)
        HanziStrokeRepository.ensureLoaded { env.openStrokeAssetStream() }
        seedShowcaseBookIfEmpty(env)
        val controller = BookStudioController(env)
        val books = controller.repo.listBooks()
        val words = controller.repo.listCollectedWords()
        val book = books.first()
        val firstSent = book.sentences.first()

        // 1. Hardcover Bookshelf
        val shelfState = StudioUiState(
            destination = StudioDestination.SHELF,
            books = books,
            collectedWords = words,
        )
        renderShot(File(outDir, "01-bookshelf.png"), 1140, 780) {
            MaterialTheme { StoryStudioRoot(shelfState, controller) }
        }

        // 2. Two-Page Open Hardcover Spread (Tablet Landscape) with active sentence edit capsule
        val spreadState = StudioUiState(
            destination = StudioDestination.BOOK,
            books = books,
            activeBookId = book.id,
            currentPageIndex = 0,
            selectedSentenceId = book.sentences[1].id,
            cursorAfterSentenceId = book.sentences.last().id,
            collectedWords = words,
        )
        renderShot(File(outDir, "02-open-book-spread.png"), 1180, 820) {
            MaterialTheme { StoryStudioRoot(spreadState, controller) }
        }

        // 3. Single-Page Portrait / Phone View with Live Speaking Draft at Ink Cursor
        val phoneLiveState = StudioUiState(
            destination = StudioDestination.BOOK,
            books = books,
            activeBookId = book.id,
            currentPageIndex = 0,
            cursorAfterSentenceId = book.sentences[1].id,
            activeMicIntent = MicIntent.WriteAtCursor,
            liveDraftText = "第二天太阳出来了",
            voiceEnergy = 0.55f,
            collectedWords = words,
        )
        renderShot(File(outDir, "03-phone-live-writing.png"), 412, 900) {
            MaterialTheme { StoryStudioRoot(phoneLiveState, controller) }
        }

        // 4. TianZiGe Stroke & Spelling Card (`恐龙`)
        val span = LiteracyEngine.findWordAt(firstSent.text, firstSent.text.indexOf('恐'))!!
        val wordCardState = spreadState.copy(
            selectedSentenceId = null,
            inspectedWord = ActiveWordInspection(
                sentenceId = firstSent.id,
                span = span,
                homophones = LiteracyEngine.suggestHomophones(span.word, span.pinyins),
            ),
        )
        renderShot(File(outDir, "04-tianzige-word-card.png"), 1140, 780) {
            MaterialTheme { StoryStudioRoot(wordCardState, controller) }
        }

        // 5. Word Box Flashcards
        val wordBoxState = StudioUiState(
            destination = StudioDestination.WORD_BOX,
            books = books,
            collectedWords = words,
        )
        renderShot(File(outDir, "05-word-box.png"), 1024, 720) {
            MaterialTheme { StoryStudioRoot(wordBoxState, controller) }
        }

        controller.dispose()
    } finally {
        tmp.deleteRecursively()
    }
}

private fun renderShot(
    file: File,
    wDp: Int,
    hDp: Int,
    scale: Float = 2f,
    content: @androidx.compose.runtime.Composable () -> Unit,
) {
    val scene = ImageComposeScene((wDp * scale).toInt(), (hDp * scale).toInt(), Density(scale))
    try {
        scene.setContent(content)
        // Step through 16 virtual frames (0s -> 2.4s) so LaunchedEffect + Animatable renders mid-calligraphy strokes!
        var img = scene.render(0L)
        for (step in 1..15) {
            img = scene.render(step * 160_000_000L)
        }
        val bytes = img.encodeToData(EncodedImageFormat.PNG)?.bytes
            ?: throw IllegalStateException("PNG encode failed")
        file.writeBytes(bytes)
    } finally {
        scene.close()
    }
}
