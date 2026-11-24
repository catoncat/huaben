package com.luna.storynote

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val storyViewModel: StoryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val uiState by storyViewModel.uiState.collectAsStateWithLifecycle()

            // Keep tablet/phone screen awake whenever recording or saving a story
            LaunchedEffect(uiState.recMode) {
                if (uiState.recMode != RecMode.IDLE) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            MaterialTheme {
                StoryNoteAppScreen(
                    uiState = uiState,
                    viewModel = storyViewModel,
                )
            }
        }
    }
}
