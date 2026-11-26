package com.luna.storynote.update

import com.luna.storynote.PlatformEnvironment
import com.luna.storynote.model.StudioUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

internal class AppUpdateCoordinator(
    private val env: PlatformEnvironment,
    private val configStore: UpdateConfigStore,
    private val updateManager: AppUpdateManager,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<StudioUiState>,
    private val postNotice: (String) -> Unit,
) {
    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    fun initAndMaybeAutoCheck() {
        scope.launch(Dispatchers.IO) {
            val saved = configStore.load()
            val effective = if (saved.githubToken.isBlank()) {
                val platformToken = env.loadDefaultGithubToken()
                if (platformToken.isNotBlank()) saved.copy(githubToken = platformToken) else saved
            } else {
                saved
            }
            uiState.update { it.copy(updateConfig = effective) }
            if (effective.autoCheckEnabled) {
                delay(1800L)
                checkNow(silent = true)
            }
        }
    }

    fun saveUpdateConfig(config: AppUpdateConfig) {
        scope.launch(Dispatchers.IO) {
            configStore.save(config)
            uiState.update { it.copy(updateConfig = config) }
            postNotice("更新配置已保存")
        }
    }

    fun checkNow(silent: Boolean = false) {
        if (checkJob?.isActive == true) return
        checkJob = scope.launch(Dispatchers.IO) {
            if (!silent) {
                uiState.update { it.copy(updateState = AppUpdateState.Checking) }
            }
            val cfg = uiState.value.updateConfig
            when (val res = updateManager.checkForUpdate(cfg, AppBuildInfo.VERSION_NAME)) {
                is UpdateCheckOutcome.UpdateFound -> {
                    uiState.update { it.copy(updateState = AppUpdateState.Available(res.update)) }
                    if (!silent) postNotice("发现新版本 v${res.update.versionName}")
                }
                is UpdateCheckOutcome.UpToDate -> {
                    if (!silent) {
                        uiState.update { it.copy(updateState = AppUpdateState.UpToDate(AppBuildInfo.VERSION_NAME)) }
                    }
                }
                is UpdateCheckOutcome.Failed -> {
                    if (!silent) {
                        uiState.update { it.copy(updateState = AppUpdateState.Error(res.message)) }
                    }
                }
            }
        }
    }

    fun downloadAndInstall(update: AvailableAppUpdate) {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch(Dispatchers.IO) {
            val cfg = uiState.value.updateConfig
            val updatesDir = File(env.storageRoot, "updates").apply { mkdirs() }
            uiState.update { it.copy(updateState = AppUpdateState.Downloading(update, 0f)) }
            try {
                val apkFile = updateManager.downloadApk(update, cfg, updatesDir) { pct ->
                    uiState.update { it.copy(updateState = AppUpdateState.Downloading(update, pct)) }
                }
                uiState.update { it.copy(updateState = AppUpdateState.ReadyToInstall(update, apkFile)) }
                val notice = env.installUpdateApk(apkFile)
                if (!notice.isNullOrBlank()) {
                    postNotice(notice)
                }
            } catch (e: Exception) {
                val msg = e.message ?: "下载升级包失败"
                uiState.update { it.copy(updateState = AppUpdateState.Error(msg)) }
                postNotice(msg)
            }
        }
    }

    fun triggerInstallReadyApk(apkFile: File) {
        val notice = env.installUpdateApk(apkFile)
        if (!notice.isNullOrBlank()) postNotice(notice)
    }

    fun openReleasePage(url: String) {
        if (url.isNotBlank()) env.openExternalUrl(url)
    }

    fun cancelAll() {
        checkJob?.cancel()
        downloadJob?.cancel()
    }
}
