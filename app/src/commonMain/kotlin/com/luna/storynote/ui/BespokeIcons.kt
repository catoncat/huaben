package com.luna.storynote.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure geometric vector icons & tactile Braun/Teenage-Engineering physical controls.
 * Strictly zero emoji glyphs anywhere in the product.
 */

@Composable
fun IconChevronBack(color: Color = InkPrimary, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val p = Path().apply {
            moveTo(w * 0.64f, h * 0.20f)
            lineTo(w * 0.30f, h * 0.50f)
            lineTo(w * 0.64f, h * 0.80f)
        }
        drawPath(p, color, style = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun IconPlayAudio(color: Color = InkPrimary, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val p = Path().apply {
            moveTo(w * 0.28f, h * 0.18f)
            lineTo(w * 0.82f, h * 0.50f)
            lineTo(w * 0.28f, h * 0.82f)
            close()
        }
        drawPath(p, color)
        drawPath(p, color, style = Stroke(width = w * 0.08f, join = StrokeJoin.Round))
    }
}

@Composable
fun IconPauseAudio(color: Color = Vermilion, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val barW = w * 0.20f
        val r = CornerRadius(barW / 2f, barW / 2f)
        drawRoundRect(color, Offset(w * 0.24f, h * 0.20f), Size(barW, h * 0.60f), r)
        drawRoundRect(color, Offset(w * 0.56f, h * 0.20f), Size(barW, h * 0.60f), r)
    }
}

@Composable
fun IconMicCapsule(color: Color = Color.White, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.085f
        val capW = w * 0.28f
        val capH = h * 0.44f
        drawRoundRect(
            color = color,
            topLeft = Offset((w - capW) / 2f, h * 0.12f),
            size = Size(capW, capH),
            cornerRadius = CornerRadius(capW / 2f, capW / 2f),
        )
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.24f, h * 0.28f),
            size = Size(w * 0.52f, h * 0.44f),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        drawLine(color, Offset(w * 0.5f, h * 0.72f), Offset(w * 0.5f, h * 0.86f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.35f, h * 0.86f), Offset(w * 0.65f, h * 0.86f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
fun IconRespeakWave(color: Color = InkPrimary, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.095f
        drawArc(
            color = color,
            startAngle = -210f,
            sweepAngle = 290f,
            useCenter = false,
            topLeft = Offset(w * 0.14f, w * 0.14f),
            size = Size(w * 0.72f, w * 0.72f),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        // Arrowhead
        val arrow = Path().apply {
            moveTo(w * 0.68f, w * 0.10f)
            lineTo(w * 0.84f, w * 0.24f)
            lineTo(w * 0.64f, w * 0.31f)
        }
        drawPath(arrow, color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // Center voice bar
        drawLine(color, Offset(w * 0.5f, w * 0.36f), Offset(w * 0.5f, w * 0.64f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
fun IconInsertSentence(color: Color = InkPrimary, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.095f
        // Plus sign + insertion corner
        drawLine(color, Offset(w * 0.50f, w * 0.22f), Offset(w * 0.50f, w * 0.78f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.22f, w * 0.50f), Offset(w * 0.78f, w * 0.50f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
fun IconEraserBlock(color: Color = Color(0xFFB0412E), size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.09f
        rotate(-38f, pivot = Offset(w * 0.5f, w * 0.5f)) {
            drawRoundRect(
                color = color,
                topLeft = Offset(w * 0.24f, w * 0.30f),
                size = Size(w * 0.52f, w * 0.36f),
                cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
                style = Stroke(width = sw),
            )
            drawLine(color, Offset(w * 0.44f, w * 0.30f), Offset(w * 0.44f, w * 0.66f), strokeWidth = sw)
        }
        drawLine(color, Offset(w * 0.24f, w * 0.82f), Offset(w * 0.78f, w * 0.82f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
fun IconSpeakerMandarin(color: Color = InkPrimary, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.085f
        val cone = Path().apply {
            moveTo(w * 0.16f, h * 0.38f)
            lineTo(w * 0.32f, h * 0.38f)
            lineTo(w * 0.54f, h * 0.20f)
            lineTo(w * 0.54f, h * 0.80f)
            lineTo(w * 0.32f, h * 0.62f)
            lineTo(w * 0.16f, h * 0.62f)
            close()
        }
        drawPath(cone, color, style = Stroke(width = sw, join = StrokeJoin.Round))
        drawArc(
            color = color,
            startAngle = -45f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(w * 0.46f, h * 0.30f),
            size = Size(w * 0.28f, h * 0.40f),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        drawArc(
            color = color,
            startAngle = -48f,
            sweepAngle = 96f,
            useCenter = false,
            topLeft = Offset(w * 0.42f, h * 0.18f),
            size = Size(w * 0.46f, h * 0.64f),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
    }
}

@Composable
fun IconWordBoxDeck(filled: Boolean = false, color: Color = InkPrimary, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.085f
        // Stacked cards silhouette
        drawRoundRect(
            color = color.copy(alpha = 0.45f),
            topLeft = Offset(w * 0.24f, w * 0.14f),
            size = Size(w * 0.58f, w * 0.62f),
            cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
            style = Stroke(width = sw),
        )
        val frontTop = Offset(w * 0.16f, w * 0.24f)
        val frontSize = Size(w * 0.58f, w * 0.62f)
        val cr = CornerRadius(w * 0.10f, w * 0.10f)
        if (filled) {
            drawRoundRect(color = color, topLeft = frontTop, size = frontSize, cornerRadius = cr)
        } else {
            drawRoundRect(color = BookPaper, topLeft = frontTop, size = frontSize, cornerRadius = cr)
            drawRoundRect(color = color, topLeft = frontTop, size = frontSize, cornerRadius = cr, style = Stroke(width = sw))
        }
    }
}

@Composable
fun IconCloseCross(color: Color = InkSecondary, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.10f
        drawLine(color, Offset(w * 0.24f, w * 0.24f), Offset(w * 0.76f, w * 0.76f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.76f, w * 0.24f), Offset(w * 0.24f, w * 0.76f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
fun IconSwatchCycle(color: Color = FoilGold, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        val r = this.size.width * 0.26f
        drawCircle(color.copy(alpha = 0.85f), radius = r, center = Offset(this.size.width * 0.38f, this.size.height * 0.5f))
        drawCircle(
            color,
            radius = r,
            center = Offset(this.size.width * 0.64f, this.size.height * 0.5f),
            style = Stroke(width = this.size.width * 0.09f),
        )
    }
}

/**
 * Subtle sentence-start acoustic dot: quiet sepia dot at rest, pulsing concentric wave when speaking,
 * crisp vermilion ring when selected for in-place voice editing.
 */
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
            .clickable(onClick = onClick),
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

/**
 * Braun / Teenage-Engineering inspired machined physical microphone button anchored on the book desk.
 */
@Composable
fun BraunTactileMicButton(
    isRecording: Boolean,
    voiceEnergy: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1.0f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 520f),
        label = "braunPress",
    )
    val inf = rememberInfiniteTransition(label = "micRing")
    val phase by inf.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Restart),
        label = "wavePhase",
    )

    Box(
        modifier = modifier
            .scale(scale)
            .size(72.dp)
            .shadow(elevation = 10.dp, shape = CircleShape, ambientColor = Color(0x331E1812), spotColor = Color(0x441E1812))
            .clip(CircleShape)
            .background(BookPaper)
            .border(1.5.dp, BookOutline, CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
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
                    // 5 precision oscillating sound bars + subtle stop square affordance feel
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

/**
 * 6 curated geometric gold-foil line-art emblems for cloth hardcover books (zero emojis).
 */
@Composable
fun BookFoilEmblem(emblemIndex: Int, color: Color = FoilGold, size: Dp = 28.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val c = Offset(w / 2f, h / 2f)
        val sw = w * 0.055f
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)

        when (emblemIndex.coerceAtLeast(0) % 6) {
            0 -> {
                // 8-point geometric compass star
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
                // Minimalist ringed planet
                drawCircle(color, radius = w * 0.24f, center = c, style = stroke)
                rotate(-24f, pivot = c) {
                    drawOval(
                        color = color,
                        topLeft = Offset(w * 0.08f, h * 0.38f),
                        size = Size(w * 0.84f, h * 0.24f),
                        style = stroke,
                    )
                }
            }
            2 -> {
                // Crown silhouette
                val p = Path().apply {
                    moveTo(w * 0.18f, h * 0.74f)
                    lineTo(w * 0.14f, h * 0.32f)
                    lineTo(w * 0.36f, h * 0.52f)
                    lineTo(w * 0.50f, h * 0.24f)
                    lineTo(w * 0.64f, h * 0.52f)
                    lineTo(w * 0.86f, h * 0.32f)
                    lineTo(w * 0.82f, h * 0.74f)
                    close()
                }
                drawPath(p, color, style = stroke)
            }
            3 -> {
                // Origami Sailboat
                val sail = Path().apply {
                    moveTo(w * 0.50f, h * 0.16f)
                    lineTo(w * 0.50f, h * 0.66f)
                    lineTo(w * 0.22f, h * 0.66f)
                    close()
                    moveTo(w * 0.50f, h * 0.26f)
                    lineTo(w * 0.76f, h * 0.66f)
                    lineTo(w * 0.50f, h * 0.66f)
                }
                val hull = Path().apply {
                    moveTo(w * 0.16f, h * 0.72f)
                    lineTo(w * 0.84f, h * 0.72f)
                    lineTo(w * 0.72f, h * 0.84f)
                    lineTo(w * 0.28f, h * 0.84f)
                    close()
                }
                drawPath(sail, color, style = stroke)
                drawPath(hull, color, style = stroke)
            }
            4 -> {
                // Crescent Moon & North Star
                drawArc(
                    color = color,
                    startAngle = 40f,
                    sweepAngle = 260f,
                    useCenter = false,
                    topLeft = Offset(w * 0.18f, h * 0.18f),
                    size = Size(w * 0.64f, h * 0.64f),
                    style = stroke,
                )
                drawCircle(color, radius = w * 0.06f, center = Offset(w * 0.68f, h * 0.34f))
            }
            else -> {
                // Twin Mountain Peaks & Sun
                val m = Path().apply {
                    moveTo(w * 0.12f, h * 0.78f)
                    lineTo(w * 0.40f, h * 0.34f)
                    lineTo(w * 0.56f, h * 0.58f)
                    lineTo(w * 0.70f, h * 0.40f)
                    lineTo(w * 0.88f, h * 0.78f)
                    close()
                }
                drawPath(m, color, style = stroke)
                drawCircle(color, radius = w * 0.09f, center = Offset(w * 0.72f, h * 0.24f), style = stroke)
            }
        }
    }
}
