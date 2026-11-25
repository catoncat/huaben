package com.luna.storynote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luna.storynote.PinyinAnnotator
import com.luna.storynote.RubyCharCell
import com.luna.storynote.RubyCluster

/**
 * Renders a published-storybook Pinyin + Hanzi paragraph with:
 * - Authentic 2-character first-line indentation (`首行缩进两格`)
 * - Uniform character grid so 5-letter Pinyin (`chuán`, `zhǎng`) never creates uneven gaps
 * - Full descender clearance (`g`, `q`, `p`, `y`) and tone-mark headroom (`ǎ`, `ǚ`)
 * - Non-breaking punctuation clusters (`RubyCluster`) obeying Chinese GB/T 15834 line-edge rules
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RubyParagraph(
    paragraph: String,
    bookFont: FontFamily,
    modifier: Modifier = Modifier,
    hanziSize: TextUnit = 25.sp,
    pinyinSize: TextUnit = 12.sp,
    rowVerticalGap: Dp = 14.dp,
    indentTwoChars: Boolean = true,
) {
    val clusters = remember(paragraph) {
        PinyinAnnotator.annotateParagraphToClusters(paragraph)
    }
    if (clusters.isEmpty()) return

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalArrangement = Arrangement.spacedBy(rowVerticalGap),
    ) {
        if (indentTwoChars) {
            Spacer(Modifier.width(74.dp)) // 2 * 37dp uniform grid indent
        }
        for (cluster in clusters) {
            RubyClusterView(
                cluster = cluster,
                bookFont = bookFont,
                hanziSize = hanziSize,
                pinyinSize = pinyinSize,
                hanziWeight = FontWeight.Normal,
                hanziColor = BookInk,
                pinyinColor = PinyinInk,
                pinyinSlotMinHeight = 20.dp,
                minHanziCellWidth = 37.dp,
            )
        }
    }
}

/**
 * Centered Pinyin-annotated book/chapter heading.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RubyBookTitle(
    title: String,
    bookFont: FontFamily,
    modifier: Modifier = Modifier,
    hanziSize: TextUnit = 27.sp,
    pinyinSize: TextUnit = 12.5.sp,
    centerAlign: Boolean = true,
) {
    val clusters = remember(title) {
        PinyinAnnotator.annotateParagraphToClusters(title)
    }
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (centerAlign) Arrangement.Center else Arrangement.Start,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (cluster in clusters) {
            RubyClusterView(
                cluster = cluster,
                bookFont = bookFont,
                hanziSize = hanziSize,
                pinyinSize = pinyinSize,
                hanziWeight = FontWeight.Bold,
                hanziColor = BookInk,
                pinyinColor = PinyinInk,
                pinyinSlotMinHeight = 21.dp,
                minHanziCellWidth = 38.dp,
            )
        }
    }
}

/**
 * Compact Pinyin-annotated title for book cover cards on the shelf (with full `g`/`q`/`y` descender room).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RubyCoverTitle(
    title: String,
    bookFont: FontFamily,
    modifier: Modifier = Modifier,
) {
    val clusters = remember(title) {
        PinyinAnnotator.annotateParagraphToClusters(title)
    }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (cluster in clusters) {
            RubyClusterView(
                cluster = cluster,
                bookFont = bookFont,
                hanziSize = 19.sp,
                pinyinSize = 10.5.sp,
                hanziWeight = FontWeight.Bold,
                hanziColor = BookInk,
                pinyinColor = PinyinInk,
                pinyinSlotMinHeight = 17.dp,
                minHanziCellWidth = 28.dp,
            )
        }
    }
}

@Composable
private fun RubyClusterView(
    cluster: RubyCluster,
    bookFont: FontFamily,
    hanziSize: TextUnit,
    pinyinSize: TextUnit,
    hanziWeight: FontWeight,
    hanziColor: Color,
    pinyinColor: Color,
    pinyinSlotMinHeight: Dp,
    minHanziCellWidth: Dp,
) {
    Row(verticalAlignment = Alignment.Bottom) {
        for (cell in cluster.cells) {
            RubySingleCellView(
                cell = cell,
                bookFont = bookFont,
                hanziSize = hanziSize,
                pinyinSize = pinyinSize,
                hanziWeight = hanziWeight,
                hanziColor = hanziColor,
                pinyinColor = pinyinColor,
                pinyinSlotMinHeight = pinyinSlotMinHeight,
                minHanziCellWidth = minHanziCellWidth,
            )
        }
    }
}

@Composable
private fun RubySingleCellView(
    cell: RubyCharCell,
    bookFont: FontFamily,
    hanziSize: TextUnit,
    pinyinSize: TextUnit,
    hanziWeight: FontWeight,
    hanziColor: Color,
    pinyinColor: Color,
    pinyinSlotMinHeight: Dp,
    minHanziCellWidth: Dp,
) {
    val minWidth = if (cell.isHanzi) minHanziCellWidth else 15.dp
    val horizPad = if (cell.isHanzi) 1.dp else 0.dp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .widthIn(min = minWidth)
            .padding(horizontal = horizPad),
    ) {
        Box(
            modifier = Modifier
                .defaultMinSize(minHeight = pinyinSlotMinHeight)
                .padding(bottom = 2.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (cell.pinyin.isNotEmpty()) {
                Text(
                    text = cell.pinyin,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = pinyinSize,
                    lineHeight = (pinyinSize.value + 4f).sp,
                    letterSpacing = (-0.2).sp,
                    fontWeight = FontWeight.Medium,
                    color = pinyinColor,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Text(
            text = cell.charText,
            fontFamily = bookFont,
            fontSize = hanziSize,
            lineHeight = (hanziSize.value + 4f).sp,
            fontWeight = hanziWeight,
            color = hanziColor,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
    }
}
