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
import com.luna.storynote.ui.StoryBookRootScreen

class MainActivity : ComponentActivity() {
    private var pendingPermCallback: (() -> Unit)? = null
    private lateinit var controller: StoryController

    private val requestMicLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val cb = pendingPermCallback
        pendingPermCallback = null
        if (granted) {
            cb?.invoke()
        } else {
            controller.showToast("请允许麦克风权限后再开始讲故事哦")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val env = AndroidPlatformEnvironment(
            appContext = applicationContext,
            activityProvider = { this },
        )
        controller = StoryController(
            env = env,
            externalEnsureMicPermission = { onGranted ->
                val ok = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                if (ok) {
                    onGranted()
                } else {
                    pendingPermCallback = onGranted
                    requestMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
        )

        setContent {
            val uiState by controller.uiState.collectAsState()
            MaterialTheme {
                StoryBookRootScreen(
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
        }
        super.onDestroy()
    }
}
