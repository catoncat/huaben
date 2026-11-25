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
import com.luna.storynote.ui.StoryBookRootScreen
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.io.path.createTempDirectory

fun main(args: Array<String>) {
    val renderFlagIdx = args.indexOf("--render-preview")
    if (renderFlagIdx >= 0 && renderFlagIdx + 1 < args.size) {
        renderHeadlessPreviewShots(File(args[renderFlagIdx + 1]))
        return
    }

    val env = DesktopPlatformEnvironment()
    seedSampleStoryIfFirstRun(env)
    val controller = StoryController(env)

    application {
        val windowState = rememberWindowState(size = DpSize(1120.dp, 820.dp))
        Window(
            onCloseRequest = {
                controller.dispose()
                exitApplication()
            },
            state = windowState,
            title = "小作家故事集（本地桌面版 — 与安卓 APK 同源）",
        ) {
            var previewMode by remember { mutableIntStateOf(0) } // 0=平板铺满, 1=平板竖屏, 2=手机竖屏
            val uiState by controller.uiState.collectAsState()

            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFE7DFD1)),
                ) {
                    // Lightweight Desktop Device Form-Factor Switcher Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF2B2520))
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "本地实时预览（可直接点「讲新故事」用电脑麦克风试录）",
                            color = Color(0xFFE5DDD3),
                            fontSize = 12.5.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("平板横屏铺满", "平板竖屏 760×980", "手机 400×840").forEachIndexed { idx, label ->
                                val selected = previewMode == idx
                                Surface(
                                    shape = CircleShape,
                                    color = if (selected) Color(0xFFD95338) else Color(0xFF423932),
                                    modifier = Modifier.clickable { previewMode = idx },
                                ) {
                                    Text(
                                        text = label,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        val frameModifier = when (previewMode) {
                            1 -> Modifier
                                .padding(14.dp)
                                .width(760.dp)
                                .height(980.dp)
                                .shadow(10.dp, RoundedCornerShape(20.dp))
                                .clip(RoundedCornerShape(20.dp))
                            2 -> Modifier
                                .padding(14.dp)
                                .width(400.dp)
                                .height(840.dp)
                                .shadow(10.dp, RoundedCornerShape(24.dp))
                                .clip(RoundedCornerShape(24.dp))
                            else -> Modifier.fillMaxSize()
                        }

                        StoryBookRootScreen(
                            uiState = uiState,
                            controller = controller,
                            modifier = frameModifier,
                        )
                    }
                }
            }
        }
    }
}

private fun seedSampleStoryIfFirstRun(env: DesktopPlatformEnvironment) {
    val marker = File(env.storageRoot, ".seeded_demo_v2")
    val repo = NoteRepository(env.storageRoot)
    if (marker.isFile || repo.listNotes().isNotEmpty()) return
    val recId = repo.newRecordingId()
    val wav = WavFileStreamWriter(repo.stagingWavFile(recId))
    wav.appendSilenceMs(3200)
    wav.close()
    repo.finalizeNewNote(
        recId = recId,
        durationSec = 3.2,
        rawRecognizedText = "从前有一只蓝色的小恐龙，他慢慢长大了，最喜欢吃草莓味的棉花糖。有一天早上，他看着天上的月亮，觉得月亮像一块大饼干。突然，一艘飞船落在了家门口，小恐龙开心地跑过去交新朋友，玩累了才回家睡觉。",
        rollovers = 0,
        asrError = "",
    )
    marker.writeText("ok")
}

private fun renderHeadlessPreviewShots(outDir: File) {
    outDir.mkdirs()
    val tempStorage = createTempDirectory("story-shot-").toFile()
    try {
        val env = DesktopPlatformEnvironment(storageRoot = tempStorage)
        seedSampleStoryIfFirstRun(env)
        val notes = NoteRepository(tempStorage).listNotes()
        val sample = notes.first()
        val controller = StoryController(env)

        // 1. Render Bookshelf View (Tablet 1100x760 @ 2x Retina)
        val shelfState = StoryAppUiState(notes = notes)
        saveComposeShot(File(outDir, "01-shelf-tablet.png"), 1100, 760) {
            MaterialTheme {
                StoryBookRootScreen(uiState = shelfState, controller = controller)
            }
        }

        // 2. Render Published Pinyin Book Reader View (Tablet 1100x880 & Phone 412x892)
        val readerState = StoryAppUiState(notes = notes, selectedNoteId = sample.id)
        saveComposeShot(File(outDir, "02-reader-tablet.png"), 1100, 880) {
            MaterialTheme {
                StoryBookRootScreen(uiState = readerState, controller = controller)
            }
        }
        saveComposeShot(File(outDir, "03-reader-phone.png"), 412, 915) {
            MaterialTheme {
                StoryBookRootScreen(uiState = readerState, controller = controller)
            }
        }

        // 3. Render Hands-Free Recording Modal View
        val recState = StoryAppUiState(
            notes = notes,
            recorderOpen = true,
            recStatus = RecStatus.RECORDING,
            recElapsedSec = 28.0,
            voiceLevel = 0.42f,
            liveTranscript = "从前有一只住在森林里的小狐狸，他有一条毛茸茸的大尾巴……",
        )
        saveComposeShot(File(outDir, "04-recorder-modal.png"), 1100, 760) {
            MaterialTheme {
                StoryBookRootScreen(uiState = recState, controller = controller)
            }
        }
        controller.dispose()
    } finally {
        tempStorage.deleteRecursively()
    }
}

private fun saveComposeShot(
    file: File,
    widthDp: Int,
    heightDp: Int,
    scale: Float = 2f,
    content: @androidx.compose.runtime.Composable () -> Unit,
) {
    val pxW = (widthDp * scale).toInt()
    val pxH = (heightDp * scale).toInt()
    val scene = ImageComposeScene(
        width = pxW,
        height = pxH,
        density = Density(scale),
    )
    try {
        scene.setContent(content)
        val img = scene.render(0L)
        val pngData = img.encodeToData(EncodedImageFormat.PNG)
            ?: throw IllegalStateException("Failed to encode PNG")
        file.writeBytes(pngData.bytes)
    } finally {
        scene.close()
    }
}
