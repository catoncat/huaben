package com.luna.storynote.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luna.storynote.literacy.RubyCharCell
import com.luna.storynote.literacy.RubyCluster
import com.luna.storynote.literacy.RubyClusterer
import com.luna.storynote.literacy.WordSpan
import com.luna.storynote.model.Clause
import com.luna.storynote.model.StoryParagraphGroup
import com.luna.storynote.ui.icons.SentenceAcousticDot
import com.luna.storynote.ui.motion.StorySprings
import com.luna.storynote.ui.motion.clauseLandedSettle
import com.luna.storynote.ui.motion.clauseRevisedHighlight

private val SoftTypoUnderlineColor = Color(0xFFD4883A)

internal fun resolveSoftAnnotationForCell(
    clause: Clause,
    charIndex: Int,
): Pair<String?, Boolean> {
    val ann = clause.softAnnotations.firstOrNull {
        it.charIndex >= 0 && it.length > 0 && charIndex in it.charIndex until (it.charIndex + it.length)
    } ?: return null to false
    val correctedPy = ann.correctedPinyin?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
        val parts = raw.split(Regex("\\s+"))
        when {
            parts.size == ann.length -> parts.getOrNull(charIndex - ann.charIndex)
            ann.length == 1 -> raw
            else -> null
        }
    }
    return correctedPy to !ann.suggestedWord.isNullOrBlank()
}

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
    justLandedClauseId: String? = null,
    revisedClauseId: String? = null,
    voiceRevisingClauseId: String? = null,
    onTapSentenceDot: (Clause) -> Unit,
    onTapCharacter: (Clause, Int) -> Unit,
    onPlaceCursorAfter: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.78f, stiffness = 360f)),
        horizontalArrangement = Arrangement.Start,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        group.clauses.forEachIndexed { clauseIdx, clause ->
            val isPlaying = playingSentenceId == clause.id
            val isSelected = selectedSentenceId == clause.id
            val isBeingReplaced = isReplacingSentenceId == clause.id
            val isVoiceRevising = voiceRevisingClauseId == clause.id
            val isJustRevised = revisedClauseId == clause.id
            val sentBg = when {
                isVoiceRevising -> VermilionSoft
                isPlaying || isSelected -> HighlightSun
                else -> Color.Transparent
            }
            val motionMod = when {
                justLandedClauseId == clause.id -> Modifier.clauseLandedSettle(isJustLanded = true)
                isJustRevised -> Modifier.clauseRevisedHighlight(isJustRevised = true)
                else -> Modifier
            }

            if (clauseIdx == 0) {
                Spacer(Modifier.width(48.dp))
            }

            Box(
                modifier = Modifier
                    .height(50.dp)
                    .padding(start = if (clauseIdx == 0) 0.dp else 4.dp, end = 2.dp)
                    .testTag("clause_row_${clause.id}")
                    .semantics { text = AnnotatedString(clause.text) }
                    .then(motionMod),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(modifier = Modifier.padding(bottom = 4.dp)) {
                    SentenceAcousticDot(
                        isPlaying = isPlaying,
                        isSelected = isSelected,
                        onClick = { onTapSentenceDot(clause) },
                        modifier = Modifier
                            .testTag("clause_dot_${clause.id}")
                            .semantics { contentDescription = "句子" },
                    )
                }
            }

            if (isBeingReplaced && liveDraftText.isNotBlank()) {
                val draftClusters = remember(liveDraftText) { RubyClusterer.annotateAndCluster(liveDraftText) }
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
                        modifier = motionMod,
                    )
                }
            } else {
                val clusters = remember(clause.text) { RubyClusterer.annotateAndCluster(clause.text) }
                for (cluster in clusters) {
                    ClusterCellRow(
                        cluster = cluster,
                        bookFont = bookFont,
                        hanziSize = 25.sp,
                        pinyinSize = 12.sp,
                        hanziWeight = FontWeight.Normal,
                        hanziColor = InkPrimary,
                        pinyinColor = InkPinyin,
                        pinyinOverride = { cell ->
                            if (cell.isHanzi) resolveSoftAnnotationForCell(clause, cell.startIndexInSentence).first else null
                        },
                        hasSoftUnderline = { cell ->
                            cell.isHanzi && resolveSoftAnnotationForCell(clause, cell.startIndexInSentence).second
                        },
                        cellBackground = { cell ->
                            val inInspectedWord = inspectedSentenceId == clause.id &&
                                inspectedWordSpan != null &&
                                cell.startIndexInSentence in inspectedWordSpan.startCharIndex until inspectedWordSpan.endCharIndex
                            when {
                                inInspectedWord -> HighlightWord
                                sentBg != Color.Transparent -> sentBg
                                else -> Color.Transparent
                            }
                        },
                        onClickCell = { cell ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (cell.isHanzi) {
                                onTapCharacter(clause, cell.startIndexInSentence)
                            } else {
                                onPlaceCursorAfter(clause.id)
                            }
                        },
                        modifier = motionMod,
                    )
                }
            }

            if (cursorAfterSentenceId == clause.id && isReplacingSentenceId == null) {
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
        val draftClusters = remember(liveDraftText) { RubyClusterer.annotateAndCluster(liveDraftText) }
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
fun RubyCoverPlateTitle(
    title: String,
    bookFont: FontFamily,
    modifier: Modifier = Modifier,
) {
    val clusters = remember(title) { RubyClusterer.annotateAndCluster(title) }
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
internal fun ClusterCellRow(
    cluster: RubyCluster,
    bookFont: FontFamily,
    hanziSize: TextUnit,
    pinyinSize: TextUnit,
    hanziWeight: FontWeight,
    hanziColor: Color,
    pinyinColor: Color,
    minHanziWidth: Dp = 36.dp,
    pinyinMinHeight: Dp = 20.dp,
    pinyinOverride: ((RubyCharCell) -> String?)? = null,
    hasSoftUnderline: ((RubyCharCell) -> Boolean)? = null,
    cellBackground: (RubyCharCell) -> Color,
    onClickCell: ((RubyCharCell) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        for (cell in cluster.cells) {
            val bg = cellBackground(cell)
            val minW = if (cell.isHanzi) minHanziWidth else 14.dp
            val clickMod = if (onClickCell != null) {
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onClickCell(cell) }
            } else {
                Modifier
            }
            val effectivePinyin = pinyinOverride?.invoke(cell) ?: cell.pinyin
            val showUnderline = hasSoftUnderline?.invoke(cell) == true

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
                    if (effectivePinyin.isNotEmpty()) {
                        Text(
                            text = effectivePinyin,
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
                if (showUnderline) {
                    Box(
                        modifier = Modifier
                            .padding(top = 1.dp)
                            .width(18.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(SoftTypoUnderlineColor.copy(alpha = 0.78f)),
                    )
                }
            }
        }
    }
}
