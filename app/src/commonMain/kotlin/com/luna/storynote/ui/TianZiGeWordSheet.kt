package com.luna.storynote.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luna.storynote.ActiveWordInspection
import com.luna.storynote.BookStudioController
import com.luna.storynote.HanziStrokeRepository
import com.luna.storynote.LiteracyEngine
import com.luna.storynote.MicIntent
import com.luna.storynote.StudioUiState

/**
 * Pure, tactile Grade-1 Character & Spelling Card (`田字格认字拼读卡`):
 * - Zero redundant dictionary prose (the child spoke this word himself!)
 * - Authentic 60fps stroke-by-stroke Kaiti calligraphy animation inside a dashed `田字格`
 * - Exact Grade-1 声母 / 介母 / 韵母 / 整体认读音节 spelling tiles
 * - One-tap homophone replacement chips + mini voice-replace button
 * - Collect into `字词盒` toggle
 */
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
    var selectedCharIdx by remember(word) { mutableIntStateOf(0) }
    var replayTrigger by remember(word, selectedCharIdx) { mutableIntStateOf(0) }

    val activeChar = word.getOrElse(selectedCharIdx) { word.first() }
    val activePinyin = pinyins.getOrElse(selectedCharIdx) { "" }
    val spelling = remember(activePinyin) { LiteracyEngine.analyzeSpelling(activePinyin) }
    val glyph = remember(activeChar) { HanziStrokeRepository.getGlyph(activeChar) }
    val singleCharHomophones = remember(activeChar, activePinyin) {
        LiteracyEngine.suggestHomophones(activeChar.toString(), listOf(activePinyin))
    }
    val hasWholeWordHomophones = inspection.homophones.isNotEmpty()
    val displayedHomophones = if (hasWholeWordHomophones) inspection.homophones else singleCharHomophones

    val isCollected = uiState.collectedWords.any { it.word == word }
    val isListeningForWordSwap = uiState.activeMicIntent is MicIntent.ReplaceWord

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x551C1712))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { controller.dismissSelectionAndPopovers() },
        contentAlignment = Alignment.Center,
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
                // Top bar: Multi-char selector tabs + Speaker + Collect-to-WordBox + Close
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
                                modifier = Modifier.clickable { selectedCharIdx = idx },
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
                        ) {
                            IconSpeakerMandarin(color = InkPrimary, size = 19.dp)
                        }
                        CircularChromeIconButton(
                            onClick = { controller.toggleCollectWord(inspection) },
                            containerColor = if (isCollected) JadeSoft else BookPaperWarm,
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
                        ) {
                            IconCloseCross(color = InkSubtle, size = 15.dp)
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Main Stage: TianZiGe Stroke Animation + Grade-1 Pinyin Spelling Tiles
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
                        // Spelling decomposition tiles (`k · ǒng → kǒng`)
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

                // Bottom Bar: Homophone quick-fix chips + Speak-to-replace-word mic button
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
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = BookPaperWarm,
                                border = BorderStroke(1.dp, BookOutline),
                                modifier = Modifier.clickable {
                                    if (hasWholeWordHomophones) {
                                        controller.applyHomophoneReplacement(inspection, cand)
                                    } else {
                                        controller.applySingleCharHomophoneReplacement(
                                            inspection = inspection,
                                            charOffsetInWord = selectedCharIdx,
                                            candidateChar = cand,
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

                    // Speak replacement word button
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(if (isListeningForWordSwap) InkPrimary else Vermilion)
                            .clickable {
                                if (isListeningForWordSwap) {
                                    controller.stopRecording()
                                } else {
                                    controller.startRespeakWord(inspection.sentenceId, inspection.span)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        IconMicCapsule(color = Color.White, size = 20.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatedTianZiGeBox(
    ch: Char,
    glyph: com.luna.storynote.HanziStrokeGlyph?,
    bookFont: FontFamily,
    replayKey: Int,
    onTapReplay: () -> Unit,
) {
    val strokeCount = glyph?.strokes?.size ?: 0
    val progress = remember(ch, replayKey) { Animatable(0f) }

    LaunchedEffect(ch, replayKey, strokeCount) {
        if (strokeCount > 0) {
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = strokeCount.toFloat(),
                animationSpec = tween(
                    durationMillis = (strokeCount * 360).coerceIn(500, 4200),
                    easing = LinearEasing,
                ),
            )
        }
    }

    val parsedPaths = remember(ch, glyph) {
        glyph?.strokes?.map { s ->
            try {
                PathParser().parsePathString(s.svgPath).toPath()
            } catch (_: Exception) {
                Path()
            }
        }.orEmpty()
    }

    Box(
        modifier = Modifier
            .size(152.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFFFFDF8))
            .border(1.8.dp, TianZiRed, RoundedCornerShape(14.dp))
            .clickable(onClick = onTapReplay),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
            val guideColor = TianZiRed.copy(alpha = 0.42f)

            // Horizontal, Vertical & Diagonal dashed MiZiGe / TianZiGe guides
            drawLine(guideColor, Offset(0f, h / 2f), Offset(w, h / 2f), strokeWidth = 1.2f, pathEffect = dash)
            drawLine(guideColor, Offset(w / 2f, 0f), Offset(w / 2f, h), strokeWidth = 1.2f, pathEffect = dash)
            drawLine(guideColor.copy(alpha = 0.22f), Offset(0f, 0f), Offset(w, h), strokeWidth = 1f, pathEffect = dash)
            drawLine(guideColor.copy(alpha = 0.22f), Offset(w, 0f), Offset(0f, h), strokeWidth = 1f, pathEffect = dash)

            if (parsedPaths.isNotEmpty() && glyph != null) {
                val pad = w * 0.08f
                val scale = (w - pad * 2f) / 1024f

                // 1. Ghost background of all strokes in soft warm stone
                for (rawPath in parsedPaths) {
                    val transformed = transformMakeMeAHanziPath(rawPath, scale, pad)
                    drawPath(transformed, StrokeGhost)
                }

                val curVal = progress.value
                val completedCount = curVal.toInt().coerceIn(0, parsedPaths.size)
                val activeIdx = completedCount
                val activeFrac = (curVal - completedCount).coerceIn(0f, 1f)

                // 2. Completed strokes in rich crisp ink
                for (i in 0 until completedCount) {
                    val transformed = transformMakeMeAHanziPath(parsedPaths[i], scale, pad)
                    drawPath(transformed, InkPrimary)
                }

                // 3. Active stroke animated progressively along its median centerline in Vermilion brush
                if (activeIdx < glyph.strokes.size && activeFrac > 0.02f) {
                    val med = glyph.strokes[activeIdx].medianPoints
                    if (med.size >= 2) {
                        val medPath = Path()
                        val totalPts = med.size
                        val exactPos = activeFrac * (totalPts - 1)
                        val fullSegs = exactPos.toInt()
                        val segRem = exactPos - fullSegs

                        fun mapPt(pt: Pair<Float, Float>): Offset =
                            Offset(pad + pt.first * (w - pad * 2f), pad + pt.second * (h - pad * 2f))

                        val p0 = mapPt(med[0])
                        medPath.moveTo(p0.x, p0.y)
                        for (k in 1..fullSegs.coerceAtMost(med.lastIndex)) {
                            val pk = mapPt(med[k])
                            medPath.lineTo(pk.x, pk.y)
                        }
                        if (fullSegs + 1 < med.size) {
                            val a = mapPt(med[fullSegs])
                            val b = mapPt(med[fullSegs + 1])
                            medPath.lineTo(a.x + (b.x - a.x) * segRem, a.y + (b.y - a.y) * segRem)
                        }
                        drawPath(
                            path = medPath,
                            color = Vermilion,
                            style = Stroke(width = w * 0.072f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }
            }
        }

        if (parsedPaths.isEmpty()) {
            Text(
                text = ch.toString(),
                fontFamily = bookFont,
                fontSize = 92.sp,
                color = InkPrimary,
            )
        }
    }
}

/**
 * MakeMeAHanzi SVG paths live in `(0..1024)` with `scale(1, -1) translate(0, -900)`.
 * So `screenX = pad + x * scale`, `screenY = pad + (900 - y) * scale`.
 */
private fun transformMakeMeAHanziPath(src: Path, scale: Float, pad: Float): Path {
    val copy = Path().apply { addPath(src) }
    val m = Matrix()
    // Compose Matrix applies post-multiplied operations in reverse or direct element mapping:
    // x' = scale * x + pad
    // y' = -scale * y + (pad + 900f * scale)
    m.values[Matrix.ScaleX] = scale
    m.values[Matrix.ScaleY] = -scale
    m.values[Matrix.TranslateX] = pad
    m.values[Matrix.TranslateY] = pad + 900f * scale
    copy.transform(m)
    return copy
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PinyinSpellingStage(spelling: com.luna.storynote.PinyinSpellingBreakdown) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (spelling.isWholeSyllable) {
            Surface(
                shape = CircleShape,
                color = JadeSoft,
            ) {
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
