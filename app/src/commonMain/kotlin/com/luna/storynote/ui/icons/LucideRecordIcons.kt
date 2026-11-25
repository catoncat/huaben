package com.luna.storynote.ui.icons

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.luna.storynote.ui.BookOutline
import com.luna.storynote.ui.BookPaper
import com.luna.storynote.ui.FoilGold
import com.luna.storynote.ui.InkPrimary
import com.luna.storynote.ui.InkSubtle
import com.luna.storynote.ui.Vermilion
import com.luna.storynote.ui.motion.springPressScale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun IconMicCapsule(color: Color = Color.White, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        lucideCanvas { s, stroke ->
            drawRoundRect(
                color = color,
                topLeft = Offset(9f * s, 2f * s),
                size = Size(6f * s, 11f * s),
                cornerRadius = CornerRadius(3f * s, 3f * s),
                style = stroke,
            )
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(5f * s, 5f * s),
                size = Size(14f * s, 14f * s),
                style = stroke,
            )
            drawLine(color, Offset(12f * s, 19f * s), Offset(12f * s, 22f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(8f * s, 22f * s), Offset(16f * s, 22f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun IconRespeakWave(color: Color = InkPrimary, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        lucideCanvas(strokeFactor = 1.9f) { s, stroke ->
            // Mic silhouette in center-left + loop rewind arc
            drawRoundRect(color, Offset(9.5f * s, 5.5f * s), Size(5f * s, 8f * s), CornerRadius(2.5f * s, 2.5f * s), style = stroke)
            drawLine(color, Offset(12f * s, 15.5f * s), Offset(12f * s, 18f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawArc(color, -45f, 265f, false, Offset(3f * s, 3f * s), Size(18f * s, 18f * s), style = stroke)
            val arrow = Path().apply {
                moveTo(15.5f * s, 3.5f * s)
                lineTo(20f * s, 4.5f * s)
                lineTo(18.5f * s, 9f * s)
            }
            drawPath(arrow, color, style = stroke)
        }
    }
}

@Composable
fun IconInsertSentence(color: Color = InkPrimary, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        lucideCanvas { s, stroke ->
            // Text cursor beam + insertion plus
            drawLine(color, Offset(6f * s, 4f * s), Offset(12f * s, 4f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(9f * s, 4f * s), Offset(9f * s, 20f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(6f * s, 20f * s), Offset(12f * s, 20f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(17f * s, 8f * s), Offset(17f * s, 16f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(13f * s, 12f * s), Offset(21f * s, 12f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun IconEraserBlock(color: Color = Color(0xFFB0412E), size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        lucideCanvas { s, stroke ->
            val body = Path().apply {
                moveTo(7f * s, 21f * s)
                lineTo(2.7f * s, 16.7f * s)
                cubicTo(1.7f * s, 15.7f * s, 1.7f * s, 14.2f * s, 2.7f * s, 13.3f * s)
                lineTo(12.3f * s, 3.7f * s)
                cubicTo(13.3f * s, 2.7f * s, 14.8f * s, 2.7f * s, 15.7f * s, 3.7f * s)
                lineTo(21.3f * s, 9.3f * s)
                cubicTo(22.3f * s, 10.3f * s, 22.3f * s, 11.8f * s, 21.3f * s, 12.7f * s)
                lineTo(13f * s, 21f * s)
                close()
            }
            drawPath(body, color, style = stroke)
            drawLine(color, Offset(5.5f * s, 10.5f * s), Offset(13.5f * s, 18.5f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(7f * s, 21f * s), Offset(21f * s, 21f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun IconVoiceRevise(color: Color = InkPrimary, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        lucideCanvas { s, stroke ->
            drawLine(color, Offset(3f * s, 21f * s), Offset(16f * s, 8f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(18f * s, 3f * s), Offset(18f * s, 7f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(16f * s, 5f * s), Offset(20f * s, 5f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(7f * s, 5f * s), Offset(7f * s, 9f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(5f * s, 7f * s), Offset(9f * s, 7f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(19f * s, 14f * s), Offset(19f * s, 18f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(17f * s, 16f * s), Offset(21f * s, 16f * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun SentenceAcousticDot(
    isPlaying: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val inf = rememberInfiniteTransition(label = "dotWave")
    val pulse by inf.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(650, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    Box(
        modifier = modifier
            .size(26.dp)
            .clip(CircleShape)
            .springPressScale(pressedScale = 0.88f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f
            when {
                isPlaying -> {
                    drawCircle(Vermilion.copy(alpha = 0.22f), radius = r * pulse, center = c)
                    drawCircle(Vermilion, radius = r * 0.42f, center = c)
                }
                isSelected -> {
                    drawCircle(Vermilion, radius = r * 0.76f, center = c, style = Stroke(width = size.width * 0.11f))
                    drawCircle(Vermilion, radius = r * 0.34f, center = c)
                }
                else -> {
                    drawCircle(BookOutline, radius = r * 0.68f, center = c, style = Stroke(width = size.width * 0.08f))
                    drawCircle(InkSubtle.copy(alpha = 0.65f), radius = r * 0.24f, center = c)
                }
            }
        }
    }
}

@Composable
fun BraunTactileMicButton(
    isRecording: Boolean,
    voiceEnergy: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val inf = rememberInfiniteTransition(label = "micRing")
    val phase by inf.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Restart),
        label = "wavePhase",
    )

    Box(
        modifier = modifier
            .size(72.dp)
            .shadow(elevation = 10.dp, shape = CircleShape, ambientColor = Color(0x331E1812), spotColor = Color(0x441E1812))
            .clip(CircleShape)
            .background(BookPaper)
            .border(1.5.dp, BookOutline, CircleShape)
            .springPressScale(pressedScale = 0.92f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(56.dp),
            shape = CircleShape,
            color = if (isRecording) InkPrimary else Vermilion,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (!isRecording) {
                    IconMicCapsule(color = Color.White, size = 25.dp)
                } else {
                    Canvas(Modifier.size(28.dp)) {
                        val w = size.width
                        val h = size.height
                        val barCount = 5
                        val gap = w / (barCount + 1)
                        val amp = (voiceEnergy * 5.5f).coerceIn(0.18f, 1.0f)
                        for (i in 0 until barCount) {
                            val centerFactor = 1f - kotlin.math.abs(i - 2) * 0.22f
                            val wave = (sin(phase + i * 0.9f) * 0.30f + 0.70f)
                            val bh = (h * (0.22f + 0.62f * amp * centerFactor * wave)).coerceIn(h * 0.20f, h * 0.86f)
                            val x = gap * (i + 1) - w * 0.045f
                            drawRoundRect(
                                color = Vermilion,
                                topLeft = Offset(x, (h - bh) / 2f),
                                size = Size(w * 0.09f, bh),
                                cornerRadius = CornerRadius(w * 0.05f, w * 0.05f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BookFoilEmblem(emblemIndex: Int, color: Color = FoilGold, size: Dp = 28.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val c = Offset(w / 2f, h / 2f)
        val stroke = Stroke(width = w * 0.055f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (emblemIndex.coerceAtLeast(0) % 6) {
            0 -> {
                val p = Path()
                for (i in 0 until 8) {
                    val ang = i * PI / 4.0 - PI / 2.0
                    val rad = if (i % 2 == 0) w * 0.44f else w * 0.17f
                    val px = c.x + (cos(ang) * rad).toFloat()
                    val py = c.y + (sin(ang) * rad).toFloat()
                    if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
                }
                p.close()
                drawPath(p, color, style = stroke)
            }
            1 -> {
                drawCircle(color, radius = w * 0.24f, center = c, style = stroke)
                rotate(-24f, pivot = c) {
                    drawOval(color, Offset(w * 0.08f, h * 0.38f), Size(w * 0.84f, h * 0.24f), style = stroke)
                }
            }
            2 -> {
                val p = Path().apply {
                    moveTo(w * 0.18f, h * 0.74f); lineTo(w * 0.14f, h * 0.32f); lineTo(w * 0.36f, h * 0.52f)
                    lineTo(w * 0.50f, h * 0.24f); lineTo(w * 0.64f, h * 0.52f); lineTo(w * 0.86f, h * 0.32f)
                    lineTo(w * 0.82f, h * 0.74f); close()
                }
                drawPath(p, color, style = stroke)
            }
            3 -> {
                val sail = Path().apply {
                    moveTo(w * 0.50f, h * 0.16f); lineTo(w * 0.50f, h * 0.66f); lineTo(w * 0.22f, h * 0.66f); close()
                    moveTo(w * 0.50f, h * 0.26f); lineTo(w * 0.76f, h * 0.66f); lineTo(w * 0.50f, h * 0.66f)
                }
                val hull = Path().apply {
                    moveTo(w * 0.16f, h * 0.72f); lineTo(w * 0.84f, h * 0.72f)
                    lineTo(w * 0.72f, h * 0.84f); lineTo(w * 0.28f, h * 0.84f); close()
                }
                drawPath(sail, color, style = stroke); drawPath(hull, color, style = stroke)
            }
            4 -> {
                drawArc(color, 40f, 260f, false, Offset(w * 0.18f, h * 0.18f), Size(w * 0.64f, h * 0.64f), style = stroke)
                drawCircle(color, radius = w * 0.06f, center = Offset(w * 0.68f, h * 0.34f))
            }
            else -> {
                val m = Path().apply {
                    moveTo(w * 0.12f, h * 0.78f); lineTo(w * 0.40f, h * 0.34f); lineTo(w * 0.56f, h * 0.58f)
                    lineTo(w * 0.70f, h * 0.40f); lineTo(w * 0.88f, h * 0.78f); close()
                }
                drawPath(m, color, style = stroke)
                drawCircle(color, radius = w * 0.09f, center = Offset(w * 0.72f, h * 0.24f), style = stroke)
            }
        }
    }
}
