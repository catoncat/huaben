package com.luna.storynote.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luna.storynote.BookStudioController
import com.luna.storynote.CollectedWordCard
import com.luna.storynote.StoryBook
import com.luna.storynote.StudioDestination
import com.luna.storynote.StudioUiState
import com.luna.storynote.rememberPlatformBookFontFamily

@Composable
fun StoryStudioRoot(
    uiState: StudioUiState,
    controller: BookStudioController,
    modifier: Modifier = Modifier,
) {
    val bookFont = rememberPlatformBookFontFamily()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DeskSurface),
    ) {
        when (uiState.destination) {
            StudioDestination.SHELF -> HardcoverBookshelfScreen(
                uiState = uiState,
                controller = controller,
                bookFont = bookFont,
            )
            StudioDestination.BOOK -> {
                val active = uiState.activeBook
                if (active != null) {
                    InteractiveBookSpreadScreen(
                        book = active,
                        uiState = uiState,
                        controller = controller,
                        bookFont = bookFont,
                    )
                } else {
                    HardcoverBookshelfScreen(uiState, controller, bookFont)
                }
            }
            StudioDestination.WORD_BOX -> WordBoxFlashcardScreen(
                uiState = uiState,
                controller = controller,
                bookFont = bookFont,
            )
        }

        uiState.subtleNotice?.let { msg ->
            Surface(
                shape = CircleShape,
                color = InkPrimary,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp),
            ) {
                Text(
                    text = msg,
                    color = BookPaper,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun HardcoverBookshelfScreen(
    uiState: StudioUiState,
    controller: BookStudioController,
    bookFont: FontFamily,
) {
    var bookPendingDelete by remember { mutableStateOf<StoryBook?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        // Minimalist Header: Title + Word Treasure Box Deck Pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "故事书架",
                fontFamily = bookFont,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = InkPrimary,
            )

            // Tactile Word Box Deck Pill
            Surface(
                shape = CircleShape,
                color = BookPaper,
                border = BorderStroke(1.dp, BookOutline),
                shadowElevation = 3.dp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { controller.openWordBox() },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconWordBoxDeck(filled = uiState.collectedWords.isNotEmpty(), color = JadeInk, size = 19.dp)
                    Text(
                        text = "字词盒",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = InkPrimary,
                    )
                    if (uiState.collectedWords.isNotEmpty()) {
                        Surface(shape = CircleShape, color = JadeSoft) {
                            Text(
                                text = uiState.collectedWords.size.toString(),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = JadeInk,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        // 3:4 Hardcover Journals Grid (Paper by FiftyThree aesthetic)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 196.dp),
            contentPadding = PaddingValues(bottom = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "__new_blank_book__") {
                NewBlankHardcoverCard(
                    onClick = { controller.createAndOpenNewBook() },
                )
            }
            items(uiState.books, key = { it.id }) { book ->
                HardcoverJournalCard(
                    book = book,
                    bookFont = bookFont,
                    onOpen = { controller.openBook(book.id) },
                    onCycleCloth = { controller.cycleCoverStyle(book.id) },
                    onLongPressDelete = { bookPendingDelete = book },
                )
            }
        }
    }

    bookPendingDelete?.let { target ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x551C1712))
                .clickable { bookPendingDelete = null },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = BookPaper,
                border = BorderStroke(1.dp, BookOutline),
                shadowElevation = 12.dp,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "《${target.title}》",
                        fontFamily = bookFont,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = InkPrimary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(
                            shape = CircleShape,
                            color = BookPaperWarm,
                            border = BorderStroke(1.dp, BookOutline),
                            modifier = Modifier.clickable {
                                controller.env.exportBook(target)
                                bookPendingDelete = null
                            },
                        ) {
                            Text(
                                text = "导出文稿",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = InkPrimary,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                            )
                        }
                        Surface(
                            shape = CircleShape,
                            color = Vermilion,
                            modifier = Modifier.clickable {
                                controller.deleteBook(target.id)
                                bookPendingDelete = null
                            },
                        ) {
                            Text(
                                text = "移除书本",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewBlankHardcoverCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(topStart = 5.dp, bottomStart = 5.dp, topEnd = 18.dp, bottomEnd = 18.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.74f)
            .shadow(4.dp, shape, ambientColor = Color(0x221F1A14), spotColor = Color(0x281F1A14))
            .clip(shape)
            .background(BookPaper)
            .border(1.5.dp, BookOutline, shape)
            .clickable(onClick = onClick),
    ) {
        // Subtle linen spine groove on left
        Box(
            modifier = Modifier
                .width(14.dp)
                .fillMaxHeight()
                .background(BookPaperWarm),
        )
        Box(
            modifier = Modifier
                .padding(start = 14.dp)
                .width(1.dp)
                .fillMaxHeight()
                .background(BookRuleLine),
        )

        // Centered geometric brass plus cross
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(56.dp)
                .clip(CircleShape)
                .background(VermilionSoft)
                .border(1.2.dp, Vermilion.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(22.dp)) {
                val sw = size.width * 0.11f
                drawLine(Vermilion, Offset(size.width / 2f, size.height * 0.16f), Offset(size.width / 2f, size.height * 0.84f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(Vermilion, Offset(size.width * 0.16f, size.height / 2f), Offset(size.width * 0.84f, size.height / 2f), strokeWidth = sw, cap = StrokeCap.Round)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HardcoverJournalCard(
    book: StoryBook,
    bookFont: FontFamily,
    onOpen: () -> Unit,
    onCycleCloth: () -> Unit,
    onLongPressDelete: () -> Unit,
) {
    val cloth = bookClothFor(book.coverColorIndex)
    val bookShape = RoundedCornerShape(topStart = 5.dp, bottomStart = 5.dp, topEnd = 18.dp, bottomEnd = 18.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.74f),
    ) {
        // Right-side paper block thickness peek
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 4.dp, top = 3.dp)
                .clip(bookShape)
                .background(BookPageEdge1)
                .border(1.dp, BookOutline, bookShape),
        )

        // Main Hardcover Cloth Board
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 3.dp, bottom = 3.dp)
                .shadow(7.dp, bookShape, ambientColor = Color(0x331B1510), spotColor = Color(0x441B1510))
                .clip(bookShape)
                .background(cloth)
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = onLongPressDelete,
                ),
        ) {
            // Realistic bound book-spine groove highlight & crease
            Box(
                modifier = Modifier
                    .width(16.dp)
                    .fillMaxHeight()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.18f),
                                Color.White.copy(alpha = 0.08f),
                                Color.Black.copy(alpha = 0.22f),
                            ),
                        ),
                    ),
            )

            // Discreet cover cloth/emblem cycle dot in top-right corner
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onCycleCloth),
                contentAlignment = Alignment.Center,
            ) {
                IconSwatchCycle(color = FoilGold.copy(alpha = 0.8f), size = 16.dp)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 24.dp, end = 14.dp, top = 26.dp, bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // Gold-foil line-art emblem
                BookFoilEmblem(
                    emblemIndex = book.emblemIndex,
                    color = FoilGold,
                    size = 32.dp,
                )

                // Traditional pasted cream `题签` Title Plate with Pinyin!
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = TitlePlatePaper,
                    border = BorderStroke(1.dp, FoilGold),
                    shadowElevation = 1.dp,
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        RubyCoverPlateTitle(
                            title = book.title,
                            bookFont = bookFont,
                        )
                    }
                }

                // Minimal folio count in gold foil at bottom
                val pageCnt = book.paginate().size
                Text(
                    text = "${pageCnt} 页",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = FoilGold.copy(alpha = 0.85f),
                )
            }
        }
    }
}
