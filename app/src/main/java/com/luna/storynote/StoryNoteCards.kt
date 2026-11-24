package com.luna.storynote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun EmptyShelfCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = CardBg,
        border = BorderStroke(1.5.dp, BorderWarm),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 40.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🦖  🚀  🏰", fontSize = 34.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                text = "还没有故事笔记呢",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = InkStrong,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "讲完第一段故事，原声录音和文字就会一起存到这里！",
                fontSize = 14.sp,
                color = InkMuted,
            )
        }
    }
}

@Composable
internal fun NoteItemCard(
    note: StoryNote,
    isPlaying: Boolean,
    playPosSec: Float,
    playDurSec: Float,
    isRetranscribing: Boolean,
    onCycleSticker: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Float) -> Unit,
    onEdit: () -> Unit,
    onRetranscribe: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = CardBg,
        border = BorderStroke(2.dp, BorderWarm),
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFFFF9EE))
                        .border(1.5.dp, BorderWarm, RoundedCornerShape(16.dp))
                        .clickable { onCycleSticker() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(note.sticker, fontSize = 28.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = CoralSoft) {
                            Text(
                                text = "第 ${note.storyNumber} 篇",
                                color = CoralMain,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = formatDateShort(note.createdAtMs),
                            fontSize = 12.sp,
                            color = InkMuted,
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = note.title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = InkStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = GreenSoft,
                border = BorderStroke(1.5.dp, Color(0xFFCBEAD9)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onTogglePlay,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPlaying) AmberMain else GreenMain,
                            contentColor = Color.White,
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = if (isPlaying) "⏸ 暂停" else "▶ 听我讲的",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    val safeMax = maxOf(0.5f, playDurSec)
                    Slider(
                        value = if (isPlaying) playPosSec.coerceIn(0f, safeMax) else 0f,
                        onValueChange = { if (isPlaying) onSeek(it) },
                        valueRange = 0f..safeMax,
                        colors = SliderDefaults.colors(
                            thumbColor = GreenMain,
                            activeTrackColor = GreenMain,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isPlaying) {
                            "${formatClock(playPosSec.toDouble())} / ${formatClock(safeMax.toDouble())}"
                        } else {
                            formatClock(note.durationSec)
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = InkBody,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = LinedPaperBg,
            ) {
                SelectionContainer {
                    if (note.text.isNotBlank()) {
                        Text(
                            text = note.text,
                            fontSize = 19.sp,
                            lineHeight = 31.sp,
                            color = InkStrong,
                            modifier = Modifier.padding(14.dp),
                        )
                    } else {
                        val fallback = if (note.asrError.isNotBlank()) {
                            "录音已安全保存（当时未联网或识别未完成：${note.asrError}，点下方「重新识别」即可生成文字）"
                        } else {
                            "录音已保存（暂未识别出文字，可点「重新识别」）"
                        }
                        Text(
                            text = fallback,
                            fontSize = 15.sp,
                            lineHeight = 23.sp,
                            color = InkMuted,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                SmallActionChip(label = "✏️ 改字", onClick = onEdit)
                SmallActionChip(
                    label = if (isRetranscribing) "⏳ 识别中…" else "🔄 重新识别",
                    enabled = !isRetranscribing,
                    onClick = onRetranscribe,
                )
                SmallActionChip(label = "📤 分享", onClick = onShare)
                SmallActionChip(
                    label = "🗑️ 删除",
                    textColor = Color(0xFFB91C1C),
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
internal fun SmallActionChip(
    label: String,
    enabled: Boolean = true,
    textColor: Color = InkBody,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFFAF6EE),
        border = BorderStroke(1.dp, BorderWarm),
        modifier = Modifier.clickable(enabled = enabled) { onClick() },
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) textColor else InkMuted,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
internal fun EditStoryDialog(
    note: StoryNote,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var title by remember(note.id) { mutableStateOf(note.title) }
    var text by remember(note.id) { mutableStateOf(note.text) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑第 ${note.storyNumber} 篇故事", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(40) },
                    label = { Text("故事标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("故事内容") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title, text) },
                colors = ButtonDefaults.buttonColors(containerColor = CoralMain),
            ) {
                Text("保存修改", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
