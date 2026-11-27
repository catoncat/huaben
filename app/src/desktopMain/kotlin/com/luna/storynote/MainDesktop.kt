package com.luna.storynote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.luna.storynote.literacy.HanziStrokeRepository
import com.luna.storynote.literacy.LiteracyEngine
import com.luna.storynote.model.ActiveWordInspection
import com.luna.storynote.model.MicIntent
import com.luna.storynote.model.SoftAnnotation
import com.luna.storynote.model.StudioDestination
import com.luna.storynote.model.StudioUiState
import com.luna.storynote.storage.StoryBookRepository
import com.luna.storynote.ui.StoryStudioRoot
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color as SkColor
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Path as SkPath
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.io.path.createTempDirectory

private fun loadDesktopWindowIcon(): BitmapPainter? = try {
    val bytes = DesktopPlatformEnvironment::class.java.getResourceAsStream("/app_icon.png")?.use { it.readBytes() }
    bytes?.let { BitmapPainter(SkiaImage.makeFromEncoded(it).toComposeImageBitmap()) }
} catch (_: Throwable) {
    null
}

fun main(args: Array<String>) {
    val magpieIdx = args.indexOf("--verify-magpie")
    if (magpieIdx >= 0 && magpieIdx + 1 < args.size) {
        runMagpieLiveVerification(File(args[magpieIdx + 1]))
        return
    }
    val idx = args.indexOf("--render-preview")
    if (idx >= 0 && idx + 1 < args.size) {
        renderVerificationSuite(File(args[idx + 1]))
        return
    }

    val env = DesktopPlatformEnvironment()
    seedShowcaseBookIfEmpty(env)
    val controller = BookStudioController(env)

    application {
        val windowState = rememberWindowState(size = DpSize(1140.dp, 780.dp))
        val windowIcon = loadDesktopWindowIcon()
        Window(
            onCloseRequest = {
                controller.dispose()
                exitApplication()
            },
            state = windowState,
            title = "话本本",
            icon = windowIcon,
        ) {
            val uiState by controller.uiState.collectAsState()
            MaterialTheme {
                StoryStudioRoot(uiState = uiState, controller = controller)
            }
        }
    }
}

private fun seedShowcaseBookIfEmpty(env: DesktopPlatformEnvironment) {
    val repo = StoryBookRepository(env.storageRoot)
    if (repo.listBooks().isNotEmpty()) return

    val b = repo.createBlankBook()
    val demoSentences = listOf(
        "小恐龙慢慢的走出了房间，他往前走啊走，走啊走，终于来到了大森林里。",
        "大森林里的树木绿油油的，阳光穿过树叶洒在软软的草地上。",
        "突然，一只聪明的小狐狸拿着星星地图跳了出来，邀请小恐龙一起去河边探险。",
        "他们开心地分享着棉花糖和冰淇淋，玩累了才回到温暖的山洞里睡觉。",
    )
    var afterId: String? = null
    for (s in demoSentences) {
        val (_, clip) = repo.insertSentenceClip(b.id, afterId, s, tempWavFile = null, durationSec = 0.0)
        afterId = clip.id
    }
    repo.updateBookTitleAndCover(b.id, newTitle = "小恐龙来到了大森林里", coverColorIndex = 0, emblemIndex = 1)
    val fullBook = repo.getBook(b.id)!!
    val dinoClause = fullBook.clauses.first()
    val forestClause = fullBook.clauses.firstOrNull { it.text.contains("大森林") } ?: fullBook.clauses.last()
    repo.toggleCollectWord("小恐龙", listOf("xiǎo", "kǒng", "lóng"), fullBook, dinoClause)
    repo.toggleCollectWord("大森林", listOf("dà", "sēn", "lín"), fullBook, forestClause)
}

private fun createSampleWatercolorPng(targetFile: File, width: Int = 720, height: Int = 540) {
    targetFile.parentFile?.mkdirs()
    val surface = Surface.makeRasterN32Premul(width, height)
    val c: Canvas = surface.canvas
    val skyPaint = Paint().apply {
        shader = Shader.makeLinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(SkColor.makeRGB(248, 236, 212), SkColor.makeRGB(228, 236, 224), SkColor.makeRGB(246, 226, 200)),
        )
    }
    c.drawRect(org.jetbrains.skia.Rect.makeWH(width.toFloat(), height.toFloat()), skyPaint)
    // Soft moon
    c.drawCircle(width * 0.78f, height * 0.22f, 52f, Paint().apply { color = SkColor.makeARGB(220, 252, 238, 178) })
    // Gentle hills
    val hill = SkPath().apply {
        moveTo(0f, height * 0.72f)
        cubicTo(width * 0.28f, height * 0.50f, width * 0.62f, height * 0.80f, width.toFloat(), height * 0.62f)
        lineTo(width.toFloat(), height.toFloat())
        lineTo(0f, height.toFloat())
        closePath()
    }
    c.drawPath(hill, Paint().apply { color = SkColor.makeARGB(200, 148, 186, 154) })
    // Volcano cone + marshmallow cloud
    val volcano = SkPath().apply {
        moveTo(width * 0.16f, height * 0.78f)
        lineTo(width * 0.30f, height * 0.42f)
        lineTo(width * 0.44f, height * 0.42f)
        lineTo(width * 0.58f, height * 0.78f)
        closePath()
    }
    c.drawPath(volcano, Paint().apply { color = SkColor.makeARGB(220, 198, 146, 120) })
    hill.close()
    volcano.close()
    val puff = Paint().apply { color = SkColor.makeARGB(235, 254, 245, 238) }
    c.drawCircle(width * 0.35f, height * 0.31f, 36f, puff)
    c.drawCircle(width * 0.41f, height * 0.28f, 44f, puff)
    c.drawCircle(width * 0.47f, height * 0.32f, 34f, puff)
    // Little blue dinosaur silhouette
    val dinoPaint = Paint().apply { color = SkColor.makeARGB(240, 74, 134, 178) }
    c.drawCircle(width * 0.64f, height * 0.68f, 38f, dinoPaint)
    c.drawCircle(width * 0.69f, height * 0.56f, 24f, dinoPaint)
    val bytes = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)?.bytes
        ?: throw IllegalStateException("Failed to encode sample watercolor PNG")
    targetFile.writeBytes(bytes)
    surface.close()
}

private fun renderVerificationSuite(outDir: File) {
    outDir.mkdirs()
    val tmp = createTempDirectory("studio-m3-shots-").toFile()
    try {
        val env = DesktopPlatformEnvironment(storageRoot = tmp)
        HanziStrokeRepository.ensureLoaded { env.openStrokeAssetStream() }
        seedShowcaseBookIfEmpty(env)
        val repo = StoryBookRepository(env.storageRoot)
        val illustratedBook = repo.listBooks().first()

        // Create second unillustrated book for landscape unillustrated centered-page shot + fuller shelf
        val plainBook = repo.createBlankBook()
        repo.insertSentenceClip(plainBook.id, null, "清晨的露珠挂在叶子上，小鸟在枝头唱起了歌。", tempWavFile = null, durationSec = 0.0)
        repo.updateBookTitleAndCover(plainBook.id, newTitle = "森林清晨", coverColorIndex = 1, emblemIndex = 3)

        // Seed sample watercolor illustration & SoftAnnotation & TimeMachineSnapshot on illustratedBook
        val illuFile = File(tmp, "sample_watercolor.png")
        createSampleWatercolorPng(illuFile)
        repo.attachIllustration(illustratedBook.id, 0, illuFile, "warm watercolor dinosaur marshmallow volcano")
        val targetClause = illustratedBook.clauses.first()
        repo.annotateClauseSoftSuggestions(
            illustratedBook.id,
            targetClause.id,
            listOf(SoftAnnotation(charIndex = 5, length = 1, suggestedWord = "地", correctedPinyin = "de", reason = "助词")),
        )
        repo.replaceRangeInClause(illustratedBook.id, targetClause.id, 0, 3, "大恐龙", actionLabel = "修改词语", toolName = "replace_span")
        val snaps = repo.timeMachine.listSnapshots(illustratedBook.id)
        repo.restoreSnapshot(illustratedBook.id, snaps.first().id)

        val controller = BookStudioController(env)
        val books = repo.listBooks()
        val mainBook = books.first { it.id == illustratedBook.id }
        val plainLoaded = books.first { it.id == plainBook.id }
        val words = repo.listCollectedWords()
        val updatedSnaps = repo.timeMachine.listSnapshots(mainBook.id)

        val cleanLandscapeState = StudioUiState(
            destination = StudioDestination.BOOK,
            books = books,
            activeBookId = mainBook.id,
            currentPageIndex = 0,
            collectedWords = words,
            timeMachineSnapshots = updatedSnaps,
        )
        renderShot(File(outDir, "01-landscape-spread.png"), 960, 600) {
            MaterialTheme { StoryStudioRoot(cleanLandscapeState, controller) }
        }
        val phoneReaderState = StudioUiState(
            destination = StudioDestination.BOOK,
            books = books,
            activeBookId = mainBook.id,
            currentPageIndex = 0,
            cursorAfterClauseId = mainBook.clauses.getOrNull(1)?.id,
            activeMicIntent = MicIntent.WriteAtCursor,
            liveDraftText = "第二天太阳出来了",
            voiceEnergy = 0.55f,
            collectedWords = words,
            timeMachineSnapshots = updatedSnaps,
        )
        renderShot(File(outDir, "02-phone-reader.png"), 390, 844) {
            MaterialTheme { StoryStudioRoot(phoneReaderState, controller) }
        }
        val shelfState = StudioUiState(destination = StudioDestination.SHELF, books = books, collectedWords = words)
        renderShot(File(outDir, "03-phone-bookshelf.png"), 390, 844) {
            MaterialTheme { StoryStudioRoot(shelfState, controller) }
        }
        val portraitReaderState = StudioUiState(
            destination = StudioDestination.BOOK,
            books = books,
            activeBookId = mainBook.id,
            currentPageIndex = 0,
            collectedWords = words,
            timeMachineSnapshots = updatedSnaps,
        )
        renderShot(File(outDir, "04-tablet7-portrait-reader.png"), 560, 900) {
            MaterialTheme { StoryStudioRoot(portraitReaderState, controller) }
        }
        val kongClause = mainBook.clauses.first { it.text.contains("小恐龙") }
        val span = LiteracyEngine.findWordAt(kongClause.text, kongClause.text.indexOf('恐'), kongClause.contextualPhrases)!!
        val wordCardState = cleanLandscapeState.copy(
            selectedClauseId = null,
            inspectedWord = ActiveWordInspection(
                clauseId = kongClause.id,
                span = span,
                homophones = LiteracyEngine.suggestHomophones(span.word, span.pinyins),
                softSuggestion = "小孔龙",
            ),
        )
        renderShot(File(outDir, "05-tianzige-phrase-card.png"), 960, 600) {
            MaterialTheme { StoryStudioRoot(wordCardState, controller) }
        }
        val capsuleState = cleanLandscapeState.copy(
            selectedClauseId = mainBook.clauses.firstOrNull()?.id,
        )
        renderShot(File(outDir, "06-clause-capsule.png"), 960, 600) {
            MaterialTheme { StoryStudioRoot(capsuleState, controller) }
        }
        val unillustratedLandState = StudioUiState(
            destination = StudioDestination.BOOK,
            books = books,
            activeBookId = plainLoaded.id,
            currentPageIndex = 0,
            collectedWords = words,
        )
        renderShot(File(outDir, "07-landscape-unillustrated.png"), 960, 600) {
            MaterialTheme { StoryStudioRoot(unillustratedLandState, controller) }
        }
        val tmState = cleanLandscapeState.copy(
            isTimeMachineOpen = true,
            timeMachineSnapshots = updatedSnaps,
        )
        renderShot(File(outDir, "08-time-machine-sheet.png"), 960, 600) {
            MaterialTheme { StoryStudioRoot(tmState, controller) }
        }
        val settingsState = shelfState.copy(
            isParentSettingsOpen = true,
            discoveredAiModels = listOf("auto/deepseek-v4.1-flash", "codex/gpt-image-2"),
        )
        renderShot(File(outDir, "09-parent-settings-sheet.png"), 560, 900) {
            MaterialTheme { StoryStudioRoot(settingsState, controller) }
        }
        val wordBoxState = StudioUiState(
            destination = StudioDestination.WORD_BOX,
            books = books,
            collectedWords = words,
        )
        renderShot(File(outDir, "10-word-box.png"), 960, 600) {
            MaterialTheme { StoryStudioRoot(wordBoxState, controller) }
        }
        val editorState = cleanLandscapeState.copy(isFullTextEditorOpen = true)
        renderShot(File(outDir, "11-full-text-editor-landscape.png"), 960, 600) {
            MaterialTheme { StoryStudioRoot(editorState, controller) }
        }
        renderShot(File(outDir, "12-full-text-editor-phone.png"), 390, 844) {
            MaterialTheme { StoryStudioRoot(editorState, controller) }
        }
        val phoneTopBarAiState = cleanLandscapeState.copy(
            aiConfig = com.luna.storynote.model.AiGatewayConfig(
                baseUrl = "https://example.invalid/v1",
                apiKey = "sk-preview",
                textModel = "preview-model",
            ),
            isFullBookPlaying = true,
        )
        renderShot(File(outDir, "13-phone-top-bar-ai.png"), 390, 844) {
            MaterialTheme { StoryStudioRoot(phoneTopBarAiState, controller) }
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
