package com.luna.storynote.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luna.storynote.PinyinAnnotator
import com.luna.storynote.SentenceClip
import com.luna.storynote.StoryParagraphGroup
import com.luna.storynote.WordSpan

internal data class IndexedRubyCell(
    val charText: String,
    val pinyin: String,
    val isHanzi: Boolean,
    val startIndexInSentence: Int,
)

internal data class IndexedRubyCluster(
    val cells: List<IndexedRubyCell>,
)

private val OPEN_PUNCT = setOf('“', '‘', '《', '（', '(', '[', '「')
private val CLOSE_PUNCT = setOf('，', '。', '！', '？', '、', '；', '：', '”', '’', '》', '）', ')', ']', '」', '…', '—', ',', '.', '!', '?')

internal fun buildIndexedClusters(sentenceText: String): List<IndexedRubyCluster> {
    val rawCells = PinyinAnnotator.annotateToCells(sentenceText)
    if (rawCells.isEmpty()) return emptyList()

    val indexed = ArrayList<IndexedRubyCell>(rawCells.size)
    var charPos = 0
    for (c in rawCells) {
        indexed.add(IndexedRubyCell(c.charText, c.pinyin, c.isHanzi, charPos))
        charPos += c.charText.length
    }

    val clusters = mutableListOf<IndexedRubyCluster>()
    val pendingOpen = mutableListOf<IndexedRubyCell>()
    var i = 0
    while (i < indexed.size) {
        val cell = indexed[i]
        val ch = cell.charText.singleOrNull()
        if (!cell.isHanzi && ch != null && ch in OPEN_PUNCT) {
            pendingOpen.add(cell)
            i++
            continue
        }
        if (!cell.isHanzi && ch != null && ch in CLOSE_PUNCT && clusters.isNotEmpty() && pendingOpen.isEmpty()) {
            val prev = clusters.removeAt(clusters.lastIndex)
            clusters.add(IndexedRubyCluster(prev.cells + cell))
            i++
            continue
        }
        val group = ArrayList<IndexedRubyCell>()
        group.addAll(pendingOpen)
        pendingOpen.clear()
        group.add(cell)
        i++
        while (i < indexed.size) {
            val next = indexed[i]
            val nch = next.charText.singleOrNull()
            if (!next.isHanzi && nch != null && nch in CLOSE_PUNCT) {
                group.add(next)
                i++
            } else {
                break
            }
        }
        clusters.add(IndexedRubyCluster(group))
    }
    if (pendingOpen.isNotEmpty()) {
        if (clusters.isNotEmpty()) {
            val prev = clusters.removeAt(clusters.lastIndex)
            clusters.add(IndexedRubyCluster(prev.cells + pendingOpen))
        } else {
            clusters.add(IndexedRubyCluster(pendingOpen.toList()))
        }
    }
    return clusters
}

/**
 * Interactive book paragraph where:
 * - Multiple `SentenceClip`s flow seamlessly together with a 2-character first-line indent (`首行缩进两格`)
 * - Each sentence begins with its subtle `SentenceAcousticDot` (tap to hear voice & reveal 3-icon capsule)
 * - Tapping any Chinese character opens the `田字格` stroke & spelling card for that word
 * - If the active ink cursor sits inside this paragraph, renders the breathing Vermilion cursor + live spoken Pinyin draft!
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InteractiveRubyParagraph(
    group: StoryParagraphGroup,
    bookFont: FontFamily,
    playingSentenceId: String?,
    selectedSentenceId: String?,
    inspectedSentenceId: String?,
    inspectedWordSpan: WordSpan?,
    cursorAfterSentenceId: String?,
    liveDraftText: String,
    isReplacingSentenceId: String?,
    onTapSentenceDot: (SentenceClip) -> Unit,
    onTapCharacter: (SentenceClip, Int) -> Unit,
    onPlaceCursorAfter: (String) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 2-character textbook paragraph indent
        Spacer(Modifier.width(72.dp))

        for (sentence in group.sentences) {
            val isPlaying = playingSentenceId == sentence.id
            val isSelected = selectedSentenceId == sentence.id
            val isBeingReplaced = isReplacingSentenceId == sentence.id
            val sentBg = when {
                isPlaying || isSelected -> HighlightSun
                else -> Color.Transparent
            }

            // Subtle acoustic dot anchored at the start of the sentence
            Box(
                modifier = Modifier
                    .height(50.dp)
                    .padding(end = 2.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(modifier = Modifier.padding(bottom = 4.dp)) {
                    SentenceAcousticDot(
                        isPlaying = isPlaying,
                        isSelected = isSelected,
                        onClick = { onTapSentenceDot(sentence) },
                    )
                }
            }

            if (isBeingReplaced && liveDraftText.isNotBlank()) {
                val draftClusters = remember(liveDraftText) { buildIndexedClusters(liveDraftText) }
                for (cluster in draftClusters) {
                    ClusterCellRow(
                        cluster = cluster,
                        bookFont = bookFont,
                        hanziSize = 25.sp,
                        pinyinSize = 12.sp,
                        hanziWeight = FontWeight.Normal,
                        hanziColor = Vermilion,
                        pinyinColor = Vermilion.copy(alpha = 0.8f),
                        cellBackground = { VermilionSoft },
                        onClickCell = null,
                    )
                }
            } else {
                val clusters = remember(sentence.text) { buildIndexedClusters(sentence.text) }
                for (cluster in clusters) {
                    ClusterCellRow(
                        cluster = cluster,
                        bookFont = bookFont,
                        hanziSize = 25.sp,
                        pinyinSize = 12.sp,
                        hanziWeight = FontWeight.Normal,
                        hanziColor = InkPrimary,
                        pinyinColor = InkPinyin,
                        cellBackground = { cell ->
                            val inInspectedWord = inspectedSentenceId == sentence.id &&
                                inspectedWordSpan != null &&
                                cell.startIndexInSentence in inspectedWordSpan.startCharIndex until inspectedWordSpan.endCharIndex
                            when {
                                inInspectedWord -> HighlightWord
                                sentBg != Color.Transparent -> sentBg
                                else -> Color.Transparent
                            }
                        },
                        onClickCell = { cell ->
                            if (cell.isHanzi) {
                                onTapCharacter(sentence, cell.startIndexInSentence)
                            } else {
                                onPlaceCursorAfter(sentence.id)
                            }
                        },
                    )
                }
            }

            // If cursor sits right after this sentence, render breathing ink bar + live draft right in-flow!
            if (cursorAfterSentenceId == sentence.id && isReplacingSentenceId == null) {
                BreathingInkCursorWithDraft(
                    liveDraftText = liveDraftText,
                    bookFont = bookFont,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BreathingInkCursorWithDraft(
    liveDraftText: String,
    bookFont: FontFamily,
) {
    val inf = rememberInfiniteTransition(label = "inkCursor")
    val alpha by inf.animateFloat(
        initialValue = 0.25f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(680), RepeatMode.Reverse),
        label = "cursorBlink",
    )

    if (liveDraftText.isNotBlank()) {
        val draftClusters = remember(liveDraftText) { buildIndexedClusters(liveDraftText) }
        for (cluster in draftClusters) {
            ClusterCellRow(
                cluster = cluster,
                bookFont = bookFont,
                hanziSize = 25.sp,
                pinyinSize = 12.sp,
                hanziWeight = FontWeight.Normal,
                hanziColor = InkDraftWet,
                pinyinColor = InkPinyin,
                cellBackground = { Color.Transparent },
                onClickCell = null,
            )
        }
    }

    Box(
        modifier = Modifier
            .height(50.dp)
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = 3.dp)
                .width(2.2.dp)
                .height(27.dp)
                .alpha(alpha)
                .clip(RoundedCornerShape(1.dp))
                .background(Vermilion),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RubyBookTitle(
    title: String,
    bookFont: FontFamily,
    modifier: Modifier = Modifier,
    hanziSize: TextUnit = 26.sp,
    pinyinSize: TextUnit = 12.sp,
    centerAlign: Boolean = true,
) {
    val clusters = remember(title) { buildIndexedClusters(title) }
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (centerAlign) Arrangement.Center else Arrangement.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (cluster in clusters) {
            ClusterCellRow(
                cluster = cluster,
                bookFont = bookFont,
                hanziSize = hanziSize,
                pinyinSize = pinyinSize,
                hanziWeight = FontWeight.Bold,
                hanziColor = InkPrimary,
                pinyinColor = InkPinyin,
                minHanziWidth = 36.dp,
                pinyinMinHeight = 20.dp,
                cellBackground = { Color.Transparent },
                onClickCell = null,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RubyCoverPlateTitle(
    title: String,
    bookFont: FontFamily,
    modifier: Modifier = Modifier,
) {
    val clusters = remember(title) { buildIndexedClusters(title) }
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (cluster in clusters) {
            ClusterCellRow(
                cluster = cluster,
                bookFont = bookFont,
                hanziSize = 18.sp,
                pinyinSize = 9.8.sp,
                hanziWeight = FontWeight.Bold,
                hanziColor = InkPrimary,
                pinyinColor = InkPinyin,
                minHanziWidth = 26.dp,
                pinyinMinHeight = 16.dp,
                cellBackground = { Color.Transparent },
                onClickCell = null,
            )
        }
    }
}

@Composable
private fun ClusterCellRow(
    cluster: IndexedRubyCluster,
    bookFont: FontFamily,
    hanziSize: TextUnit,
    pinyinSize: TextUnit,
    hanziWeight: FontWeight,
    hanziColor: Color,
    pinyinColor: Color,
    minHanziWidth: Dp = 36.dp,
    pinyinMinHeight: Dp = 20.dp,
    cellBackground: (IndexedRubyCell) -> Color,
    onClickCell: ((IndexedRubyCell) -> Unit)?,
) {
    Row(verticalAlignment = Alignment.Bottom) {
        for (cell in cluster.cells) {
            val bg = cellBackground(cell)
            val minW = if (cell.isHanzi) minHanziWidth else 14.dp
            val clickMod = if (onClickCell != null) {
                Modifier.clickable { onClickCell(cell) }
            } else {
                Modifier
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(bg)
                    .then(clickMod)
                    .widthIn(min = minW)
                    .padding(horizontal = if (cell.isHanzi) 1.dp else 0.dp, vertical = 1.dp),
            ) {
                Box(
                    modifier = Modifier
                        .defaultMinSize(minHeight = pinyinMinHeight)
                        .padding(bottom = 2.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (cell.pinyin.isNotEmpty()) {
                        Text(
                            text = cell.pinyin,
                            fontFamily = FontFamily.SansSerif,
                            fontSize = pinyinSize,
                            lineHeight = (pinyinSize.value + 4f).sp,
                            letterSpacing = (-0.2).sp,
                            fontWeight = FontWeight.Medium,
                            color = pinyinColor,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Text(
                    text = cell.charText,
                    fontFamily = bookFont,
                    fontSize = hanziSize,
                    lineHeight = (hanziSize.value + 4f).sp,
                    fontWeight = hanziWeight,
                    color = hanziColor,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
