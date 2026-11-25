package com.luna.storynote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luna.storynote.BookStudioController
import com.luna.storynote.model.StudioDestination
import com.luna.storynote.model.StudioUiState
import com.luna.storynote.rememberPlatformBookFontFamily
import com.luna.storynote.ui.motion.StudioDestinationTransition
import com.luna.storynote.ui.settings.ParentSettingsSheet

@Composable
fun StoryStudioRoot(
    uiState: StudioUiState,
    controller: BookStudioController,
    modifier: Modifier = Modifier,
) {
    val bookFont = rememberPlatformBookFontFamily()
    val rootBackground = if (uiState.destination == StudioDestination.BOOK) BookPaper else DeskSurface

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(rootBackground),
    ) {
        StudioDestinationTransition(
            destination = uiState.destination,
            modifier = Modifier.fillMaxSize(),
        ) { dest ->
            when (dest) {
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
        }

        if (uiState.isParentSettingsOpen) {
            ParentSettingsSheet(
                uiState = uiState,
                bookFont = bookFont,
                onTestConnection = { controller.testAiConnectivity(it) },
                onSave = { controller.saveAiConfig(it) },
                onResetDefaults = { controller.resetAiConfigToDefaults() },
                onClear = { controller.clearAiConfig() },
                onDismiss = { controller.closeParentSettings() },
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
