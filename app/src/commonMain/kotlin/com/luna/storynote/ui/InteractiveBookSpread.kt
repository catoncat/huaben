package com.luna.storynote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luna.storynote.BookPage
import com.luna.storynote.BookStudioController
import com.luna.storynote.MicIntent
import com.luna.storynote.StoryBook
import com.luna.storynote.StudioUiState

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InteractiveBookSpreadScreen(
    book: StoryBook,
    uiState: StudioUiState,
    controller: BookStudioController,
    bookFont: FontFamily,
) {
    val pages = remember(book.sentences, book.title) { book.paginate() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(DeskSurface),
    ) {
        val isTwoPageSpread = maxWidth >= 860.dp
        val spreadCount = if (isTwoPageSpread) {
            ((pages.size + 1) / 2).coerceAtLeast(1)
        } else {
            pages.size.coerceAtLeast(1)
        }
        val targetPagerPage = if (isTwoPageSpread) {
            (uiState.currentPageIndex / 2).coerceIn(0, spreadCount - 1)
        } else {
            uiState.currentPageIndex.coerceIn(0, spreadCount - 1)
        }

        val pagerState = rememberPagerState(
            initialPage = targetPagerPage,
            pageCount = { spreadCount },
        )

        LaunchedEffect(targetPagerPage, spreadCount) {
            if (pagerState.currentPage != targetPagerPage) {
                pagerState.animateScrollToPage(targetPagerPage)
            }
        }

        LaunchedEffect(pagerState.settledPage, isTwoPageSpread) {
            val logicalPage = if (isTwoPageSpread) pagerState.settledPage * 2 else pagerState.settledPage
            if (logicalPage != uiState.currentPageIndex && logicalPage - 1 != uiState.currentPageIndex) {
                controller.setCurrentPage(logicalPage)
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Minimalist Whisper Top Bar (pure iconography, zero clutter)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularChromeIconButton(onClick = { controller.returnToShelf() }) {
                    IconChevronBack(color = InkPrimary, size = 18.dp)
                }

                // Subtle page indicator dots / numbers
                Text(
                    text = if (isTwoPageSpread) {
                        val leftNum = pagerState.currentPage * 2 + 1
                        val rightNum = (leftNum + 1).coerceAtMost(pages.size)
                        if (leftNum == rightNum) "$leftNum / ${pages.size}" else "$leftNum–$rightNum / ${pages.size}"
                    } else {
                        "${pagerState.currentPage + 1} / ${pages.size}"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = InkSubtle,
                )

                // Full-book audiobook read-along button (auto-flips pages!)
                val hasContent = book.sentences.isNotEmpty()
                CircularChromeIconButton(
                    onClick = { if (hasContent) controller.toggleFullBookReadAlong() },
                    containerColor = if (uiState.isFullBookPlaying) HighlightSun else BookPaper,
                ) {
                    if (uiState.isFullBookPlaying) {
                        IconPauseAudio(color = Vermilion, size = 16.dp)
                    } else {
                        IconPlayAudio(
                            color = if (hasContent) InkPrimary else InkSubtle.copy(alpha = 0.4f),
                            size = 16.dp,
                        )
                    }
                }
            }

            // Book Canvas Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = if (isTwoPageSpread) 28.dp else 14.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    pageSpacing = 20.dp,
                ) { spreadIdx ->
                    if (isTwoPageSpread) {
                        val leftPage = pages.getOrNull(spreadIdx * 2)
                        val rightPage = pages.getOrNull(spreadIdx * 2 + 1)
                        TwoPageHardcoverSpread(
                            book = book,
                            leftPage = leftPage,
                            rightPage = rightPage,
                            totalPages = pages.size,
                            uiState = uiState,
                            controller = controller,
                            bookFont = bookFont,
                        )
                    } else {
                        val singlePage = pages.getOrElse(spreadIdx) { BookPage(spreadIdx, emptyList()) }
                        SingleHardcoverPageSheet(
                            book = book,
                            page = singlePage,
                            isLastPage = singlePage.pageIndex == pages.lastIndex,
                            uiState = uiState,
                            controller = controller,
                            bookFont = bookFont,
                        )
                    }
                }

                // Floating pure-vector 3-icon sentence edit capsule when a sentence dot is tapped
                val selectedId = uiState.selectedSentenceId
                androidx.compose.animation.AnimatedVisibility(
                    visible = selectedId != null && !uiState.isRecording,
                    enter = fadeIn() + scaleIn(initialScale = 0.88f),
                    exit = fadeOut() + scaleOut(targetScale = 0.88f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 96.dp),
                ) {
                    if (selectedId != null) {
                        FloatingSentenceEditCapsule(
                            onRespeak = { controller.startRespeakSentence(selectedId) },
                            onInsertAfter = { controller.startInsertAfterSentence(selectedId) },
                            onErase = { controller.eraseSentence(selectedId) },
                            onDismiss = { controller.dismissSelectionAndPopovers() },
                        )
                    }
                }

                // Subtle Undo pill when a sentence was just erased
                androidx.compose.animation.AnimatedVisibility(
                    visible = uiState.undoBanner != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 18.dp, bottom = 22.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = InkPrimary,
                        shadowElevation = 6.dp,
                        modifier = Modifier.clickable { controller.undoEraseSentence() },
                    ) {
                        Text(
                            text = "撤回擦除",
                            color = BookPaper,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }

                // Braun / Teenage-Engineering Tactile Microphone Button anchored at bottom center
                BraunTactileMicButton(
                    isRecording = uiState.isRecording,
                    voiceEnergy = uiState.voiceEnergy,
                    onClick = { controller.togglePrimaryMicButton() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp),
                )
            }
        }

        // Tactile TianZiGe Word Card Sheet (`田字格认字拼读卡`)
        uiState.inspectedWord?.let { inspection ->
            TianZiGeWordOverlay(
                inspection = inspection,
                uiState = uiState,
                controller = controller,
                bookFont = bookFont,
            )
        }
    }
}

@Composable
private fun TwoPageHardcoverSpread(
    book: StoryBook,
    leftPage: BookPage?,
    rightPage: BookPage?,
    totalPages: Int,
    uiState: StudioUiState,
    controller: BookStudioController,
    bookFont: FontFamily,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 52.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Outer stacked paper edges layer
        Box(
            modifier = Modifier
                .widthIn(max = 1120.dp)
                .fillMaxSize()
                .padding(top = 4.dp, start = 4.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(BookPageEdge2),
        )

        Surface(
            modifier = Modifier
                .widthIn(max = 1120.dp)
                .fillMaxSize()
                .padding(end = 4.dp, bottom = 4.dp),
            shape = RoundedCornerShape(18.dp),
            color = BookPaper,
            border = BorderStroke(1.dp, BookOutline),
            shadowElevation = 8.dp,
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Left Page
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (leftPage != null) {
                        BookPageInterior(
                            book = book,
                            page = leftPage,
                            isLastPage = leftPage.pageIndex == totalPages - 1,
                            uiState = uiState,
                            controller = controller,
                            bookFont = bookFont,
                        )
                    }
                }

                // Realistic center book-spine gutter crease gradient
                Box(
                    modifier = Modifier
                        .width(22.dp)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color(0x16261E16),
                                    Color(0x28261E16),
                                    Color(0x12261E16),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )

                // Right Page
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (rightPage != null) {
                        BookPageInterior(
                            book = book,
                            page = rightPage,
                            isLastPage = rightPage.pageIndex == totalPages - 1,
                            uiState = uiState,
                            controller = controller,
                            bookFont = bookFont,
                        )
                    } else {
                        // Gentle blank right-hand flyleaf waiting to be filled
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { controller.moveCursorAfter(book.sentences.lastOrNull()?.id) },
                        ) {
                            Text(
                                text = "— ${(leftPage?.pageIndex ?: 0) + 2} —",
                                fontSize = 12.sp,
                                color = InkSubtle.copy(alpha = 0.45f),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SingleHardcoverPageSheet(
    book: StoryBook,
    page: BookPage,
    isLastPage: Boolean,
    uiState: StudioUiState,
    controller: BookStudioController,
    bookFont: FontFamily,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 82.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Stacked right-hand page edges
        Box(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxSize()
                .padding(start = 6.dp, top = 5.dp)
                .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 20.dp, bottomEnd = 20.dp))
                .background(BookPageEdge2),
        )
        Box(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxSize()
                .padding(start = 3.dp, top = 2.5.dp, end = 3.dp, bottom = 2.5.dp)
                .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 20.dp, bottomEnd = 20.dp))
                .background(BookPageEdge1),
        )

        Surface(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxSize()
                .padding(end = 6.dp, bottom = 5.dp),
            shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 20.dp, bottomEnd = 20.dp),
            color = BookPaper,
            border = BorderStroke(1.dp, BookOutline),
            shadowElevation = 6.dp,
        ) {
            BookPageInterior(
                book = book,
                page = page,
                isLastPage = isLastPage,
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
    isLastPage: Boolean,
    uiState: StudioUiState,
    controller: BookStudioController,
    bookFont: FontFamily,
) {
    val isSpeakingTitle = uiState.activeMicIntent is MicIntent.SpeakBookTitle
    val replacingSentenceId = (uiState.activeMicIntent as? MicIntent.ReplaceSentence)?.sentenceId
    val draftForFlow = if (uiState.activeMicIntent is MicIntent.WriteAtCursor || replacingSentenceId != null) {
        uiState.liveDraftText
    } else {
        ""
    }

    val lastSentenceOnBook = book.sentences.lastOrNull()?.id
    val cursorAtBookEnd = isLastPage && (uiState.cursorAfterSentenceId == null || uiState.cursorAfterSentenceId == lastSentenceOnBook) && book.sentences.isEmpty()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                val tailId = page.sentenceIds.lastOrNull() ?: lastSentenceOnBook
                controller.moveCursorAfter(tailId)
            },
    ) {
        val compactPage = maxWidth < 460.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = if (compactPage) 18.dp else 24.dp,
                    end = if (compactPage) 18.dp else 24.dp,
                    top = if (compactPage) 20.dp else 26.dp,
                    bottom = 48.dp,
                ),
        ) {
            // Page 1 Title Header (tappable to hear or re-speak book title!)
            if (page.pageIndex == 0) {
                val displayedTitle = if (isSpeakingTitle && uiState.liveDraftText.isNotBlank()) {
                    uiState.liveDraftText
                } else {
                    book.title
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { controller.playBookTitleVoice(book) }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        RubyBookTitle(
                            title = displayedTitle,
                            bookFont = bookFont,
                            hanziSize = if (compactPage) 21.sp else 26.sp,
                            pinyinSize = if (compactPage) 10.5.sp else 12.sp,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    // Discreet voice-rename micro button next to title
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(if (isSpeakingTitle) Vermilion else BookPaperWarm)
                            .border(1.dp, BookOutline, CircleShape)
                            .clickable { controller.startSpeakBookTitle() },
                        contentAlignment = Alignment.Center,
                    ) {
                        IconMicCapsule(
                            color = if (isSpeakingTitle) Color.White else InkSecondary,
                            size = 15.dp,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = BookRuleLine, thickness = 1.dp)
                Spacer(Modifier.height(22.dp))
            }

            // Story Paragraphs on this Page
            if (page.paragraphs.isEmpty() && cursorAtBookEnd) {
                Row(modifier = Modifier.padding(start = 72.dp, top = 8.dp)) {
                    BreathingInkCursorWithDraft(
                        liveDraftText = draftForFlow,
                        bookFont = bookFont,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    for (para in page.paragraphs) {
                        InteractiveRubyParagraph(
                            group = para,
                            bookFont = bookFont,
                            playingSentenceId = uiState.playingSentenceId,
                            selectedSentenceId = uiState.selectedSentenceId,
                            inspectedSentenceId = uiState.inspectedWord?.sentenceId,
                            inspectedWordSpan = uiState.inspectedWord?.span,
                            cursorAfterSentenceId = uiState.cursorAfterSentenceId
                                ?: if (isLastPage) lastSentenceOnBook else "__NONE__",
                            liveDraftText = draftForFlow,
                            isReplacingSentenceId = replacingSentenceId,
                            onTapSentenceDot = { s -> controller.onSentenceDotTapped(s) },
                            onTapCharacter = { s, charIdx -> controller.onCharacterTapped(s, charIdx) },
                            onPlaceCursorAfter = { sid -> controller.moveCursorAfter(sid) },
                        )
                    }
                }
            }
        }

        // Crisp Folio Page Stamp at Bottom Center
        val isJustStamped = uiState.newlyStampedPage == page.pageIndex
        val stampScale by animateFloatAsState(
            targetValue = if (isJustStamped) 1.28f else 1.0f,
            animationSpec = spring(dampingRatio = 0.52f, stiffness = 380f),
            label = "pageStamp",
        )
        Text(
            text = "— ${page.pageIndex + 1} —",
            fontFamily = FontFamily.Serif,
            fontSize = 12.5.sp,
            fontWeight = if (isJustStamped) FontWeight.Bold else FontWeight.Normal,
            color = if (isJustStamped) Vermilion else InkSubtle,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
                .scale(stampScale),
        )
    }
}
