package com.luna.storynote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luna.storynote.BookStudioController
import com.luna.storynote.model.StoryBook
import com.luna.storynote.model.StudioUiState
import com.luna.storynote.ui.icons.IconSettingsGear
import com.luna.storynote.ui.icons.IconWordBoxDeck
import com.luna.storynote.ui.motion.ModalPaperSheet
import com.luna.storynote.ui.motion.bookshelfColumnCount
import com.luna.storynote.ui.motion.springCombinedPressScale
import com.luna.storynote.ui.motion.springPressScale

@Composable
internal fun HardcoverBookshelfScreen(
    uiState: StudioUiState,
    controller: BookStudioController,
    bookFont: FontFamily,
) {
    var bookPendingDelete by remember { mutableStateOf<StoryBook?>(null) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val cols = bookshelfColumnCount(maxWidth)
        val horizontalPad = when (cols) {
            2 -> 16.dp
            3 -> 20.dp
            else -> 24.dp
        }
        val gridGap = if (cols == 2) 14.dp else 18.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPad, vertical = 18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "故事书架",
                    fontFamily = bookFont,
                    fontSize = if (cols == 2) 24.sp else 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = InkPrimary,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = BookPaper,
                        border = BorderStroke(1.dp, BookOutline),
                        shadowElevation = 3.dp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .testTag("word_box_button")
                            .semantics { contentDescription = "字词盒" }
                            .springPressScale(pressedScale = 0.94f, onClick = { controller.openWordBox() }),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
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

                    Surface(
                        shape = CircleShape,
                        color = BookPaper,
                        border = BorderStroke(1.dp, BookOutline),
                        shadowElevation = 3.dp,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .testTag("settings_gear")
                            .semantics { contentDescription = "家长设置" }
                            .springCombinedPressScale(
                                pressedScale = 0.90f,
                                onClick = {},
                                onLongClick = { controller.openParentSettings() },
                            ),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            IconSettingsGear(color = InkSecondary, size = 18.dp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                contentPadding = PaddingValues(bottom = 36.dp),
                horizontalArrangement = Arrangement.spacedBy(gridGap),
                verticalArrangement = Arrangement.spacedBy(if (cols == 2) 18.dp else 22.dp),
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
    }

    bookPendingDelete?.let { target ->
        ModalPaperSheet(onDismiss = { bookPendingDelete = null }) {
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
                            modifier = Modifier
                                .clip(CircleShape)
                                .springPressScale {
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
                            modifier = Modifier
                                .clip(CircleShape)
                                .springPressScale {
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
