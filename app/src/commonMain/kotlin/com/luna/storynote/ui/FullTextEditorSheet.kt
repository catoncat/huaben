package com.luna.storynote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luna.storynote.literacy.FullTextAudioAligner
import com.luna.storynote.model.StoryBook
import com.luna.storynote.ui.motion.springPressScale

@Composable
internal fun FullTextEditorSheet(
    book: StoryBook,
    bookFont: FontFamily,
    onCancel: () -> Unit,
    onSave: (newTitle: String, newFullText: String) -> Unit,
) {
    val initialBody = remember(book.id, book.updatedAtMs) {
        FullTextAudioAligner.renderEditableFullText(book)
    }
    var titleDraft by remember(book.id, book.updatedAtMs) { mutableStateOf(book.title) }
    var bodyDraft by remember(book.id, book.updatedAtMs) { mutableStateOf(initialBody) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BookPaper)
            .imePadding()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {}
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                    onCancel()
                    true
                } else {
                    false
                }
            }
            .testTag("full_text_editor_sheet"),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BookPaperWarm.copy(alpha = 0.72f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = BookPaper,
                    border = BorderStroke(1.dp, BookOutline),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .clip(CircleShape)
                        .testTag("full_text_cancel")
                        .semantics { contentDescription = "取消" }
                        .springPressScale(pressedScale = 0.92f, onClick = onCancel),
                ) {
                    Text(
                        text = "取消",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = InkSecondary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }

                Text(
                    text = "全文校对",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = InkPrimary,
                    modifier = Modifier.align(Alignment.Center),
                )

                Surface(
                    shape = CircleShape,
                    color = Vermilion,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clip(CircleShape)
                        .testTag("full_text_save")
                        .semantics { contentDescription = "完成" }
                        .springPressScale(pressedScale = 0.92f) {
                            onSave(titleDraft, bodyDraft)
                        },
                ) {
                    Text(
                        text = "完成",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                    )
                }
            }

            HorizontalDivider(color = BookRuleLine, thickness = 1.dp)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 680.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.Top,
                ) {
                    BasicTextField(
                        value = titleDraft,
                        onValueChange = { next ->
                            titleDraft = next.replace("\r", "").replace("\n", "")
                        },
                        textStyle = TextStyle(
                            fontFamily = bookFont,
                            fontSize = 24.sp,
                            lineHeight = 34.sp,
                            fontWeight = FontWeight.Bold,
                            color = InkPrimary,
                        ),
                        cursorBrush = SolidColor(Vermilion),
                        maxLines = 2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("full_text_title_input")
                            .semantics { contentDescription = "书名" },
                        decorationBox = { innerTextField ->
                            Box(modifier = Modifier.fillMaxWidth()) {
                                if (titleDraft.isEmpty()) {
                                    Text(
                                        text = "故事标题",
                                        fontFamily = bookFont,
                                        fontSize = 24.sp,
                                        lineHeight = 34.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = InkSubtle.copy(alpha = 0.6f),
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = BookRuleLine, thickness = 1.dp)
                    Spacer(Modifier.height(18.dp))

                    BasicTextField(
                        value = bodyDraft,
                        onValueChange = { bodyDraft = it },
                        textStyle = TextStyle(
                            fontFamily = bookFont,
                            fontSize = 19.sp,
                            lineHeight = 32.sp,
                            color = InkPrimary,
                            letterSpacing = 0.4.sp,
                        ),
                        cursorBrush = SolidColor(Vermilion),
                        minLines = 12,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("full_text_body_input")
                            .semantics { contentDescription = "故事正文" },
                        decorationBox = { innerTextField ->
                            Box(modifier = Modifier.fillMaxWidth()) {
                                if (bodyDraft.isEmpty()) {
                                    Text(
                                        text = "在这里直接修改文字，空行可分段…",
                                        fontFamily = bookFont,
                                        fontSize = 19.sp,
                                        lineHeight = 32.sp,
                                        color = InkSubtle.copy(alpha = 0.65f),
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )

                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
}
