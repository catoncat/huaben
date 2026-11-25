package com.luna.storynote.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luna.storynote.BookStudioController
import com.luna.storynote.literacy.HanziStrokeRepository
import com.luna.storynote.literacy.LiteracyEngine
import com.luna.storynote.literacy.PinyinSpellingBreakdown
import com.luna.storynote.model.ActiveWordInspection
import com.luna.storynote.model.MicIntent
import com.luna.storynote.model.StudioUiState
import com.luna.storynote.ui.icons.IconCloseCross
import com.luna.storynote.ui.icons.IconMicCapsule
import com.luna.storynote.ui.icons.IconSpeakerMandarin
import com.luna.storynote.ui.icons.IconWordBoxDeck
import com.luna.storynote.ui.motion.ModalPaperSheet
import com.luna.storynote.ui.motion.StorySprings
import com.luna.storynote.ui.motion.springPressScale

internal sealed interface CandidateReplacement {
    data class WholeWord(val candidate: String) : CandidateReplacement
    data class SingleChar(val charOffsetInWord: Int, val candidateChar: String) : CandidateReplacement
    data class ClauseSpan(val startIndexInClause: Int, val endIndexInClause: Int, val candidate: String) : CandidateReplacement
}

internal fun resolveCandidateReplacement(
    inspection: ActiveWordInspection,
    selectedCharIdx: Int,
    candidate: String,
): CandidateReplacement {
    val word = inspection.span.word
    val trimmedCand = candidate.trim()
    val softTrimmed = inspection.softSuggestion?.trim()
    val isSoftPriority = softTrimmed != null && trimmedCand == softTrimmed
    if (isSoftPriority && inspection.softAnnotationLength > 1 && inspection.softAnnotationLength == word.length) {
        return CandidateReplacement.WholeWord(trimmedCand)
    }
    if (isSoftPriority && inspection.softAnnotationLength in 2 until word.length && inspection.softAnnotationOffsetInWord != null) {
        val start = (inspection.span.startCharIndex + inspection.softAnnotationOffsetInWord)
            .coerceIn(inspection.span.startCharIndex, (inspection.span.endCharIndex - 1).coerceAtLeast(inspection.span.startCharIndex))
        val end = (start + inspection.softAnnotationLength).coerceIn(start + 1, inspection.span.endCharIndex)
        return CandidateReplacement.ClauseSpan(start, end, trimmedCand)
    }
    if (trimmedCand.length == 1 && word.length > 1) {
        val lastIdx = (word.length - 1).coerceAtLeast(0)
        val targetOffset = if (isSoftPriority && inspection.softAnnotationOffsetInWord != null) {
            inspection.softAnnotationOffsetInWord.coerceIn(0, lastIdx)
        } else {
            selectedCharIdx.coerceIn(0, lastIdx)
        }
        return CandidateReplacement.SingleChar(targetOffset, trimmedCand)
    }
    return CandidateReplacement.WholeWord(trimmedCand)
}

internal fun rankHomophoneCandidates(
    inspection: ActiveWordInspection,
    singleCharHomophones: List<String>,
    activeChar: Char,
): List<String> {
    val word = inspection.span.word
    val base = if (inspection.homophones.isNotEmpty()) inspection.homophones else singleCharHomophones
    val offset = inspection.softAnnotationOffsetInWord
    val targetSnippet = when {
        offset != null && inspection.softAnnotationLength == 1 -> word.getOrNull(offset)?.toString()
        offset != null && inspection.softAnnotationLength > 1 ->
            word.substring(offset.coerceIn(0, word.length), (offset + inspection.softAnnotationLength).coerceIn(0, word.length))
        else -> null
    }
    val soft = inspection.softSuggestion?.trim()?.takeIf {
        it.isNotEmpty() && it != word && it != (targetSnippet ?: activeChar.toString())
    }
    return if (soft != null) {
        (listOf(soft) + base).distinct().take(6)
    } else {
        base.distinct().take(6)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TianZiGeWordOverlay(
    inspection: ActiveWordInspection,
    uiState: StudioUiState,
    controller: BookStudioController,
    bookFont: FontFamily,
) {
    val word = inspection.span.word
    val pinyins = inspection.span.pinyins
    val initialIdx = inspection.initialCharIndexInWord.coerceIn(0, (word.length - 1).coerceAtLeast(0))
    var selectedCharIdx by remember(inspection.clauseId, inspection.span.startCharIndex, word, initialIdx) {
        mutableIntStateOf(initialIdx)
    }
    var replayTrigger by remember(word, selectedCharIdx) { mutableIntStateOf(0) }

    val activeChar = word.getOrElse(selectedCharIdx) { word.first() }
    val activePinyin = pinyins.getOrElse(selectedCharIdx) { "" }
    val spelling = remember(activePinyin) { LiteracyEngine.analyzeSpelling(activePinyin) }
    val glyph = remember(activeChar) { HanziStrokeRepository.getGlyph(activeChar) }
    val singleCharHomophones = remember(activeChar, activePinyin) {
        LiteracyEngine.suggestHomophones(activeChar.toString(), listOf(activePinyin))
    }
    val displayedHomophones = remember(inspection, singleCharHomophones, activeChar) {
        rankHomophoneCandidates(inspection, singleCharHomophones, activeChar)
    }

    val isCollected = uiState.collectedWords.any { it.word == word }
    val isListeningForWordSwap = uiState.activeMicIntent is MicIntent.ReplaceWord

    val bookmarkScale = remember { Animatable(1f) }
    LaunchedEffect(isCollected) {
        if (isCollected) {
            bookmarkScale.snapTo(1.24f)
            bookmarkScale.animateTo(1f, animationSpec = StorySprings.BookmarkBounce)
        }
    }

    ModalPaperSheet(
        onDismiss = { controller.dismissSelectionAndPopovers() },
        scrimMaxAlpha = 0.38f,
    ) {
        Surface(
            modifier = Modifier
                .padding(20.dp)
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
            shape = RoundedCornerShape(26.dp),
            color = BookPaper,
            border = BorderStroke(1.5.dp, BookOutline),
            shadowElevation = 16.dp,
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (idx in word.indices) {
                            val isSel = idx == selectedCharIdx
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSel) HighlightSun else BookPaperWarm,
                                border = BorderStroke(1.5.dp, if (isSel) Vermilion else BookOutline),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .testTag("word_sheet_tab_$idx")
                                    .springPressScale(pressedScale = 0.92f) { selectedCharIdx = idx },
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        text = pinyins.getOrElse(idx) { "" },
                                        fontSize = 11.sp,
                                        color = InkPinyin,
                                    )
                                    Text(
                                        text = word[idx].toString(),
                                        fontFamily = bookFont,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = InkPrimary,
                                    )
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularChromeIconButton(
                            onClick = { controller.env.speakStandardWord(word) },
                            containerColor = BookPaperWarm,
                            modifier = Modifier
                                .testTag("word_sheet_speak")
                                .semantics { contentDescription = "读音" },
                        ) {
                            IconSpeakerMandarin(color = InkPrimary, size = 19.dp)
                        }
                        CircularChromeIconButton(
                            onClick = { controller.toggleCollectWord(inspection) },
                            containerColor = if (isCollected) JadeSoft else BookPaperWarm,
                            modifier = Modifier
                                .graphicsLayer {
                                    scaleX = bookmarkScale.value
                                    scaleY = bookmarkScale.value
                                }
                                .testTag("word_sheet_collect")
                                .semantics { contentDescription = "加入字词盒" },
                        ) {
                            IconWordBoxDeck(
                                filled = isCollected,
                                color = if (isCollected) JadeInk else InkPrimary,
                                size = 19.dp,
                            )
                        }
                        CircularChromeIconButton(
                            onClick = { controller.dismissSelectionAndPopovers() },
                            containerColor = BookPaperWarm,
                            modifier = Modifier
                                .testTag("word_sheet_close")
                                .semantics { contentDescription = "关闭" },
                        ) {
                            IconCloseCross(color = InkSubtle, size = 15.dp)
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedTianZiGeBox(
                        ch = activeChar,
                        glyph = glyph,
                        bookFont = bookFont,
                        replayKey = replayTrigger,
                        onTapReplay = {
                            replayTrigger++
                            controller.env.speakStandardWord(activeChar.toString())
                        },
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PinyinSpellingStage(spelling = spelling)
                        if (glyph != null) {
                            Text(
                                text = "${glyph.strokes.size} 画",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = InkSubtle,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = BookRuleLine, thickness = 1.dp)
                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (cand in displayedHomophones) {
                            val isSoftPriority = cand.trim() == inspection.softSuggestion?.trim()
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSoftPriority) HighlightSun else BookPaperWarm,
                                border = BorderStroke(1.dp, if (isSoftPriority) TianZiRed else BookOutline),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .springPressScale(pressedScale = 0.92f) {
                                        when (val action = resolveCandidateReplacement(inspection, selectedCharIdx, cand)) {
                                            is CandidateReplacement.WholeWord ->
                                                controller.applyHomophoneReplacement(inspection, action.candidate)
                                            is CandidateReplacement.SingleChar ->
                                                controller.applySingleCharHomophoneReplacement(
                                                    inspection = inspection,
                                                    charOffsetInWord = action.charOffsetInWord,
                                                    candidateChar = action.candidateChar,
                                                )
                                            is CandidateReplacement.ClauseSpan ->
                                                controller.applyClauseSpanReplacement(
                                                    clauseId = inspection.clauseId,
                                                    startIndex = action.startIndexInClause,
                                                    endIndex = action.endIndexInClause,
                                                    replacement = action.candidate,
                                                )
                                        }
                                    },
                            ) {
                                Text(
                                    text = cand,
                                    fontFamily = bookFont,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = InkPrimary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(10.dp))

                    Surface(
                        shape = CircleShape,
                        color = if (isListeningForWordSwap) InkPrimary else Vermilion,
                        shadowElevation = 2.dp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .testTag("word_sheet_mic")
                            .semantics { contentDescription = "重念换词" }
                            .springPressScale(pressedScale = 0.92f) {
                                if (isListeningForWordSwap) {
                                    controller.stopRecording()
                                } else {
                                    controller.startRespeakWord(inspection.clauseId, inspection.span)
                                }
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            IconMicCapsule(color = Color.White, size = 16.dp)
                            Text(
                                text = if (isListeningForWordSwap) "正在听…" else "重念换词",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PinyinSpellingStage(spelling: PinyinSpellingBreakdown) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (spelling.isWholeSyllable) {
            Surface(shape = CircleShape, color = JadeSoft) {
                Text(
                    text = "整体认读",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = JadeInk,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
            SpellingTile(text = spelling.fullSyllable, highlight = true)
        } else if (spelling.initial.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SpellingTile(text = spelling.initial, highlight = false)
                Text("·", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = InkSubtle, modifier = Modifier.align(Alignment.CenterVertically))
                if (spelling.medial.isNotEmpty()) {
                    SpellingTile(text = spelling.medial, highlight = false)
                    Text("·", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = InkSubtle, modifier = Modifier.align(Alignment.CenterVertically))
                }
                SpellingTile(text = spelling.finalPart, highlight = false)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("→", fontSize = 16.sp, color = InkSubtle)
                SpellingTile(text = spelling.fullSyllable, highlight = true)
            }
        } else {
            SpellingTile(text = spelling.fullSyllable, highlight = true)
        }
    }
}

@Composable
private fun SpellingTile(text: String, highlight: Boolean) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (highlight) VermilionSoft else BookPaperWarm,
        border = BorderStroke(1.dp, if (highlight) Vermilion.copy(alpha = 0.45f) else BookOutline),
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.SansSerif,
            fontSize = if (highlight) 22.sp else 18.sp,
            fontWeight = FontWeight.Bold,
            color = if (highlight) Vermilion else InkPrimary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}
