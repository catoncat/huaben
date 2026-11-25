package com.luna.storynote.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luna.storynote.BookStudioController
import com.luna.storynote.model.BookPage
import com.luna.storynote.model.MicIntent
import com.luna.storynote.model.StoryBook
import com.luna.storynote.model.StudioUiState
import com.luna.storynote.model.UiMotionEvent

@Composable
internal fun TwoPageIllustratedSpread(
    book: StoryBook,
    page: BookPage,
    illustrationBitmap: ImageBitmap?,
    isIllustrating: Boolean,
    isLastPage: Boolean,
    uiState: StudioUiState,
    controller: BookStudioController,
    bookFont: FontFamily,
    topClearance: Dp = 56.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BookPaper),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 28.dp, end = 16.dp, top = topClearance, bottom = 78.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (illustrationBitmap != null) {
                    BookIllustrationPanel(
                        pageIndex = page.pageIndex,
                        bitmap = illustrationBitmap,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (isIllustrating) {
                    IllustrationGeneratingPlaceholder(
                        pageIndex = page.pageIndex,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .width(20.dp)
                    .fillMaxHeight()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                BookPaper,
                                Color(0xFFEDE6D8),
                                Color(0xFFE3DAC8),
                                Color(0xFFEDE6D8),
                                BookPaper,
                            ),
                        ),
                    ),
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                BookPageInterior(
                    book = book,
                    page = page,
                    topIllustration = null,
                    isIllustrating = false,
                    generousMargins = false,
                    isLastPage = isLastPage,
                    topClearance = topClearance,
                    bottomMicClearance = 80.dp,
                    uiState = uiState,
                    controller = controller,
                    bookFont = bookFont,
                )
            }
        }
    }
}

@Composable
internal fun SingleHardcoverPageSheet(
    book: StoryBook,
    page: BookPage,
    illustrationBitmap: ImageBitmap?,
    isIllustrating: Boolean,
    isLandscapeCentered: Boolean,
    isLastPage: Boolean,
    uiState: StudioUiState,
    controller: BookStudioController,
    bookFont: FontFamily,
    topClearance: Dp = 56.dp,
    modifier: Modifier = Modifier,
) {
    val maxColumnWidth = if (isLandscapeCentered) 640.dp else 620.dp
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BookPaper),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = maxColumnWidth)
                .fillMaxSize(),
        ) {
            BookPageInterior(
                book = book,
                page = page,
                topIllustration = illustrationBitmap,
                isIllustrating = isIllustrating,
                generousMargins = isLandscapeCentered && illustrationBitmap == null && !isIllustrating,
                isLastPage = isLastPage,
                topClearance = topClearance,
                bottomMicClearance = if (isLandscapeCentered) 80.dp else 86.dp,
                uiState = uiState,
                controller = controller,
                bookFont = bookFont,
            )
        }
    }
}

@Composable
private fun BookPageInterior(
    book: StoryBook,
    page: BookPage,
    topIllustration: ImageBitmap?,
    isIllustrating: Boolean,
    generousMargins: Boolean,
    isLastPage: Boolean,
    topClearance: Dp,
    bottomMicClearance: Dp,
    uiState: StudioUiState,
    controller: BookStudioController,
    bookFont: FontFamily,
) {
    val isSpeakingTitle = uiState.activeMicIntent is MicIntent.SpeakBookTitle
    val replacingClauseId = (uiState.activeMicIntent as? MicIntent.ReplaceClause)?.clauseId
    val draftForFlow = if (uiState.activeMicIntent is MicIntent.WriteAtCursor || replacingClauseId != null) {
        uiState.liveDraftText
    } else {
        ""
    }
    val landedClauseId = (uiState.lastMotionEvent as? UiMotionEvent.ClauseLanded)?.clauseId
    val revisedClauseId = (uiState.lastMotionEvent as? UiMotionEvent.ClauseRevised)?.clauseId
    val voiceRevisingClauseId = (uiState.activeMicIntent as? MicIntent.VoiceRevision)?.clauseId
    val lastClauseOnBook = book.clauses.lastOrNull()?.id
    val cursorAtBookEnd = isLastPage &&
        (uiState.cursorAfterClauseId == null || uiState.cursorAfterClauseId == lastClauseOnBook) &&
        book.clauses.isEmpty()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                val tailId = page.clauseIds.lastOrNull() ?: lastClauseOnBook
                controller.moveCursorAfter(tailId)
            },
    ) {
        val compactPage = maxWidth < 460.dp
        val sidePad = when {
            generousMargins -> 36.dp
            compactPage -> 20.dp
            else -> 32.dp
        }
        val illusHeight = if (compactPage) 156.dp else 236.dp

        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.height(topClearance))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = sidePad,
                        end = sidePad,
                        top = 6.dp,
                        bottom = 10.dp,
                    ),
            ) {
                if (topIllustration != null) {
                    BookIllustrationPanel(
                        pageIndex = page.pageIndex,
                        bitmap = topIllustration,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(illusHeight),
                    )
                    Spacer(Modifier.height(14.dp))
                } else if (isIllustrating) {
                    IllustrationGeneratingPlaceholder(
                        pageIndex = page.pageIndex,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(illusHeight),
                    )
                    Spacer(Modifier.height(14.dp))
                }

                if (page.pageIndex == 0) {
                    val displayedTitle = if (isSpeakingTitle && uiState.liveDraftText.isNotBlank()) {
                        uiState.liveDraftText
                    } else {
                        book.title
                    }
                    BookTitleBlock(
                        title = displayedTitle,
                        isSpeakingTitle = isSpeakingTitle,
                        bookFont = bookFont,
                        onPlayTitle = { controller.playBookTitleVoice(book) },
                        onSpeakNewTitle = { controller.startSpeakBookTitle() },
                    )
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = BookRuleLine, thickness = 1.dp)
                    Spacer(Modifier.height(16.dp))
                }

                if (page.paragraphs.isEmpty() && cursorAtBookEnd) {
                    Row(modifier = Modifier.padding(start = 48.dp, top = 8.dp)) {
                        BreathingInkCursorWithDraft(
                            liveDraftText = draftForFlow,
                            bookFont = bookFont,
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        for (para in page.paragraphs) {
                            InteractiveRubyParagraph(
                                group = para,
                                bookFont = bookFont,
                                playingSentenceId = uiState.playingClauseId,
                                selectedSentenceId = uiState.selectedClauseId,
                                inspectedSentenceId = uiState.inspectedWord?.clauseId,
                                inspectedWordSpan = uiState.inspectedWord?.span,
                                cursorAfterSentenceId = uiState.cursorAfterClauseId
                                    ?: if (isLastPage) lastClauseOnBook else "__NONE__",
                                liveDraftText = draftForFlow,
                                isReplacingSentenceId = replacingClauseId,
                                justLandedClauseId = landedClauseId,
                                revisedClauseId = revisedClauseId,
                                voiceRevisingClauseId = voiceRevisingClauseId,
                                onTapSentenceDot = { c -> controller.onClauseDotTapped(c) },
                                onTapCharacter = { c, charIdx -> controller.onCharacterTapped(c, charIdx) },
                                onPlaceCursorAfter = { cid -> controller.moveCursorAfter(cid) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            val isJustStamped = uiState.newlyStampedPage == page.pageIndex
            val stampScale by animateFloatAsState(
                targetValue = if (isJustStamped) 1.28f else 1.0f,
                animationSpec = spring(dampingRatio = 0.52f, stiffness = 380f),
                label = "pageStamp",
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "— ${page.pageIndex + 1} —",
                    fontFamily = FontFamily.Serif,
                    fontSize = 12.5.sp,
                    fontWeight = if (isJustStamped) FontWeight.Bold else FontWeight.Normal,
                    color = if (isJustStamped) Vermilion else InkSubtle,
                    modifier = Modifier.scale(stampScale),
                )
            }

            Spacer(Modifier.height(bottomMicClearance))
        }
    }
}
