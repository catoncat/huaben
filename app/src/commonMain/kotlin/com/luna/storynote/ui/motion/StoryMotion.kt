package com.luna.storynote.ui.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.luna.storynote.model.DeviceFormFactor
import com.luna.storynote.model.StudioDestination
import kotlin.math.absoluteValue

fun resolveDeviceFormFactor(maxWidth: Dp, maxHeight: Dp): DeviceFormFactor = when {
    maxWidth < 500.dp -> DeviceFormFactor.PHONE
    maxWidth > maxHeight && maxWidth >= 780.dp -> DeviceFormFactor.TABLET_LANDSCAPE_OR_DESKTOP
    else -> DeviceFormFactor.TABLET_PORTRAIT
}

fun bookshelfColumnCount(maxWidth: Dp): Int = when {
    maxWidth < 500.dp -> 2
    maxWidth < 780.dp -> 3
    else -> 4
}

fun isLandscapeSpreadWidth(maxWidth: Dp, maxHeight: Dp): Boolean =
    maxWidth > maxHeight && maxWidth >= 780.dp

object StorySprings {
    val GentlePaper = spring<Float>(dampingRatio = 0.82f, stiffness = 320f)
    val SnappyTactile = spring<Float>(dampingRatio = 0.66f, stiffness = 520f)
    val ElasticPop = spring<Float>(dampingRatio = 0.58f, stiffness = 400f)
    val BookmarkBounce = spring<Float>(dampingRatio = 0.45f, stiffness = 480f)
}

fun Modifier.springPressScale(
    pressedScale: Float = 0.94f,
    enabled: Boolean = true,
    hapticOnClick: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = StorySprings.SnappyTactile,
        label = "springPressScale",
    )
    val haptic = LocalHapticFeedback.current
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = {
                if (hapticOnClick) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                onClick()
            },
        )
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.springCombinedPressScale(
    pressedScale: Float = 0.96f,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = StorySprings.SnappyTactile,
        label = "springCombinedPressScale",
    )
    val haptic = LocalHapticFeedback.current
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            },
        )
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.pagerPageDepth(pagerState: PagerState, pageIndex: Int): Modifier = graphicsLayer {
    val offset = ((pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction)
        .absoluteValue
        .coerceIn(0f, 1f)
    val scale = 1f - 0.025f * offset
    scaleX = scale
    scaleY = scale
    alpha = 1f - 0.16f * offset
}

fun Modifier.clauseLandedSettle(isJustLanded: Boolean): Modifier = composed {
    val progress = remember(isJustLanded) { Animatable(if (isJustLanded) 0f else 1f) }
    LaunchedEffect(isJustLanded) {
        if (isJustLanded) {
            progress.snapTo(0f)
            progress.animateTo(1f, animationSpec = StorySprings.GentlePaper)
        }
    }
    this.graphicsLayer {
        val p = progress.value
        alpha = 0.25f + 0.75f * p
        translationY = (1f - p) * 14.dp.toPx()
    }
}

fun Modifier.clauseRevisedHighlight(isJustRevised: Boolean): Modifier = composed {
    val glow = remember(isJustRevised) { Animatable(if (isJustRevised) 1f else 0f) }
    LaunchedEffect(isJustRevised) {
        if (isJustRevised) {
            glow.snapTo(1f)
            glow.animateTo(0f, animationSpec = tween(durationMillis = 1200))
        }
    }
    val alpha = glow.value
    if (alpha <= 0.01f) {
        this
    } else {
        this.drawBehind {
            drawRoundRect(
                color = Color(0xFFF6D98E).copy(alpha = alpha * 0.45f),
                cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
            )
        }
    }
}

@Composable
fun StudioDestinationTransition(
    destination: StudioDestination,
    modifier: Modifier = Modifier,
    content: @Composable (StudioDestination) -> Unit,
) {
    AnimatedContent(
        targetState = destination,
        modifier = modifier,
        transitionSpec = { destinationTransform(initialState, targetState) },
        label = "studioDestination",
    ) { dest ->
        content(dest)
    }
}

private fun destinationTransform(
    from: StudioDestination,
    to: StudioDestination,
): ContentTransform = when {
    from == StudioDestination.SHELF && to == StudioDestination.BOOK -> {
        (fadeIn(tween(220)) + scaleIn(
            initialScale = 0.91f,
            transformOrigin = TransformOrigin.Center,
            animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
        )).togetherWith(fadeOut(tween(180)) + scaleOut(targetScale = 1.03f))
    }
    from == StudioDestination.BOOK && to == StudioDestination.SHELF -> {
        (fadeIn(tween(200)) + scaleIn(initialScale = 0.96f)).togetherWith(
            fadeOut(tween(200)) + scaleOut(targetScale = 0.92f),
        )
    }
    to == StudioDestination.WORD_BOX -> {
        (fadeIn(tween(200)) + slideInHorizontally { it / 6 }).togetherWith(
            fadeOut(tween(180)) + slideOutHorizontally { -it / 8 },
        )
    }
    else -> {
        (fadeIn(tween(200)) + slideInHorizontally { -it / 6 }).togetherWith(
            fadeOut(tween(180)) + slideOutHorizontally { it / 8 },
        )
    }
}

@Composable
fun ModalPaperSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    scrimMaxAlpha: Float = 0.36f,
    content: @Composable BoxScope.() -> Unit,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, animationSpec = StorySprings.ElasticPop)
    }
    val scrimAlpha = (progress.value.coerceIn(0f, 1f)) * scrimMaxAlpha
    val cardScale = 0.90f + 0.10f * progress.value

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1C1712).copy(alpha = scrimAlpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.graphicsLayer {
                scaleX = cardScale
                scaleY = cardScale
                alpha = progress.value.coerceIn(0f, 1f)
            },
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}
