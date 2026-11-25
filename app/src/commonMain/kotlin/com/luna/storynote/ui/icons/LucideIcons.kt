package com.luna.storynote.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.luna.storynote.ui.FoilGold
import com.luna.storynote.ui.InkPrimary
import com.luna.storynote.ui.InkSecondary
import com.luna.storynote.ui.Vermilion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal inline fun DrawScope.lucideCanvas(
    strokeFactor: Float = 2f,
    block: DrawScope.(s: Float, stroke: Stroke) -> Unit,
) {
    val s = size.minDimension / 24f
    val stroke = Stroke(width = strokeFactor * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
    block(s, stroke)
}

@Composable
fun IconChevronBack(color: Color = InkPrimary, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        lucideCanvas { s, stroke ->
            val p = Path().apply {
                moveTo(15f * s, 18f * s)
                lineTo(9f * s, 12f * s)
                lineTo(15f * s, 6f * s)
            }
            drawPath(p, color, style = stroke)
        }
    }
}

@Composable
fun IconChevronForward(color: Color = InkPrimary, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        lucideCanvas { s, stroke ->
            val p = Path().apply {
                moveTo(9f * s, 18f * s)
                lineTo(15f * s, 12f * s)
                lineTo(9f * s, 6f * s)
            }
            drawPath(p, color, style = stroke)
        }
    }
}

@Composable
fun IconPlayAudio(color: Color = InkPrimary, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        lucideCanvas { s, stroke ->
            val p = Path().apply {
                moveTo(6f * s, 3f * s)
                lineTo(20f * s, 12f * s)
                lineTo(6f * s, 21f * s)
                close()
            }
            drawPath(p, color.copy(alpha = 0.16f), style = Fill)
            drawPath(p, color, style = stroke)
        }
    }
}

@Composable
fun IconPauseAudio(color: Color = Vermilion, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        lucideCanvas { s, stroke ->
            drawRoundRect(color, Offset(6f * s, 4f * s), Size(4f * s, 16f * s), CornerRadius(1f * s, 1f * s), style = stroke)
            drawRoundRect(color, Offset(14f * s, 4f * s), Size(4f * s, 16f * s), CornerRadius(1f * s, 1f * s), style = stroke)
        }
    }
}

@Composable
fun IconSpeakerMandarin(color: Color = InkPrimary, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        lucideCanvas { s, stroke ->
            val cone = Path().apply {
                moveTo(11f * s, 5f * s)
                lineTo(6f * s, 9f * s)
                lineTo(2f * s, 9f * s)
                lineTo(2f * s, 15f * s)
                lineTo(6f * s, 15f * s)
                lineTo(11f * s, 19f * s)
                close()
            }
            drawPath(cone, color, style = stroke)
            drawArc(color, -45f, 90f, false, Offset(11.5f * s, 8.5f * s), Size(7f * s, 7f * s), style = stroke)
            drawArc(color, -45f, 90f, false, Offset(10.5f * s, 5f * s), Size(12f * s, 14f * s), style = stroke)
        }
    }
}

@Composable
fun IconWordBoxDeck(filled: Boolean = false, color: Color = InkPrimary, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        lucideCanvas { s, stroke ->
            val p = Path().apply {
                moveTo(19f * s, 21f * s)
                lineTo(12f * s, 17f * s)
                lineTo(5f * s, 21f * s)
                lineTo(5f * s, 5f * s)
                cubicTo(5f * s, 3.9f * s, 5.9f * s, 3f * s, 7f * s, 3f * s)
                lineTo(17f * s, 3f * s)
                cubicTo(18.1f * s, 3f * s, 19f * s, 3.9f * s, 19f * s, 5f * s)
                close()
            }
            if (filled) drawPath(p, color, style = Fill)
            drawPath(p, color, style = stroke)
        }
    }
}

@Composable
fun IconCloseCross(color: Color = InkSecondary, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        lucideCanvas { s, stroke ->
            drawLine(color, Offset(18f * s, 6f * s), Offset(6f * s, 18f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(6f * s, 6f * s), Offset(18f * s, 18f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun IconPlus(color: Color = Vermilion, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        lucideCanvas { s, stroke ->
            drawLine(color, Offset(12f * s, 5f * s), Offset(12f * s, 19f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(5f * s, 12f * s), Offset(19f * s, 12f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun IconRotateCcw(color: Color = InkPrimary, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        lucideCanvas { s, stroke ->
            drawArc(color, -155f, 290f, false, Offset(3f * s, 3f * s), Size(18f * s, 18f * s), style = stroke)
            val arrow = Path().apply {
                moveTo(3f * s, 3f * s)
                lineTo(3f * s, 8f * s)
                lineTo(8f * s, 8f * s)
            }
            drawPath(arrow, color, style = stroke)
        }
    }
}

@Composable
fun IconTimeMachine(color: Color = InkPrimary, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        lucideCanvas { s, stroke ->
            drawArc(color, -145f, 295f, false, Offset(3f * s, 3f * s), Size(18f * s, 18f * s), style = stroke)
            val arrow = Path().apply {
                moveTo(3f * s, 3f * s)
                lineTo(3f * s, 8f * s)
                lineTo(8f * s, 8f * s)
            }
            drawPath(arrow, color, style = stroke)
            val hands = Path().apply {
                moveTo(12f * s, 7f * s)
                lineTo(12f * s, 12f * s)
                lineTo(16f * s, 14f * s)
            }
            drawPath(hands, color, style = stroke)
        }
    }
}

@Composable
fun IconPalette(color: Color = InkPrimary, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        lucideCanvas { s, stroke ->
            drawCircle(color, radius = 9.5f * s, center = Offset(12f * s, 12f * s), style = stroke)
            drawCircle(color, radius = 1.1f * s, center = Offset(13.5f * s, 6.5f * s))
            drawCircle(color, radius = 1.1f * s, center = Offset(17.5f * s, 10.5f * s))
            drawCircle(color, radius = 1.1f * s, center = Offset(8.5f * s, 7.5f * s))
            drawCircle(color, radius = 1.1f * s, center = Offset(6.5f * s, 12.5f * s))
            drawCircle(color, radius = 1.9f * s, center = Offset(14.5f * s, 16.5f * s), style = stroke)
        }
    }
}

@Composable
fun IconSwatchCycle(color: Color = FoilGold, size: Dp = 16.dp) = IconPalette(color = color, size = size)

@Composable
fun IconSettingsGear(color: Color = InkPrimary, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        lucideCanvas { s, stroke ->
            val cx = 12f * s
            val cy = 12f * s
            drawCircle(color, radius = 3f * s, center = Offset(cx, cy), style = stroke)
            drawCircle(color, radius = 6.5f * s, center = Offset(cx, cy), style = stroke)
            for (i in 0 until 8) {
                val a = (i * 45.0) * PI / 180.0
                val inner = 6.8f * s
                val outer = 9.8f * s
                drawLine(
                    color,
                    Offset(cx + (inner * cos(a)).toFloat(), cy + (inner * sin(a)).toFloat()),
                    Offset(cx + (outer * cos(a)).toFloat(), cy + (outer * sin(a)).toFloat()),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
fun IconEyeVisibility(visible: Boolean, color: Color = InkPrimary, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        lucideCanvas { s, stroke ->
            val eyePath = Path().apply {
                moveTo(2f * s, 12f * s)
                quadraticTo(12f * s, 3.5f * s, 22f * s, 12f * s)
                quadraticTo(12f * s, 20.5f * s, 2f * s, 12f * s)
                close()
            }
            drawPath(eyePath, color, style = stroke)
            drawCircle(color, radius = 3f * s, center = Offset(12f * s, 12f * s), style = stroke)
            if (!visible) {
                drawLine(color, Offset(2f * s, 2f * s), Offset(22f * s, 22f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
fun IconSparkStar(color: Color = InkPrimary, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        lucideCanvas { s, stroke ->
            val star = Path().apply {
                moveTo(10f * s, 3f * s)
                quadraticTo(10.8f * s, 9.2f * s, 17f * s, 10f * s)
                quadraticTo(10.8f * s, 10.8f * s, 10f * s, 17f * s)
                quadraticTo(9.2f * s, 10.8f * s, 3f * s, 10f * s)
                quadraticTo(9.2f * s, 9.2f * s, 10f * s, 3f * s)
                close()
            }
            drawPath(star, color, style = stroke)
            drawLine(color, Offset(19f * s, 3f * s), Offset(19f * s, 7f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(17f * s, 5f * s), Offset(21f * s, 5f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(18f * s, 17f * s), Offset(18f * s, 21f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(16f * s, 19f * s), Offset(20f * s, 19f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun IconIllustratePage(color: Color = InkPrimary, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        lucideCanvas { s, stroke ->
            drawRoundRect(color, Offset(3f * s, 3f * s), Size(18f * s, 18f * s), CornerRadius(2f * s, 2f * s), style = stroke)
            drawCircle(color, radius = 2f * s, center = Offset(9f * s, 9f * s), style = stroke)
            val mtn = Path().apply {
                moveTo(21f * s, 15f * s)
                lineTo(16f * s, 10f * s)
                lineTo(5f * s, 21f * s)
            }
            drawPath(mtn, color, style = stroke)
        }
    }
}
