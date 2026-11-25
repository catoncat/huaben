package com.luna.storynote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luna.storynote.StoryAppUiState
import com.luna.storynote.StoryController
import com.luna.storynote.StoryNote

@Composable
fun StoryReaderScreen(
    note: StoryNote,
    uiState: StoryAppUiState,
    controller: StoryController,
    bookFont: FontFamily,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val isThisPlaying = uiState.playingNoteId == note.id
    val isActivelySpeaking = isThisPlaying && !uiState.isAudioPaused
    val isRetranscribing = note.id in uiState.retranscribingIds

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ShelfBackground),
    ) {
        // Subtle Reader Top Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = BookCardSurface,
                border = BorderStroke(1.dp, BookBorder),
                modifier = Modifier.clickable { controller.closeStoryToShelf() },
            ) {
                Text(
                    text = "← 书架",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = BookInk,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }

            Text(
                text = "小作家注音故事集 · 第 ${note.storyNumber} 篇",
                fontSize = 13.sp,
                color = MutedInk,
                fontWeight = FontWeight.Medium,
            )

            Box {
                Surface(
                    shape = CircleShape,
                    color = BookCardSurface,
                    border = BorderStroke(1.dp, BookBorder),
                    modifier = Modifier.clickable { menuExpanded = true },
                ) {
                    Text(
                        text = if (isRetranscribing) "识别中…" else "家长 ⋯",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = BookSubInk,
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("修改错字与标题") },
                        onClick = {
                            menuExpanded = false
                            showEditDialog = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("重新识别并分段") },
                        enabled = !isRetranscribing,
                        onClick = {
                            menuExpanded = false
                            controller.retranscribeStory(note)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(controller.env.shareActionLabel) },
                        onClick = {
                            menuExpanded = false
                            controller.shareOrReveal(note)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除这篇故事", color = Color(0xFFB91C1C)) },
                        onClick = {
                            menuExpanded = false
                            showDeleteConfirm = true
                        },
                    )
                }
            }
        }

        // Scrollable Open Book Page
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = BookPageBackground,
                border = BorderStroke(1.5.dp, BookBorder),
                shadowElevation = 4.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 26.dp, vertical = 30.dp),
                ) {
                    // Book Chapter Ornament
                    Text(
                        text = "—  第 ${note.storyNumber} 篇  —",
                        fontSize = 13.sp,
                        color = spineColorFor(note.coverColorIndex),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(12.dp))

                    // Pinyin-Annotated Book Title
                    RubyBookTitle(
                        title = note.title,
                        bookFont = bookFont,
                        hanziSize = 28.sp,
                        pinyinSize = 13.sp,
                        centerAlign = true,
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "${formatBookDate(note.createdAtMs)}  ·  共 ${note.charCount} 字",
                        fontSize = 12.5.sp,
                        color = MutedInk,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(20.dp))

                    // Clean Audiobook Read-Along Bar
                    AudiobookReadAlongBar(
                        isActiveNote = isThisPlaying,
                        isPlaying = isActivelySpeaking,
                        posSec = if (isThisPlaying) uiState.playPositionSec else 0f,
                        totalSec = if (isThisPlaying) uiState.playDurationSec else note.durationSec.toFloat(),
                        fallbackSec = note.durationSec,
                        onTogglePlay = { controller.togglePlayAudio(note) },
                        onSeek = { sec -> controller.seekAudio(note.id, sec) },
                    )

                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(color = BookHairline, thickness = 1.dp)
                    Spacer(Modifier.height(26.dp))

                    // Short, breathable Pinyin-annotated paragraphs ("一小段一小段")
                    if (note.paragraphs.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(26.dp)) {
                            for (para in note.paragraphs) {
                                RubyParagraph(
                                    paragraph = para,
                                    bookFont = bookFont,
                                    indentTwoChars = true,
                                )
                            }
                        }
                    } else {
                        val hint = if (note.asrError.isNotBlank()) {
                            "原声录音已完整保存（当时网络未连上豆包：${note.asrError}）。点右上角「家长 ⋯ → 重新识别并分段」就能生成拼音故事书啦！"
                        } else {
                            "这段录音还没有识别出文字，可以点右上角「重新识别并分段」，或者点下方继续往下讲。"
                        }
                        Text(
                            text = hint,
                            fontSize = 16.sp,
                            lineHeight = 26.sp,
                            color = MutedInk,
                            modifier = Modifier.padding(vertical = 18.dp),
                        )
                    }

                    Spacer(Modifier.height(34.dp))
                    HorizontalDivider(color = BookHairline, thickness = 1.dp)
                    Spacer(Modifier.height(20.dp))

                    // Effortless "Continue telling this story" button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        OutlinedButton(
                            onClick = { controller.openAppendRecorder(note.id) },
                            shape = CircleShape,
                            border = BorderStroke(1.5.dp, Terracotta.copy(alpha = 0.55f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = TerracottaSoft.copy(alpha = 0.55f),
                                contentColor = Terracotta,
                            ),
                            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = "🎙️ 接着往下讲这一篇",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(36.dp))
        }
    }

    if (showEditDialog) {
        ParentEditStoryDialog(
            note = note,
            onDismiss = { showEditDialog = false },
            onSave = { title, rawText ->
                controller.saveStoryEdits(note.id, title, rawText)
                showEditDialog = false
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除这篇故事？", fontWeight = FontWeight.Bold) },
            text = { Text("确定要删除《${note.title}》的原声录音和注音文稿吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        controller.deleteStory(note)
                    },
                ) {
                    Text("确认删除", color = Color(0xFFB91C1C), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("保留") }
            },
        )
    }
}

@Composable
private fun AudiobookReadAlongBar(
    isActiveNote: Boolean,
    isPlaying: Boolean,
    posSec: Float,
    totalSec: Float,
    fallbackSec: Double,
    onTogglePlay: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = ForestGreenSoft,
        border = BorderStroke(1.dp, Color(0xFFC7E5D6)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onTogglePlay,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPlaying) AmberInk else ForestGreen,
                    contentColor = Color.White,
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text(
                    text = if (isPlaying) "⏸ 暂停原声" else "▶ 听我讲的原声",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(12.dp))
            val safeMax = maxOf(0.5f, totalSec)
            Slider(
                value = if (isActiveNote) posSec.coerceIn(0f, safeMax) else 0f,
                onValueChange = { if (isActiveNote) onSeek(it) },
                valueRange = 0f..safeMax,
                colors = SliderDefaults.colors(
                    thumbColor = ForestGreen,
                    activeTrackColor = ForestGreen,
                    inactiveTrackColor = Color(0xFFCDE5D8),
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isActiveNote) {
                    "${formatDurationClock(posSec.toDouble())} / ${formatDurationClock(safeMax.toDouble())}"
                } else {
                    formatDurationClock(fallbackSec)
                },
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = BookSubInk,
            )
        }
    }
}

@Composable
private fun ParentEditStoryDialog(
    note: StoryNote,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var title by remember(note.id) { mutableStateOf(note.title) }
    var body by remember(note.id) { mutableStateOf(note.paragraphs.joinToString("\n\n")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改文字（保存后自动重排拼音）", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(28) },
                    label = { Text("故事标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("故事正文（空行分段）") },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title, body) },
                colors = ButtonDefaults.buttonColors(containerColor = Terracotta),
            ) {
                Text("保存并重新注音", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
