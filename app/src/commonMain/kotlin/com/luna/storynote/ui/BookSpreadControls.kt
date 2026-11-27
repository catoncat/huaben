package com.luna.storynote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luna.storynote.ui.icons.IconChevronBack
import com.luna.storynote.ui.icons.IconChevronForward
import com.luna.storynote.ui.icons.IconCloseCross
import com.luna.storynote.ui.icons.IconEraserBlock
import com.luna.storynote.ui.icons.IconIllustratePage
import com.luna.storynote.ui.icons.IconInsertSentence
import com.luna.storynote.ui.icons.IconPauseAudio
import com.luna.storynote.ui.icons.IconPenLine
import com.luna.storynote.ui.icons.IconPlayAudio
import com.luna.storynote.ui.icons.IconRespeakWave
import com.luna.storynote.ui.icons.IconSparkStar
import com.luna.storynote.ui.icons.IconTimeMachine
import com.luna.storynote.ui.icons.IconVoiceRevise
import com.luna.storynote.ui.motion.springCombinedPressScale
import com.luna.storynote.ui.motion.springPressScale

@Composable
internal fun ReaderActionPill(
    hasContent: Boolean,
    isFullBookPlaying: Boolean,
    isAiConfigured: Boolean,
    isIllustratingThisPage: Boolean,
    isIllustratingAnyPage: Boolean,
    isVoiceSparkSpeaking: Boolean,
    onToggleReadAlong: () -> Unit,
    onIllustratePage: () -> Unit,
    onTriggerSpark: () -> Unit,
    onOpenTimeMachine: () -> Unit,
    onOpenFullTextEditor: () -> Unit = {},
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = BookPaperWarm.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, BookOutline),
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (compact) 4.dp else 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LabelledCapsuleButton(
                label = if (isFullBookPlaying) "暂停" else "朗读",
                labelColor = when {
                    isFullBookPlaying -> Vermilion
                    hasContent -> InkPrimary
                    else -> InkSubtle
                },
                containerColor = if (isFullBookPlaying) HighlightSun else Color.Transparent,
                enabled = hasContent,
                compact = compact,
                onClick = onToggleReadAlong,
                modifier = Modifier
                    .testTag("read_along_button")
                    .semantics { contentDescription = "朗读" },
            ) {
                if (isFullBookPlaying) {
                    IconPauseAudio(color = Vermilion, size = 14.dp)
                } else {
                    IconPlayAudio(
                        color = if (hasContent) InkPrimary else InkSubtle.copy(alpha = 0.45f),
                        size = 14.dp,
                    )
                }
            }

            if (isAiConfigured) {
                PillDivider()
                LabelledCapsuleButton(
                    label = "配图",
                    labelColor = if (isIllustratingThisPage) Vermilion else if (isIllustratingAnyPage) InkSubtle else InkPrimary,
                    containerColor = if (isIllustratingThisPage) HighlightSun else Color.Transparent,
                    enabled = !isIllustratingAnyPage,
                    compact = compact,
                    onClick = onIllustratePage,
                    modifier = Modifier
                        .testTag("illustrate_page_button")
                        .semantics { contentDescription = "配图" },
                ) {
                    IconIllustratePage(
                        color = if (isIllustratingThisPage) Vermilion else if (isIllustratingAnyPage) InkSubtle else InkPrimary,
                        size = 14.dp,
                    )
                }

                PillDivider()
                LabelledCapsuleButton(
                    label = "灵感",
                    labelColor = JadeInk,
                    containerColor = if (isVoiceSparkSpeaking) JadeSoft else Color.Transparent,
                    enabled = !isVoiceSparkSpeaking,
                    compact = compact,
                    onClick = onTriggerSpark,
                    modifier = Modifier
                        .testTag("spark_star_button")
                        .semantics { contentDescription = "灵感" },
                ) {
                    IconSparkStar(color = JadeInk, size = 14.dp)
                }
            }

            PillDivider()
            LabelledCapsuleButton(
                label = "历史",
                labelColor = InkPrimary,
                compact = compact,
                onClick = onOpenTimeMachine,
                modifier = Modifier
                    .testTag("time_machine_button")
                    .semantics { contentDescription = "历史" },
            ) {
                IconTimeMachine(color = InkPrimary, size = 14.dp)
            }

            PillDivider()
            LongPressCapsuleButton(
                label = "校对",
                labelColor = InkPrimary,
                compact = compact,
                onClick = {},
                onLongClick = onOpenFullTextEditor,
                modifier = Modifier
                    .testTag("proofread_button")
                    .semantics { contentDescription = "校对" },
            ) {
                IconPenLine(color = InkPrimary, size = 14.dp)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FloatingSentenceEditCapsule(
    showVoiceRevise: Boolean,
    isPlayingThisClause: Boolean = false,
    onPlayClause: () -> Unit = {},
    onRespeak: () -> Unit,
    onInsertAfter: () -> Unit,
    onTimeMachine: () -> Unit,
    onErase: () -> Unit,
    onVoiceRevise: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = BookPaper,
        border = BorderStroke(1.dp, BookOutline),
        shadowElevation = 10.dp,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .widthIn(max = 520.dp),
    ) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            LabelledCapsuleButton(
                label = "播放",
                labelColor = if (isPlayingThisClause) Vermilion else InkPrimary,
                containerColor = if (isPlayingThisClause) HighlightSun else BookPaperWarm,
                onClick = onPlayClause,
                modifier = Modifier
                    .testTag("edit_play_button")
                    .semantics { contentDescription = "播放本句" },
            ) {
                if (isPlayingThisClause) {
                    IconPauseAudio(color = Vermilion, size = 14.dp)
                } else {
                    IconPlayAudio(color = InkPrimary, size = 14.dp)
                }
            }

            LabelledCapsuleButton(
                label = "重说",
                labelColor = InkPrimary,
                containerColor = BookPaperWarm,
                onClick = onRespeak,
                modifier = Modifier
                    .testTag("edit_rerecord_button")
                    .semantics { contentDescription = "重说本句" },
            ) {
                IconRespeakWave(color = InkPrimary, size = 15.dp)
            }

            LabelledCapsuleButton(
                label = "插句",
                labelColor = JadeInk,
                containerColor = BookPaperWarm,
                onClick = onInsertAfter,
                modifier = Modifier
                    .testTag("edit_insert_after_button")
                    .semantics { contentDescription = "续写插句" },
            ) {
                IconInsertSentence(color = JadeInk, size = 15.dp)
            }

            if (showVoiceRevise) {
                LabelledCapsuleButton(
                    label = "AI调整",
                    labelColor = Vermilion,
                    containerColor = BookPaperWarm,
                    onClick = onVoiceRevise,
                    modifier = Modifier
                        .testTag("edit_voice_revise_button")
                        .semantics { contentDescription = "AI调整" },
                ) {
                    IconVoiceRevise(color = Vermilion, size = 15.dp)
                }
            }

            LabelledCapsuleButton(
                label = "历史",
                labelColor = InkPrimary,
                containerColor = BookPaperWarm,
                onClick = onTimeMachine,
                modifier = Modifier
                    .testTag("edit_time_machine_button")
                    .semantics { contentDescription = "本句历史" },
            ) {
                IconTimeMachine(color = InkPrimary, size = 15.dp)
            }

            LabelledCapsuleButton(
                label = "删",
                labelColor = Vermilion,
                containerColor = VermilionSoft,
                onClick = onErase,
                modifier = Modifier
                    .testTag("edit_erase_button")
                    .semantics { contentDescription = "删除本句" },
            ) {
                IconEraserBlock(color = Vermilion, size = 15.dp)
            }

            Box(
                modifier = Modifier
                    .size(30.dp)
                    .align(Alignment.CenterVertically)
                    .clip(CircleShape)
                    .springPressScale(pressedScale = 0.88f, onClick = onDismiss)
                    .semantics { contentDescription = "关闭" },
                contentAlignment = Alignment.Center,
            ) {
                IconCloseCross(color = InkSubtle, size = 14.dp)
            }
        }
    }
}

@Composable
private fun LabelledCapsuleButton(
    label: String,
    labelColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Transparent,
    enabled: Boolean = true,
    compact: Boolean = false,
    icon: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .height(if (compact) 30.dp else 32.dp)
            .clip(CircleShape)
            .background(containerColor)
            .springPressScale(pressedScale = 0.92f, enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 5.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp),
    ) {
        icon()
        Text(
            text = label,
            fontSize = if (compact) 11.sp else 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = labelColor,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun LongPressCapsuleButton(
    label: String,
    labelColor: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Transparent,
    compact: Boolean = false,
    icon: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .height(if (compact) 30.dp else 32.dp)
            .clip(CircleShape)
            .background(containerColor)
            .springCombinedPressScale(pressedScale = 0.92f, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = if (compact) 5.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp),
    ) {
        icon()
        Text(
            text = label,
            fontSize = if (compact) 11.sp else 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = labelColor,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun PillDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(14.dp)
            .background(BookRuleLine),
    )
}

@Composable
internal fun CircularChromeIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = BookPaper,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .size(38.dp)
            .clip(CircleShape)
            .springPressScale(pressedScale = 0.90f, enabled = enabled, onClick = onClick),
        shape = CircleShape,
        color = containerColor,
        border = BorderStroke(1.dp, BookOutline),
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@Composable
internal fun EdgePageTurnControl(
    isForward: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = modifier
            .fillMaxHeight(0.62f)
            .width(42.dp)
            .hoverable(interactionSource = interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = BookPaperWarm.copy(alpha = if (hovered) 0.94f else 0.56f),
            border = BorderStroke(1.dp, BookOutline.copy(alpha = if (hovered) 0.9f else 0.45f)),
            modifier = Modifier.size(30.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                val iconColor = InkSecondary.copy(alpha = if (hovered) 0.95f else 0.62f)
                if (isForward) {
                    IconChevronForward(color = iconColor, size = 15.dp)
                } else {
                    IconChevronBack(color = iconColor, size = 15.dp)
                }
            }
        }
    }
}
