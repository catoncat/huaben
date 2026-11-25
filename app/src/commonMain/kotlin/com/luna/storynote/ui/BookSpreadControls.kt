package com.luna.storynote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun FloatingSentenceEditCapsule(
    onRespeak: () -> Unit,
    onInsertAfter: () -> Unit,
    onErase: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = BookPaper,
        border = BorderStroke(1.dp, BookOutline),
        shadowElevation = 10.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CapsuleIconSlot(onClick = onRespeak) {
                IconRespeakWave(color = InkPrimary, size = 19.dp)
            }
            CapsuleIconSlot(onClick = onInsertAfter) {
                IconInsertSentence(color = JadeInk, size = 19.dp)
            }
            CapsuleIconSlot(onClick = onErase) {
                IconEraserBlock(color = Vermilion, size = 19.dp)
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(18.dp)
                    .background(BookRuleLine),
            )
            CapsuleIconSlot(onClick = onDismiss) {
                IconCloseCross(color = InkSubtle, size = 15.dp)
            }
        }
    }
}

@Composable
private fun CapsuleIconSlot(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
internal fun CircularChromeIconButton(
    onClick: () -> Unit,
    containerColor: Color = BookPaper,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = containerColor,
        border = BorderStroke(1.dp, BookOutline),
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}
