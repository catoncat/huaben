package com.luna.storynote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luna.storynote.RecStatus
import com.luna.storynote.StoryAppUiState
import com.luna.storynote.StoryController
import com.luna.storynote.StoryNote
import com.luna.storynote.rememberPlatformBookFontFamily
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun StoryBookRootScreen(
    uiState: StoryAppUiState,
    controller: StoryController,
    modifier: Modifier = Modifier,
) {
    val bookFont = rememberPlatformBookFontFamily()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ShelfBackground),
    ) {
        val activeNote = uiState.selectedNote
        if (activeNote != null) {
            StoryReaderScreen(
                note = activeNote,
                uiState = uiState,
                controller = controller,
                bookFont = bookFont,
            )
        } else {
            BookshelfScreen(
                uiState = uiState,
                controller = controller,
                bookFont = bookFont,
            )
        }

        if (uiState.recorderOpen) {
            RecorderModalOverlay(
                uiState = uiState,
                controller = controller,
                bookFont = bookFont,
            )
        }

        uiState.toastMessage?.let { msg ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp, start = 20.dp, end = 20.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (uiState.toastPositive) ForestGreen else BookInk,
                    shadowElevation = 6.dp,
                ) {
                    Text(
                        text = msg,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BookshelfScreen(
    uiState: StoryAppUiState,
    controller: StoryController,
    bookFont: FontFamily,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // Shelf Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "小作家故事集",
                    fontFamily = bookFont,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = BookInk,
                )
                Text(
                    text = "开口讲故事，自动排版成带拼音的故事书",
                    fontSize = 13.sp,
                    color = MutedInk,
                )
            }

            Button(
                onClick = { controller.openNewStoryRecorder() },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Terracotta,
                    contentColor = Color.White,
                ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp),
            ) {
                Text(
                    text = "🎙️ 讲新故事",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        if (uiState.notes.isEmpty()) {
            EmptyBookshelfInvitation(
                bookFont = bookFont,
                onStartFirstStory = { controller.openNewStoryRecorder() },
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 290.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "共写了 ${uiState.notes.size} 篇故事",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MutedInk,
                    )
                }
                items(uiState.notes, key = { it.id }) { note ->
                    BookCoverCard(
                        note = note,
                        bookFont = bookFont,
                        onClick = { controller.openStory(note.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyBookshelfInvitation(
    bookFont: FontFamily,
    onStartFirstStory: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 540.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = BookPageBackground,
            border = BorderStroke(1.5.dp, BookBorder),
            shadowElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 34.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RubyBookTitle(
                    title = "我的第一本故事书",
                    bookFont = bookFont,
                    hanziSize = 26.sp,
                    pinyinSize = 12.5.sp,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "点下面的按钮开口讲，不用一直按着。\n讲完会自动分成一小段一小段，并标好拼音！",
                    fontSize = 15.sp,
                    lineHeight = 24.sp,
                    color = BookSubInk,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onStartFirstStory,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Terracotta,
                        contentColor = Color.White,
                    ),
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
                ) {
                    Text(
                        text = "🎙️ 点一下开始讲故事",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun BookCoverCard(
    note: StoryNote,
    bookFont: FontFamily,
    onClick: () -> Unit,
) {
    val spineColor = spineColorFor(note.coverColorIndex)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 18.dp, bottomEnd = 18.dp),
        color = BookCardSurface,
        border = BorderStroke(1.5.dp, BookBorder),
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .heightIn(min = 184.dp),
        ) {
            // Bound book cloth spine
            Box(
                modifier = Modifier
                    .width(12.dp)
                    .fillMaxHeight()
                    .background(spineColor),
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(BookHairline),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column {
                    Text(
                        text = "第 ${note.storyNumber} 篇  ·  ${formatBookDate(note.createdAtMs)}",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = spineColor,
                    )
                    Spacer(Modifier.height(6.dp))
                    RubyCoverTitle(
                        title = note.title,
                        bookFont = bookFont,
                    )
                    Spacer(Modifier.height(6.dp))
                    val preview = note.paragraphs.firstOrNull().orEmpty()
                    if (preview.isNotEmpty()) {
                        Text(
                            text = preview,
                            fontFamily = bookFont,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = BookSubInk,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "🎧 原声 ${formatDurationClock(note.durationSec)}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ForestGreen,
                    )
                    Text(
                        text = "${note.paragraphs.size} 小段 · ${note.charCount} 字 →",
                        fontSize = 12.sp,
                        color = MutedInk,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecorderModalOverlay(
    uiState: StoryAppUiState,
    controller: StoryController,
    bookFont: FontFamily,
) {
    val isRecording = uiState.recStatus == RecStatus.RECORDING
    val isPaused = uiState.recStatus == RecStatus.PAUSED
    val isSaving = uiState.recStatus == RecStatus.SAVING
    val appendNote = uiState.appendTargetNote

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x881F1A15))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .padding(18.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = BookPageBackground,
            border = BorderStroke(2.dp, if (isRecording) Terracotta else BookBorder),
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val statusLabel = when (uiState.recStatus) {
                        RecStatus.RECORDING -> if (appendNote != null) "🔴 正在续讲《${appendNote.title}》" else "🔴 正在听你讲故事…"
                        RecStatus.PAUSED -> "⏸ 已暂停，想好了点继续"
                        RecStatus.SAVING -> "✨ 正在分段并标注拼音…"
                        RecStatus.IDLE -> "准备就绪"
                    }
                    Surface(
                        shape = CircleShape,
                        color = if (isRecording) TerracottaSoft else AmberSoft,
                    ) {
                        Text(
                            text = statusLabel,
                            color = if (isRecording) Terracotta else AmberInk,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }

                    if (!isSaving) {
                        Text(
                            text = "取消",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MutedInk,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { controller.cancelRecording() }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = formatDurationClock(uiState.recElapsedSec),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isRecording) Terracotta else BookInk,
                )

                Spacer(Modifier.height(12.dp))

                LiveSoundWaveStrip(active = isRecording, level = uiState.voiceLevel)

                Spacer(Modifier.height(16.dp))

                // Live emerging speech preview box
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 110.dp, max = 210.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = BookCardSurface,
                    border = BorderStroke(1.dp, BookHairline),
                ) {
                    val scroll = rememberScrollState()
                    val txt = uiState.liveTranscript.trim()
                    LaunchedEffect(txt) {
                        scroll.animateScrollTo(scroll.maxValue)
                    }
                    Box(
                        modifier = Modifier
                            .verticalScroll(scroll)
                            .padding(14.dp),
                    ) {
                        if (txt.isNotEmpty()) {
                            Text(
                                text = txt,
                                fontFamily = bookFont,
                                fontSize = 20.sp,
                                lineHeight = 30.sp,
                                color = BookInk,
                            )
                        } else {
                            Text(
                                text = "不用按住屏幕，自然开口讲就可以啦。\n想停下来思考剧情也没关系，讲完点绿色按钮自动生成注音故事书！",
                                fontSize = 15.sp,
                                lineHeight = 23.sp,
                                color = MutedInk,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Hands-free Pause/Resume + Finish & Typeset Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = { controller.togglePauseRecording() },
                        enabled = !isSaving,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.5.dp, BookBorder),
                        contentPadding = PaddingValues(vertical = 16.dp, horizontal = 14.dp),
                        modifier = Modifier.weight(0.42f),
                    ) {
                        Text(
                            text = if (isPaused) "▶ 继续讲" else "⏸ 暂停想一想",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = BookInk,
                        )
                    }

                    Button(
                        onClick = { controller.finishAndTypesetStory() },
                        enabled = !isSaving,
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ForestGreen,
                            contentColor = Color.White,
                        ),
                        contentPadding = PaddingValues(vertical = 16.dp, horizontal = 16.dp),
                        modifier = Modifier.weight(0.58f),
                    ) {
                        Text(
                            text = if (isSaving) "⏳ 正在排版…" else "✓ 讲完了，生成故事书",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveSoundWaveStrip(active: Boolean, level: Float) {
    var nowMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(active) {
        while (active) {
            nowMs = System.currentTimeMillis()
            delay(85L)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF3ECE0))
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val count = 18
        for (i in 0 until count) {
            val h = if (!active) {
                5.dp
            } else {
                val dist = abs(i - (count - 1) / 2f) / (count / 2f)
                val wave = (sin(nowMs / 105.0 + i * 0.6) * 0.28 + 0.72).toFloat()
                val amp = (level * 5.4f).coerceIn(0.08f, 1f)
                (5f + amp * (1f - dist * 0.45f) * wave * 26f).coerceIn(5f, 30f).dp
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .size(width = 6.dp, height = h)
                    .clip(CircleShape)
                    .background(if (active) Terracotta else Color(0xFFD5C8B4)),
            )
        }
    }
}
