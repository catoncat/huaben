package com.luna.storynote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.luna.storynote.ui.StoryStudioRoot

class MainActivity : ComponentActivity() {
    private var pendingGrantedCallback: (() -> Unit)? = null
    private lateinit var env: AndroidPlatformEnvironment
    private lateinit var controller: BookStudioController

    private val requestMicLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val cb = pendingGrantedCallback
        pendingGrantedCallback = null
        if (granted) cb?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        env = AndroidPlatformEnvironment(
            appContext = applicationContext,
            activityProvider = { this },
        )
        controller = BookStudioController(
            env = env,
            ensureMicPermission = { onGranted ->
                val ok = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                if (ok) {
                    onGranted()
                } else {
                    pendingGrantedCallback = onGranted
                    requestMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
        )

        setContent {
            val uiState by controller.uiState.collectAsState()
            MaterialTheme {
                StoryStudioRoot(
                    uiState = uiState,
                    controller = controller,
                    modifier = Modifier.statusBarsPadding(),
                )
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            controller.dispose()
            env.shutdown()
        }
        super.onDestroy()
    }
}
