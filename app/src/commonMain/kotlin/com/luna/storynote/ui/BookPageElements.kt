package com.luna.storynote.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luna.storynote.literacy.JiebaWordSegmenter
import com.luna.storynote.literacy.RubyCluster
import com.luna.storynote.literacy.RubyClusterer
import com.luna.storynote.ui.icons.IconIllustratePage
import com.luna.storynote.ui.icons.IconMicCapsule
import com.luna.storynote.ui.motion.springPressScale

@Composable
internal fun BookIllustrationPanel(
    pageIndex: Int,
    bitmap: ImageBitmap,
    modifier: Modifier = Modifier,
) {
    val stops = remember {
        arrayOf(
            0.00f to Color.Transparent, 0.04f to Color.Black.copy(alpha = 0.08f),
            0.11f to Color.Black.copy(alpha = 0.58f), 0.19f to Color.Black,
            0.81f to Color.Black, 0.89f to Color.Black.copy(alpha = 0.58f),
            0.96f to Color.Black.copy(alpha = 0.08f), 1.00f to Color.Transparent,
        )
    }
    Box(
        modifier = modifier
            .testTag("page_illustration_$pageIndex")
            .semantics { contentDescription = "插图" },
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(brush = Brush.horizontalGradient(*stops), blendMode = BlendMode.DstIn)
                    drawRect(brush = Brush.verticalGradient(*stops), blendMode = BlendMode.DstIn)
                    if (size.width > 0f && size.height > 0f) {
                        withTransform({ scale(scaleX = 1f, scaleY = size.height / size.width, pivot = center) }) {
                            val r = size.width * 0.54f
                            val cover = size.width.coerceAtLeast(size.height) * 1.6f
                            drawRect(
                                topLeft = Offset(center.x - cover, center.y - cover),
                                size = Size(cover * 2f, cover * 2f),
                                brush = Brush.radialGradient(
                                    0.58f to Color.Black, 0.80f to Color.Black.copy(alpha = 0.68f),
                                    0.94f to Color.Black.copy(alpha = 0.14f), 1.00f to Color.Transparent,
                                    center = center, radius = r,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                    }
                },
        )
    }
}

@Composable
internal fun IllustrationGeneratingPlaceholder(
    pageIndex: Int,
    modifier: Modifier = Modifier,
) {
    val inf = rememberInfiniteTransition(label = "illusShimmer")
    val pulse by inf.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "washPulse",
    )
    val warmPeach = Color(0xFFF6DFCA).copy(alpha = 0.45f + 0.35f * pulse)
    val warmAmber = Color(0xFFF8EAC8).copy(alpha = 0.30f + 0.35f * (1f - pulse))
    val softSage = Color(0xFFE1ECE2).copy(alpha = 0.25f + 0.25f * pulse)

    Box(
        modifier = modifier
            .testTag("page_illustration_loading_$pageIndex")
            .semantics { contentDescription = "正在绘制插图" }
            .background(
                Brush.radialGradient(
                    colors = listOf(warmPeach, warmAmber, softSage, BookPaper),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(20.dp),
        ) {
            IconIllustratePage(
                color = Vermilion.copy(alpha = 0.55f + 0.35f * pulse),
                size = 30.dp,
            )
            Text(
                text = "正在为第 ${pageIndex + 1} 页绘制插图…",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = InkSecondary,
            )
        }
    }
}

@Composable
internal fun BookTitleBlock(
    title: String,
    isSpeakingTitle: Boolean,
    bookFont: FontFamily,
    onPlayTitle: () -> Unit,
    onSpeakNewTitle: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .springPressScale(pressedScale = 0.97f, onClick = onPlayTitle)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            RubyBookTitle(
                title = title,
                bookFont = bookFont,
                centerAlign = true,
            )
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(if (isSpeakingTitle) Vermilion else BookPaperWarm)
                .border(1.dp, if (isSpeakingTitle) Vermilion else BookOutline, CircleShape)
                .testTag("title_mic_button")
                .semantics { contentDescription = "修改书名" }
                .springPressScale(pressedScale = 0.92f, onClick = onSpeakNewTitle)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            IconMicCapsule(
                color = if (isSpeakingTitle) Color.White else InkSecondary,
                size = 12.dp,
            )
            Text(
                text = if (isSpeakingTitle) "正在听…" else "改书名",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = if (isSpeakingTitle) Color.White else InkSubtle,
            )
        }
    }
}

private fun groupTitleClustersByWord(title: String): List<List<RubyCluster>> {
    val clusters = RubyClusterer.annotateAndCluster(title)
    if (clusters.isEmpty()) return emptyList()
    val tokens = JiebaWordSegmenter.segment(title)
    if (tokens.isEmpty()) return clusters.map { listOf(it) }

    val nonOverlapping = mutableListOf<IntRange>()
    var pos = 0
    while (pos < title.length) {
        val best = tokens
            .filter { it.startOffset == pos && it.endOffset > pos }
            .maxByOrNull { it.endOffset }
        val end = best?.endOffset?.coerceIn(pos + 1, title.length) ?: (pos + 1)
        nonOverlapping.add(pos until end)
        pos = end
    }

    val groups = mutableListOf<List<RubyCluster>>()
    var clusterIdx = 0
    for (range in nonOverlapping) {
        val bucket = mutableListOf<RubyCluster>()
        while (clusterIdx < clusters.size) {
            val firstCharIdx = clusters[clusterIdx].cells.firstOrNull()?.startIndexInSentence ?: break
            if (firstCharIdx in range) {
                bucket.add(clusters[clusterIdx])
                clusterIdx++
            } else if (firstCharIdx < range.first) {
                groups.add(listOf(clusters[clusterIdx]))
                clusterIdx++
            } else {
                break
            }
        }
        if (bucket.isNotEmpty()) groups.add(bucket)
    }
    while (clusterIdx < clusters.size) {
        groups.add(listOf(clusters[clusterIdx]))
        clusterIdx++
    }
    return groups
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RubyBookTitle(
    title: String,
    bookFont: FontFamily,
    modifier: Modifier = Modifier,
    hanziSize: TextUnit = 26.sp,
    pinyinSize: TextUnit = 12.sp,
    centerAlign: Boolean = true,
) {
    val wordGroups = remember(title) { groupTitleClustersByWord(title) }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val charCount = title.length.coerceAtLeast(1)
        val baseHanzi = if (maxWidth < 420.dp) 22f else hanziSize.value
        val baseCellDp = if (maxWidth < 420.dp) 30f else 35f
        val neededForOneLine = charCount * baseCellDp
        val scale = when {
            neededForOneLine > maxWidth.value && charCount <= 14 -> (maxWidth.value / neededForOneLine).coerceIn(0.76f, 1f)
            charCount > 14 -> 0.80f
            else -> 1f
        }
        val adaptedHanzi = (baseHanzi * scale).coerceIn(17.5f, hanziSize.value).sp
        val adaptedPinyin = (adaptedHanzi.value * (pinyinSize.value / hanziSize.value).coerceIn(0.44f, 0.50f)).coerceIn(9.5f, pinyinSize.value).sp
        val adaptedMinW = (baseCellDp * scale - 2f).coerceIn(22f, 34f).dp

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (centerAlign) Arrangement.Center else Arrangement.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (wordGroup in wordGroups) {
                Row(verticalAlignment = Alignment.Bottom) {
                    for (cluster in wordGroup) {
                        ClusterCellRow(
                            cluster = cluster,
                            bookFont = bookFont,
                            hanziSize = adaptedHanzi,
                            pinyinSize = adaptedPinyin,
                            hanziWeight = FontWeight.Bold,
                            hanziColor = InkPrimary,
                            pinyinColor = InkPinyin,
                            minHanziWidth = adaptedMinW,
                            pinyinMinHeight = 18.dp,
                            cellBackground = { Color.Transparent },
                            onClickCell = null,
                        )
                    }
                }
            }
        }
    }
}
