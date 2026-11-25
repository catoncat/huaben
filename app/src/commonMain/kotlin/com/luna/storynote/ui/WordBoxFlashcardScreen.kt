package com.luna.storynote.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luna.storynote.BookStudioController
import com.luna.storynote.model.CollectedWordCard
import com.luna.storynote.model.StudioUiState
import com.luna.storynote.ui.icons.IconChevronBack
import com.luna.storynote.ui.icons.IconWordBoxDeck
import com.luna.storynote.ui.motion.springPressScale

/**
 * `字词盒` Flashcard Deck:
 * - Front side hides Pinyin so the 7-year-old tests pure character recognition.
 * - Tapping flips the card in 3D (`rotationY`) to reveal Pinyin AND plays the child's own recorded sentence.
 */
@Composable
internal fun WordBoxFlashcardScreen(
    uiState: StudioUiState,
    controller: BookStudioController,
    bookFont: FontFamily,
) {
    val flippedMap = remember { mutableStateMapOf<String, Boolean>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularChromeIconButton(
                onClick = { controller.returnToShelf() },
                modifier = Modifier
                    .testTag("back_button")
                    .semantics { contentDescription = "返回" },
            ) {
                IconChevronBack(color = InkPrimary, size = 18.dp)
            }
            Text(
                text = "字词盒",
                fontFamily = bookFont,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = InkPrimary,
            )
            Spacer(Modifier.size(40.dp))
        }

        Spacer(Modifier.height(22.dp))

        if (uiState.collectedWords.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = BookPaper,
                    border = BorderStroke(1.dp, BookOutline),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 44.dp, vertical = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        IconWordBoxDeck(filled = false, color = InkSubtle, size = 36.dp)
                        Text(
                            text = "0",
                            fontFamily = FontFamily.Serif,
                            fontSize = 18.sp,
                            color = InkSubtle,
                        )
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 210.dp),
                contentPadding = PaddingValues(bottom = 36.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(uiState.collectedWords, key = { it.word }) { card ->
                    val isFlipped = flippedMap[card.word] == true
                    FlippableWordCard(
                        card = card,
                        isFlipped = isFlipped,
                        bookFont = bookFont,
                        onFlip = {
                            val next = !isFlipped
                            flippedMap[card.word] = next
                            if (next) {
                                controller.playCollectedWordExample(card)
                            } else {
                                controller.stopAudioPlayback()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FlippableWordCard(
    card: CollectedWordCard,
    isFlipped: Boolean,
    bookFont: FontFamily,
    onFlip: () -> Unit,
) {
    val rotY by animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.76f, stiffness = 340f),
        label = "cardFlip",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .graphicsLayer {
                rotationY = rotY
                cameraDistance = 14f * density
            }
            .clip(RoundedCornerShape(22.dp))
            .springPressScale(pressedScale = 0.96f, onClick = onFlip),
        shape = RoundedCornerShape(22.dp),
        color = if (rotY <= 90f) BookPaper else BookPaperWarm,
        border = BorderStroke(1.5.dp, if (rotY <= 90f) BookOutline else JadeInk.copy(alpha = 0.5f)),
        shadowElevation = 5.dp,
    ) {
        if (rotY <= 90f) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = card.word,
                    fontFamily = bookFont,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    color = InkPrimary,
                    letterSpacing = 4.sp,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationY = 180f }
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = card.pinyins.joinToString(" "),
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = JadeInk,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = card.word,
                        fontFamily = bookFont,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = InkPrimary,
                    )
                }

                if (card.sentenceText.isNotBlank()) {
                    val annotated = buildAnnotatedString {
                        val idx = card.sentenceText.indexOf(card.word)
                        if (idx >= 0) {
                            append(card.sentenceText.substring(0, idx))
                            withStyle(SpanStyle(color = Vermilion, fontWeight = FontWeight.Bold)) {
                                append(card.word)
                            }
                            append(card.sentenceText.substring(idx + card.word.length))
                        } else {
                            append(card.sentenceText)
                        }
                    }
                    Text(
                        text = annotated,
                        fontFamily = bookFont,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = InkSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                    )
                }
            }
        }
    }
}
