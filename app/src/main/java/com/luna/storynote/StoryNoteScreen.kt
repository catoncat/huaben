package com.luna.storynote

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun StoryNoteAppScreen(
    uiState: StoryUiState,
    viewModel: StoryViewModel,
) {
    val context = LocalContext.current
    var editingNote by remember { mutableStateOf<StoryNote?>(null) }
    var deletingNote by remember { mutableStateOf<StoryNote?>(null) }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.startRecording()
        } else {
            viewModel.showToast("需要开启麦克风权限才能录故事哦！")
        }
    }

    val onToggleRecord = {
        when (uiState.recMode) {
            RecMode.IDLE -> {
                val hasPerm = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                if (hasPerm) {
                    viewModel.startRecording()
                } else {
                    micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            RecMode.RECORDING -> viewModel.finishRecording()
            RecMode.SAVING -> Unit
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PaperBg)
            .statusBarsPadding(),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isWideTablet = maxWidth >= 820.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = if (isWideTablet) 24.dp else 16.dp, vertical = 12.dp),
            ) {
                TopHeaderBar(noteCount = uiState.notes.size)

                uiState.toastMessage?.let { msg ->
                    Spacer(Modifier.height(10.dp))
                    ToastStrip(message = msg, positive = uiState.toastIsPositive)
                }

                Spacer(Modifier.height(14.dp))

                if (isWideTablet) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(400.dp)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            RecordingStudioCard(
                                uiState = uiState,
                                onToggleRecord = onToggleRecord,
                                onCancel = { viewModel.cancelRecording() },
                            )
                        }
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentPadding = PaddingValues(bottom = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            item {
                                Text(
                                    text = "📖 我的故事笔记",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = InkStrong,
                                )
                            }
                            if (uiState.notes.isEmpty()) {
                                item { EmptyShelfCard() }
                            } else {
                                items(uiState.notes, key = { it.id }) { note ->
                                    NoteItemCard(
                                        note = note,
                                        isPlaying = uiState.playingNoteId == note.id,
                                        playPosSec = if (uiState.playingNoteId == note.id) uiState.playPositionSec else 0f,
                                        playDurSec = if (uiState.playingNoteId == note.id) uiState.playDurationSec else note.durationSec.toFloat(),
                                        isRetranscribing = note.id in uiState.retranscribingIds,
                                        onCycleSticker = { viewModel.cycleSticker(note) },
                                        onTogglePlay = { viewModel.togglePlay(note) },
                                        onSeek = { sec -> viewModel.seekPlayback(note.id, sec) },
                                        onEdit = { editingNote = note },
                                        onRetranscribe = { viewModel.retranscribeNote(note) },
                                        onShare = { viewModel.shareNote(context, note) },
                                        onDelete = { deletingNote = note },
                                    )
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 36.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        item {
                            RecordingStudioCard(
                                uiState = uiState,
                                onToggleRecord = onToggleRecord,
                                onCancel = { viewModel.cancelRecording() },
                            )
                        }
                        item {
                            Text(
                                text = "📖 我的故事笔记",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = InkStrong,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        if (uiState.notes.isEmpty()) {
                            item { EmptyShelfCard() }
                        } else {
                            items(uiState.notes, key = { it.id }) { note ->
                                NoteItemCard(
                                    note = note,
                                    isPlaying = uiState.playingNoteId == note.id,
                                    playPosSec = if (uiState.playingNoteId == note.id) uiState.playPositionSec else 0f,
                                    playDurSec = if (uiState.playingNoteId == note.id) uiState.playDurationSec else note.durationSec.toFloat(),
                                    isRetranscribing = note.id in uiState.retranscribingIds,
                                    onCycleSticker = { viewModel.cycleSticker(note) },
                                    onTogglePlay = { viewModel.togglePlay(note) },
                                    onSeek = { sec -> viewModel.seekPlayback(note.id, sec) },
                                    onEdit = { editingNote = note },
                                    onRetranscribe = { viewModel.retranscribeNote(note) },
                                    onShare = { viewModel.shareNote(context, note) },
                                    onDelete = { deletingNote = note },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editingNote?.let { note ->
        EditStoryDialog(
            note = note,
            onDismiss = { editingNote = null },
            onSave = { newTitle, newText ->
                viewModel.saveNoteEdit(note.id, newTitle, newText)
                editingNote = null
            },
        )
    }

    deletingNote?.let { note ->
        AlertDialog(
            onDismissRequest = { deletingNote = null },
            title = { Text("删除这篇故事？", fontWeight = FontWeight.Bold) },
            text = { Text("确定要删掉「第 ${note.storyNumber} 篇：${note.title}」的录音和文字吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteNote(note)
                        deletingNote = null
                    },
                ) {
                    Text("确认删除", color = Color(0xFFB91C1C), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingNote = null }) {
                    Text("保留")
                }
            },
        )
    }
}

@Composable
private fun TopHeaderBar(noteCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CoralMain),
                contentAlignment = Alignment.Center,
            ) {
                Text("📕", fontSize = 22.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = "小作家故事本",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = InkStrong,
                )
                Text(
                    text = "点一下就开讲 · 不用按住 · 留住声音和文字",
                    fontSize = 12.sp,
                    color = InkMuted,
                )
            }
        }
        Surface(
            shape = CircleShape,
            color = CardBg,
            border = BorderStroke(1.5.dp, BorderWarm),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = noteCount.toString(),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = CoralMain,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "篇故事",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = InkBody,
                )
            }
        }
    }
}

@Composable
private fun ToastStrip(message: String, positive: Boolean) {
    val bg = if (positive) GreenSoft else AmberSoft
    val border = if (positive) Color(0xFF6EE7B7) else Color(0xFFF59E0B)
    val fg = if (positive) Color(0xFF065F46) else Color(0xFF78350F)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = bg,
        border = BorderStroke(1.5.dp, border),
    ) {
        Text(
            text = message,
            color = fg,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun RecordingStudioCard(
    uiState: StoryUiState,
    onToggleRecord: () -> Unit,
    onCancel: () -> Unit,
) {
    val isRec = uiState.recMode == RecMode.RECORDING
    val isSaving = uiState.recMode == RecMode.SAVING
    val borderColor by animateColorAsState(
        targetValue = if (isRec) CoralMain else BorderWarm,
        label = "studioBorder",
    )
    val btnColor by animateColorAsState(
        targetValue = when (uiState.recMode) {
            RecMode.IDLE -> CoralMain
            RecMode.RECORDING -> GreenMain
            RecMode.SAVING -> AmberMain
        },
        label = "recordBtnBg",
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = CardBg,
        border = BorderStroke(2.dp, borderColor),
        shadowElevation = if (isRec) 6.dp else 2.dp,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val (badgeBg, badgeFg, badgeLabel) = when (uiState.recMode) {
                    RecMode.IDLE -> Triple(Color(0xFFF5EFE4), InkBody, "准备开讲")
                    RecMode.RECORDING -> Triple(CoralSoft, CoralMain, "🔴 正在听你讲故事…")
                    RecMode.SAVING -> Triple(AmberSoft, AmberMain, "✨ 正在存进故事本…")
                }
                Surface(shape = CircleShape, color = badgeBg) {
                    Text(
                        text = badgeLabel,
                        color = badgeFg,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
                Text(
                    text = formatClock(uiState.elapsedSec),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isRec) CoralMain else InkStrong,
                )
            }

            Spacer(Modifier.height(14.dp))

            Button(
                onClick = onToggleRecord,
                enabled = !isSaving,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = btnColor,
                    contentColor = Color.White,
                    disabledContainerColor = AmberMain.copy(alpha = 0.75f),
                    disabledContentColor = Color.White,
                ),
                contentPadding = PaddingValues(vertical = 22.dp, horizontal = 16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 144.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = when (uiState.recMode) {
                            RecMode.IDLE -> "🎙️"
                            RecMode.RECORDING -> "⏹️"
                            RecMode.SAVING -> "⏳"
                        },
                        fontSize = 38.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = when (uiState.recMode) {
                            RecMode.IDLE -> "点一下开始讲"
                            RecMode.RECORDING -> "讲完了点这里"
                            RecMode.SAVING -> "正在保存故事…"
                        },
                        fontSize = 25.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = when (uiState.recMode) {
                            RecMode.IDLE -> "手可以放开 · 想多久都不会断"
                            RecMode.RECORDING -> "自动存好录音和文字"
                            RecMode.SAVING -> "等一小下就好啦"
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.92f),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            VoiceWaveBar(isRecording = isRec, level = uiState.voiceLevel)

            Spacer(Modifier.height(14.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = LinedPaperBg,
                border = BorderStroke(1.5.dp, BorderWarm),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "📝 正在变成文字",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = InkMuted,
                        )
                        if (isRec) {
                            Surface(
                                shape = CircleShape,
                                color = CardBg,
                                border = BorderStroke(1.dp, BorderWarm),
                                modifier = Modifier.clickable { onCancel() },
                            ) {
                                Text(
                                    text = "✖ 这次不算",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = InkMuted,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val liveText = uiState.liveTranscript.trim()
                    if (liveText.isNotEmpty()) {
                        Text(
                            text = liveText,
                            fontSize = 19.sp,
                            lineHeight = 31.sp,
                            color = InkStrong,
                            modifier = Modifier.heightIn(min = 72.dp),
                        )
                    } else {
                        Text(
                            text = if (isRec) {
                                "开口说话吧，你说的每一句话都会出现在这里…"
                            } else {
                                "点上面的大红按钮，开口讲你的故事吧！停下来慢慢想也没关系。"
                            },
                            fontSize = 15.sp,
                            lineHeight = 23.sp,
                            color = InkMuted,
                            modifier = Modifier.heightIn(min = 72.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceWaveBar(isRecording: Boolean, level: Float) {
    var tickMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isRecording) {
        while (isRecording) {
            tickMs = System.currentTimeMillis()
            delay(85L)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFFAF5EA))
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val barCount = 16
        for (i in 0 until barCount) {
            val hDp = if (!isRecording) {
                6.dp
            } else {
                val centerDist = abs(i - (barCount - 1) / 2f) / (barCount / 2f)
                val osc = (sin(tickMs / 110.0 + i * 0.65) * 0.25 + 0.75).toFloat()
                val amp = (level * 5.2f).coerceIn(0.06f, 1f)
                val px = 6f + amp * (1f - centerDist * 0.45f) * osc * 28f
                px.coerceIn(6f, 34f).dp
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(hDp)
                    .clip(CircleShape)
                    .background(if (isRecording) CoralMain else Color(0xFFD8CCB8)),
            )
        }
    }
}
