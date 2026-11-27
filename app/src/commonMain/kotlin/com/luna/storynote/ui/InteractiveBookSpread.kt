package com.luna.storynote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luna.storynote.BookStudioController
import com.luna.storynote.decodeFileToImageBitmap
import com.luna.storynote.model.BookPage
import com.luna.storynote.model.StoryBook
import com.luna.storynote.model.StudioUiState
import com.luna.storynote.ui.icons.BraunTactileMicButton
import com.luna.storynote.ui.icons.IconChevronBack
import com.luna.storynote.ui.icons.IconSparkStar
import com.luna.storynote.ui.motion.isLandscapeSpreadWidth
import com.luna.storynote.ui.motion.pagerPageDepth
import com.luna.storynote.ui.motion.springPressScale
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InteractiveBookSpreadScreen(
    book: StoryBook,
    uiState: StudioUiState,
    controller: BookStudioController,
    bookFont: FontFamily,
) {
    val pages = remember(book.clauses, book.title) { book.paginate() }
    val pageCount = pages.size.coerceAtLeast(1)
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(BookPaper),
    ) {
        val isLandscapeSpread = isLandscapeSpreadWidth(maxWidth, maxHeight)
        val isNarrowPhone = maxWidth < 560.dp
        val showDesktopPageChevrons = maxWidth >= 760.dp
        // On mobile phones, open 100% pure immersive paper; tapping the page smoothly reveals the single-row bar!
        var chromeVisible by remember(book.id, isNarrowPhone) { mutableStateOf(!isNarrowPhone) }
        val topBarVisible = chromeVisible ||
            uiState.isFullBookPlaying ||
            uiState.illustratingPageIndex != null ||
            uiState.isVoiceSparkSpeaking
        val topClearance = if (isNarrowPhone) 14.dp else 56.dp
        val targetPage = uiState.currentPageIndex.coerceIn(0, pageCount - 1)
        val pagerState = rememberPagerState(
            initialPage = targetPage,
            pageCount = { pageCount },
        )

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        LaunchedEffect(targetPage, pageCount) {
            if (pagerState.currentPage != targetPage) {
                pagerState.animateScrollToPage(targetPage)
            }
        }

        LaunchedEffect(pagerState.settledPage) {
            if (pagerState.settledPage != uiState.currentPageIndex) {
                controller.setCurrentPage(pagerState.settledPage)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { ev ->
                    val modalOpen = uiState.isRecording ||
                        uiState.inspectedWord != null ||
                        uiState.isTimeMachineOpen ||
                        uiState.isFullTextEditorOpen ||
                        uiState.isParentSettingsOpen
                    if (!modalOpen && ev.type == KeyEventType.KeyDown) {
                        when (ev.key) {
                            Key.DirectionLeft -> {
                                if (pagerState.currentPage > 0) {
                                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                                    true
                                } else {
                                    false
                                }
                            }
                            Key.DirectionRight -> {
                                if (pagerState.currentPage < pageCount - 1) {
                                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                                    true
                                } else {
                                    false
                                }
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                },
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                pageSpacing = 0.dp,
            ) { pageIdx ->
                val page = pages.getOrElse(pageIdx) { BookPage(pageIdx, emptyList()) }
                val activeIllus = book.activeIllustrationFor(page.pageIndex)
                val illusFile = activeIllus?.let { book.illustrationFile(it) }
                val illusBitmap = remember(illusFile?.absolutePath, illusFile?.lastModified()) {
                    illusFile?.let { decodeFileToImageBitmap(it) }
                }
                val isIllustratingThisPage = uiState.illustratingPageIndex == page.pageIndex
                val depthMod = Modifier.pagerPageDepth(pagerState, pageIdx)

                if (isLandscapeSpread && (illusBitmap != null || isIllustratingThisPage)) {
                    TwoPageIllustratedSpread(
                        book = book,
                        page = page,
                        illustrationBitmap = illusBitmap,
                        isIllustrating = isIllustratingThisPage,
                        isLastPage = page.pageIndex == pages.lastIndex,
                        uiState = uiState,
                        controller = controller,
                        bookFont = bookFont,
                        topClearance = topClearance,
                        onTapPageBlank = { chromeVisible = !chromeVisible },
                        modifier = depthMod,
                    )
                } else {
                    SingleHardcoverPageSheet(
                        book = book,
                        page = page,
                        illustrationBitmap = illusBitmap,
                        isIllustrating = isIllustratingThisPage,
                        isLandscapeCentered = isLandscapeSpread,
                        isLastPage = page.pageIndex == pages.lastIndex,
                        uiState = uiState,
                        controller = controller,
                        bookFont = bookFont,
                        topClearance = topClearance,
                        onTapPageBlank = { chromeVisible = !chromeVisible },
                        modifier = depthMod,
                    )
                }
            }

            if (showDesktopPageChevrons && !uiState.isRecording && pagerState.currentPage > 0) {
                EdgePageTurnControl(
                    isForward = false,
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .testTag("prev_page_button")
                        .semantics { contentDescription = "上一页" },
                )
            }

            if (showDesktopPageChevrons && !uiState.isRecording && pagerState.currentPage < pageCount - 1) {
                EdgePageTurnControl(
                    isForward = true,
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(pageCount - 1))
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .testTag("next_page_button")
                        .semantics { contentDescription = "下一页" },
                )
            }

            // Single-row floating reader chrome: tap-to-reveal on phone, never wraps onto 2 rows!
            AnimatedVisibility(
                visible = topBarVisible,
                enter = fadeIn() + slideInVertically { -it / 2 },
                exit = fadeOut() + slideOutVertically { -it / 2 },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = if (isLandscapeSpread) 24.dp else 12.dp, vertical = 10.dp),
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    CircularChromeIconButton(
                        onClick = { controller.returnToShelf() },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .testTag("back_button")
                            .semantics { contentDescription = "返回" },
                    ) {
                        IconChevronBack(color = InkPrimary, size = 18.dp)
                    }

                    if (!isNarrowPhone) {
                        Text(
                            text = "${pagerState.currentPage + 1} / $pageCount",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = InkSubtle,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }

                    ReaderActionPill(
                        hasContent = book.clauses.isNotEmpty(),
                        isFullBookPlaying = uiState.isFullBookPlaying,
                        isAiConfigured = uiState.isAiConfigured,
                        isIllustratingThisPage = uiState.illustratingPageIndex == pagerState.currentPage,
                        isIllustratingAnyPage = uiState.illustratingPageIndex != null,
                        isVoiceSparkSpeaking = uiState.isVoiceSparkSpeaking,
                        onToggleReadAlong = { controller.toggleFullBookReadAlong() },
                        onIllustratePage = { controller.illustrateCurrentPage() },
                        onTriggerSpark = { controller.triggerVoiceSpark() },
                        onOpenTimeMachine = { controller.openTimeMachine() },
                        onOpenFullTextEditor = { controller.openFullTextEditor() },
                        compact = isNarrowPhone,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }

            var dismissedSparkQuestion by remember(uiState.activeSparkQuestion) { mutableStateOf(false) }
            val sparkQuestion = uiState.activeSparkQuestion
            val showSparkPill = !dismissedSparkQuestion && (uiState.isVoiceSparkSpeaking || !sparkQuestion.isNullOrBlank())
            AnimatedVisibility(
                visible = showSparkPill,
                enter = fadeIn() + scaleIn(initialScale = 0.94f),
                exit = fadeOut() + scaleOut(targetScale = 0.94f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topClearance + 2.dp, start = 20.dp, end = 20.dp),
            ) {
                val sparkText = if (!sparkQuestion.isNullOrBlank()) sparkQuestion else "思考中…"
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = JadeSoft.copy(alpha = 0.96f),
                    border = BorderStroke(1.dp, JadeInk.copy(alpha = 0.28f)),
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .testTag("voice_spark_pill")
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (!sparkQuestion.isNullOrBlank()) dismissedSparkQuestion = true
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconSparkStar(color = JadeInk, size = 16.dp)
                        Text(
                            text = sparkText,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = InkPrimary,
                        )
                    }
                }
            }

            val selectedId = uiState.selectedClauseId
            AnimatedVisibility(
                visible = selectedId != null && !uiState.isRecording,
                enter = fadeIn() + scaleIn(initialScale = 0.88f),
                exit = fadeOut() + scaleOut(targetScale = 0.88f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 98.dp),
            ) {
                if (selectedId != null) {
                    FloatingSentenceEditCapsule(
                        showVoiceRevise = uiState.isAiConfigured,
                        isPlayingThisClause = uiState.playingClauseId == selectedId,
                        onPlayClause = { controller.playClauseById(selectedId) },
                        onRespeak = { controller.startRespeakClause(selectedId) },
                        onInsertAfter = { controller.startInsertAfterClause(selectedId) },
                        onTimeMachine = { controller.openTimeMachine(selectedId) },
                        onErase = { controller.eraseClause(selectedId) },
                        onVoiceRevise = { controller.startVoiceRevision(selectedId) },
                        onDismiss = { controller.dismissSelectionAndPopovers() },
                    )
                }
            }

            AnimatedVisibility(
                visible = uiState.undoBanner != null,
                enter = fadeIn() + scaleIn(initialScale = 0.92f),
                exit = fadeOut() + scaleOut(targetScale = 0.92f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 18.dp, bottom = 24.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = InkPrimary,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .springPressScale { controller.undoEraseClause() },
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

            BraunTactileMicButton(
                isRecording = uiState.isRecording,
                voiceEnergy = uiState.voiceEnergy,
                onClick = { controller.togglePrimaryMicButton() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .testTag("mic_button")
                    .semantics { contentDescription = "录音" },
            )
        }

        uiState.inspectedWord?.let { inspection ->
            TianZiGeWordOverlay(
                inspection = inspection,
                uiState = uiState,
                controller = controller,
                bookFont = bookFont,
            )
        }

        if (uiState.isTimeMachineOpen) {
            TimeMachineSheet(
                book = book,
                uiState = uiState,
                controller = controller,
                bookFont = bookFont,
            )
        }

        if (uiState.isFullTextEditorOpen) {
            FullTextEditorSheet(
                book = book,
                bookFont = bookFont,
                onCancel = { controller.closeFullTextEditor() },
                onSave = { newTitle, newFullText -> controller.saveFullTextEdit(newTitle, newFullText) },
            )
        }
    }
}
