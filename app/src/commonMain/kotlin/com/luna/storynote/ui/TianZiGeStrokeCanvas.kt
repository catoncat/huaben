package com.luna.storynote.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luna.storynote.literacy.HanziStrokeGlyph
import com.luna.storynote.ui.motion.springPressScale

@Composable
internal fun AnimatedTianZiGeBox(
    ch: Char,
    glyph: HanziStrokeGlyph?,
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
            .springPressScale(pressedScale = 0.95f, onClick = onTapReplay),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
            val guideColor = TianZiRed.copy(alpha = 0.42f)

            drawLine(guideColor, Offset(0f, h / 2f), Offset(w, h / 2f), strokeWidth = 1.2f, pathEffect = dash)
            drawLine(guideColor, Offset(w / 2f, 0f), Offset(w / 2f, h), strokeWidth = 1.2f, pathEffect = dash)
            drawLine(guideColor.copy(alpha = 0.22f), Offset(0f, 0f), Offset(w, h), strokeWidth = 1f, pathEffect = dash)
            drawLine(guideColor.copy(alpha = 0.22f), Offset(w, 0f), Offset(0f, h), strokeWidth = 1f, pathEffect = dash)

            if (parsedPaths.isNotEmpty() && glyph != null) {
                val pad = w * 0.08f
                val scale = (w - pad * 2f) / 1024f

                for (rawPath in parsedPaths) {
                    drawPath(transformMakeMeAHanziPath(rawPath, scale, pad), StrokeGhost)
                }

                val curVal = progress.value
                val completedCount = curVal.toInt().coerceIn(0, parsedPaths.size)
                val activeIdx = completedCount
                val activeFrac = (curVal - completedCount).coerceIn(0f, 1f)

                for (i in 0 until completedCount) {
                    drawPath(transformMakeMeAHanziPath(parsedPaths[i], scale, pad), InkPrimary)
                }

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

private fun transformMakeMeAHanziPath(src: Path, scale: Float, pad: Float): Path {
    val copy = Path().apply { addPath(src) }
    val m = Matrix()
    m.values[Matrix.ScaleX] = scale
    m.values[Matrix.ScaleY] = -scale
    m.values[Matrix.TranslateX] = pad
    m.values[Matrix.TranslateY] = pad + 900f * scale
    copy.transform(m)
    return copy
}
