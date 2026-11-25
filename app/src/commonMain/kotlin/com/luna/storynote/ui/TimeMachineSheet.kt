package com.luna.storynote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luna.storynote.BookStudioController
import com.luna.storynote.decodeFileToImageBitmap
import com.luna.storynote.model.StoryBook
import com.luna.storynote.model.StudioUiState
import com.luna.storynote.model.TimeMachineSnapshot
import com.luna.storynote.storage.ClauseHistoryEntry
import com.luna.storynote.ui.icons.IconCloseCross
import com.luna.storynote.ui.icons.IconPlayAudio
import com.luna.storynote.ui.icons.IconRotateCcw
import com.luna.storynote.ui.icons.IconTimeMachine
import com.luna.storynote.ui.motion.ModalPaperSheet
import com.luna.storynote.ui.motion.springPressScale
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun TimeMachineSheet(
    book: StoryBook,
    uiState: StudioUiState,
    controller: BookStudioController,
    bookFont: FontFamily,
) {
    val targetClauseId = uiState.timeMachineTargetClauseId
    var clauseScoped by remember(targetClauseId) { mutableStateOf(targetClauseId != null) }
    val clauseEntries = remember(targetClauseId, uiState.timeMachineSnapshots) {
        if (targetClauseId != null) controller.findClauseHistory(targetClauseId) else emptyList()
    }
    val pageIllustrations = remember(book.illustrations, uiState.currentPageIndex) {
        book.illustrations.filter { it.pageIndex == uiState.currentPageIndex }
    }
    val activeIllusId = book.activeIllustrationFor(uiState.currentPageIndex)?.id
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    ModalPaperSheet(
        onDismiss = { controller.closeTimeMachine() },
        modifier = Modifier.testTag("time_machine_sheet"),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = BookPaper,
            border = BorderStroke(1.2.dp, BookOutline),
            shadowElevation = 16.dp,
            modifier = Modifier
                .padding(horizontal = 18.dp, vertical = 24.dp)
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .heightIn(max = 580.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconTimeMachine(color = InkPrimary, size = 20.dp)
                        Text(
                            text = "时光机",
                            fontFamily = bookFont,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = InkPrimary,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (targetClauseId != null) {
                            Surface(
                                shape = CircleShape,
                                color = BookPaperWarm,
                                border = BorderStroke(1.dp, BookOutline),
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .springPressScale { clauseScoped = !clauseScoped },
                            ) {
                                Text(
                                    text = if (clauseScoped) "整本记录" else "本句记录",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = InkSecondary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                )
                            }
                        }
                        Surface(
                            shape = CircleShape,
                            color = BookPaperWarm,
                            border = BorderStroke(1.dp, BookOutline),
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .springPressScale { controller.closeTimeMachine() },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                IconCloseCross(color = InkSecondary, size = 14.dp)
                            }
                        }
                    }
                }

                if (pageIllustrations.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "本页插图版本",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = InkSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(pageIllustrations, key = { it.id }) { illu ->
                            val isActive = illu.id == activeIllusId
                            val f = book.illustrationFile(illu)
                            val bmp = remember(f?.absolutePath, f?.lastModified()) { f?.let { decodeFileToImageBitmap(it) } }
                            Box(
                                modifier = Modifier
                                    .size(width = 76.dp, height = 56.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(BookPaperWarm)
                                    .border(if (isActive) 2.dp else 1.dp, if (isActive) Vermilion else BookOutline, RoundedCornerShape(10.dp))
                                    .springPressScale { controller.selectPageIllustration(uiState.currentPageIndex, illu.id) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (bmp != null) {
                                    Image(bitmap = bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = BookRuleLine, thickness = 1.dp)
                Spacer(Modifier.height(12.dp))

                if (clauseScoped && targetClauseId != null) {
                    if (clauseEntries.isEmpty()) {
                        EmptyHistoryBox()
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(clauseEntries, key = { it.snapshotId }) { entry ->
                                ClauseHistoryRow(
                                    entry = entry,
                                    timeText = timeFmt.format(Date(entry.timestampEpochMs)),
                                    bookFont = bookFont,
                                    onPlay = { controller.playClauseHistoryEntry(entry) },
                                    onRestore = { controller.restoreClauseFromSnapshot(entry.snapshotId, targetClauseId) },
                                )
                            }
                        }
                    }
                } else {
                    val snaps = uiState.timeMachineSnapshots
                    if (snaps.isEmpty()) {
                        EmptyHistoryBox()
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(snaps, key = { it.id }) { snap ->
                                val previewEntry = remember(snap, book.bookDir) { snapshotPreviewAudio(book, snap) }
                                SnapshotHistoryRow(
                                    snap = snap,
                                    timeText = timeFmt.format(Date(snap.timestampEpochMs)),
                                    bookFont = bookFont,
                                    hasAudio = previewEntry != null,
                                    onPlay = { previewEntry?.let { controller.playClauseHistoryEntry(it) } },
                                    onRestore = { controller.restoreSnapshot(snap.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHistoryBox() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "0", fontFamily = FontFamily.Serif, fontSize = 16.sp, color = InkSubtle)
    }
}

@Composable
private fun ClauseHistoryRow(
    entry: ClauseHistoryEntry,
    timeText: String,
    bookFont: FontFamily,
    onPlay: () -> Unit,
    onRestore: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = BookPaperWarm,
        border = BorderStroke(1.dp, BookOutline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "${entry.actionLabel} · $timeText",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = InkSubtle,
                )
                Text(
                    text = entry.clause.text,
                    fontFamily = bookFont,
                    fontSize = 15.sp,
                    color = InkPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.wavFile != null) {
                HistoryActionButton(onClick = onPlay, contentDesc = "试听") {
                    IconPlayAudio(color = InkPrimary, size = 15.dp)
                }
            }
            HistoryActionButton(
                onClick = onRestore,
                contentDesc = "恢复",
                modifier = Modifier.testTag("time_machine_restore_${entry.snapshotId}"),
                highlighted = true,
            ) {
                IconRotateCcw(color = Vermilion, size = 15.dp)
            }
        }
    }
}

@Composable
private fun SnapshotHistoryRow(
    snap: TimeMachineSnapshot,
    timeText: String,
    bookFont: FontFamily,
    hasAudio: Boolean,
    onPlay: () -> Unit,
    onRestore: () -> Unit,
) {
    val snippet = snap.clauses.firstOrNull()?.text ?: ""
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = BookPaperWarm,
        border = BorderStroke(1.dp, BookOutline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "${snap.actionLabel} · $timeText · ${snap.clauses.size} 句",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = InkSubtle,
                )
                if (snippet.isNotBlank()) {
                    Text(
                        text = snippet,
                        fontFamily = bookFont,
                        fontSize = 15.sp,
                        color = InkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (hasAudio) {
                HistoryActionButton(onClick = onPlay, contentDesc = "试听") {
                    IconPlayAudio(color = InkPrimary, size = 15.dp)
                }
            }
            HistoryActionButton(
                onClick = onRestore,
                contentDesc = "恢复",
                modifier = Modifier.testTag("time_machine_restore_${snap.id}"),
                highlighted = true,
            ) {
                IconRotateCcw(color = Vermilion, size = 15.dp)
            }
        }
    }
}

@Composable
private fun HistoryActionButton(
    onClick: () -> Unit,
    contentDesc: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = if (highlighted) VermilionSoft else BookPaper,
        border = BorderStroke(1.dp, if (highlighted) Vermilion.copy(alpha = 0.45f) else BookOutline),
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .semantics { contentDescription = contentDesc }
            .springPressScale(pressedScale = 0.88f, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

private fun snapshotPreviewAudio(book: StoryBook, snap: TimeMachineSnapshot): ClauseHistoryEntry? {
    val clause = snap.clauses.firstOrNull { it.audioSpan != null } ?: return null
    val span = clause.audioSpan ?: return null
    val take = snap.takes.firstOrNull { it.id == span.takeId } ?: book.takeById(span.takeId) ?: return null
    val wav = File(book.bookDir, take.wavFileName).takeIf { it.isFile } ?: return null
    return ClauseHistoryEntry(
        snapshotId = snap.id,
        timestampEpochMs = snap.timestampEpochMs,
        actionLabel = snap.actionLabel,
        clause = clause,
        wavFile = wav,
        audioSpan = span,
    )
}
