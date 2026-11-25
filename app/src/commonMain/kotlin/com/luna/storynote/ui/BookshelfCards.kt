package com.luna.storynote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luna.storynote.decodeFileToImageBitmap
import com.luna.storynote.model.StoryBook
import com.luna.storynote.ui.icons.BookFoilEmblem
import com.luna.storynote.ui.icons.IconPalette
import com.luna.storynote.ui.icons.IconPlus
import com.luna.storynote.ui.motion.springCombinedPressScale
import com.luna.storynote.ui.motion.springPressScale

@Composable
internal fun NewBlankHardcoverCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(topStart = 5.dp, bottomStart = 5.dp, topEnd = 18.dp, bottomEnd = 18.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.74f)
            .shadow(4.dp, shape, ambientColor = Color(0x221F1A14), spotColor = Color(0x281F1A14))
            .clip(shape)
            .background(BookPaper)
            .border(1.5.dp, BookOutline, shape)
            .testTag("new_book_button")
            .semantics { contentDescription = "新建故事书" }
            .springPressScale(pressedScale = 0.96f, onClick = onClick),
    ) {
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
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(56.dp)
                .clip(CircleShape)
                .background(VermilionSoft)
                .border(1.2.dp, Vermilion.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            IconPlus(color = Vermilion, size = 24.dp)
        }
    }
}

@Composable
internal fun HardcoverJournalCard(
    book: StoryBook,
    bookFont: FontFamily,
    onOpen: () -> Unit,
    onCycleCloth: () -> Unit,
    onLongPressDelete: () -> Unit,
) {
    val cloth = bookClothFor(book.coverColorIndex)
    val bookShape = RoundedCornerShape(topStart = 5.dp, bottomStart = 5.dp, topEnd = 18.dp, bottomEnd = 18.dp)
    val coverFile = book.coverIllustrationFile()
    val coverBitmap = remember(coverFile?.absolutePath, coverFile?.lastModified()) {
        coverFile?.let { decodeFileToImageBitmap(it) }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.74f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 4.dp, top = 3.dp)
                .clip(bookShape)
                .background(BookPageEdge1)
                .border(1.dp, BookOutline, bookShape),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 3.dp, bottom = 3.dp)
                .shadow(7.dp, bookShape, ambientColor = Color(0x331B1510), spotColor = Color(0x441B1510))
                .clip(bookShape)
                .background(cloth)
                .testTag("book_card_${book.id}")
                .springCombinedPressScale(
                    pressedScale = 0.96f,
                    onClick = onOpen,
                    onLongClick = onLongPressDelete,
                ),
        ) {
            if (coverBitmap != null) {
                Image(
                    bitmap = coverBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0x281B1510),
                                    Color(0x101B1510),
                                    Color(0x521B1510),
                                ),
                            ),
                        ),
                )
            }

            Box(
                modifier = Modifier
                    .width(16.dp)
                    .fillMaxHeight()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.22f),
                                Color.White.copy(alpha = 0.10f),
                                Color.Black.copy(alpha = 0.24f),
                            ),
                        ),
                    ),
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (coverBitmap != null) Color(0x441B1510) else Color.Transparent)
                    .springPressScale(pressedScale = 0.88f, onClick = onCycleCloth),
                contentAlignment = Alignment.Center,
            ) {
                IconPalette(color = FoilGold.copy(alpha = 0.9f), size = 16.dp)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 24.dp, end = 14.dp, top = 24.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                if (coverBitmap == null) {
                    BookFoilEmblem(
                        emblemIndex = book.emblemIndex,
                        color = FoilGold,
                        size = 32.dp,
                    )
                } else {
                    Box(modifier = Modifier.size(10.dp))
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = TitlePlatePaper.copy(alpha = 0.96f),
                    border = BorderStroke(1.dp, FoilGold),
                    shadowElevation = 2.dp,
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

                val pageCnt = book.paginate().size
                Text(
                    text = "${pageCnt} 页",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = FoilGold.copy(alpha = 0.92f),
                )
            }
        }
    }
}
