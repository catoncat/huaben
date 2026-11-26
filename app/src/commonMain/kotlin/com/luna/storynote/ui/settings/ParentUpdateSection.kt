package com.luna.storynote.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luna.storynote.ui.BookOutline
import com.luna.storynote.ui.BookPaper
import com.luna.storynote.ui.BookPaperWarm
import com.luna.storynote.ui.InkPrimary
import com.luna.storynote.ui.InkSecondary
import com.luna.storynote.ui.InkSubtle
import com.luna.storynote.ui.JadeInk
import com.luna.storynote.ui.JadeSoft
import com.luna.storynote.ui.Vermilion
import com.luna.storynote.ui.VermilionSoft
import com.luna.storynote.ui.motion.springPressScale
import com.luna.storynote.update.AppBuildInfo
import com.luna.storynote.update.AppUpdateConfig
import com.luna.storynote.update.AppUpdateState
import com.luna.storynote.update.AvailableAppUpdate
import java.io.File

@Composable
internal fun ParentUpdateSection(
    updateConfig: AppUpdateConfig,
    updateState: AppUpdateState,
    onCheckUpdate: () -> Unit,
    onDownloadAndInstall: (AvailableAppUpdate) -> Unit,
    onInstallReadyApk: (File) -> Unit,
    onSaveUpdateConfig: (AppUpdateConfig) -> Unit,
) {
    var showAdvanced by remember { mutableStateOf(false) }
    var autoCheck by remember { mutableStateOf(updateConfig.autoCheckEnabled) }
    var repoText by remember { mutableStateOf(updateConfig.githubRepo) }
    var customUrl by remember { mutableStateOf(updateConfig.customManifestUrl) }
    var ghToken by remember { mutableStateOf(updateConfig.githubToken) }

    LaunchedEffect(updateConfig) {
        autoCheck = updateConfig.autoCheckEnabled
        repoText = updateConfig.githubRepo
        customUrl = updateConfig.customManifestUrl
        ghToken = updateConfig.githubToken
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = BookPaperWarm,
        border = BorderStroke(1.dp, BookOutline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "应用版本 v${AppBuildInfo.VERSION_NAME}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = InkPrimary,
                    )
                    Text(
                        text = when (updateState) {
                            is AppUpdateState.Idle -> if (updateConfig.customManifestUrl.contains("cnb.cool")) {
                                "CNB 国内直连自动检测与一键升级"
                            } else {
                                "支持自动检测与一键升级"
                            }
                            is AppUpdateState.Checking -> "正在检查新版本…"
                            is AppUpdateState.UpToDate -> "已是最新版本"
                            is AppUpdateState.Available -> "发现新版本 v${updateState.update.versionName}"
                            is AppUpdateState.Downloading -> "正在下载 v${updateState.update.versionName} (${(updateState.progress * 100).toInt()}%)"
                            is AppUpdateState.ReadyToInstall -> "v${updateState.update.versionName} 已就绪"
                            is AppUpdateState.Error -> updateState.message
                        },
                        fontSize = 12.sp,
                        color = when (updateState) {
                            is AppUpdateState.Available, is AppUpdateState.ReadyToInstall -> Vermilion
                            is AppUpdateState.UpToDate -> JadeInk
                            is AppUpdateState.Error -> Vermilion
                            else -> InkSubtle
                        },
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = BookPaper,
                    border = BorderStroke(1.dp, BookOutline),
                    modifier = Modifier
                        .clip(CircleShape)
                        .springPressScale(enabled = updateState !is AppUpdateState.Checking && updateState !is AppUpdateState.Downloading) {
                            onCheckUpdate()
                        },
                ) {
                    Text(
                        text = if (updateState is AppUpdateState.Checking) "检查中" else "检查更新",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = InkPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            when (updateState) {
                is AppUpdateState.Available -> {
                    if (updateState.update.releaseNotes.isNotBlank()) {
                        Text(
                            text = updateState.update.releaseNotes.take(180),
                            fontSize = 12.sp,
                            color = InkSecondary,
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = Vermilion,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .springPressScale { onDownloadAndInstall(updateState.update) },
                    ) {
                        Box(modifier = Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = "一键下载并升级 v${updateState.update.versionName}",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }
                    }
                }
                is AppUpdateState.Downloading -> {
                    LinearProgressIndicator(
                        progress = { updateState.progress.coerceIn(0f, 1f) },
                        color = Vermilion,
                        trackColor = BookPaper,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                    )
                }
                is AppUpdateState.ReadyToInstall -> {
                    Surface(
                        shape = CircleShape,
                        color = JadeSoft,
                        border = BorderStroke(1.dp, JadeInk.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .springPressScale { onInstallReadyApk(updateState.apkFile) },
                    ) {
                        Box(modifier = Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = "立即安装 v${updateState.update.versionName}",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = JadeInk,
                            )
                        }
                    }
                }
                else -> Unit
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (showAdvanced) "收起更新源配置" else "更新源配置（仓库 / Token）",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = InkSecondary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { showAdvanced = !showAdvanced }
                        .padding(vertical = 2.dp),
                )
            }

            if (showAdvanced) {
                val fieldColors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = BookPaper,
                    unfocusedContainerColor = BookPaper,
                    focusedBorderColor = Vermilion,
                    unfocusedBorderColor = BookOutline,
                    focusedTextColor = InkPrimary,
                    unfocusedTextColor = InkPrimary,
                    cursorColor = Vermilion,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "启动时自动检查更新", fontSize = 13.sp, color = InkPrimary)
                    Switch(
                        checked = autoCheck,
                        onCheckedChange = { autoCheck = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = BookPaper,
                            checkedTrackColor = Vermilion,
                            uncheckedThumbColor = InkSecondary,
                            uncheckedTrackColor = BookPaper,
                        ),
                    )
                }
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = { customUrl = it },
                    singleLine = true,
                    label = { Text("更新清单 URL（默认 CNB 国内直连）", fontSize = 12.sp) },
                    placeholder = { Text(AppBuildInfo.DEFAULT_MANIFEST_URL, fontSize = 12.sp, color = InkSubtle) },
                    shape = RoundedCornerShape(10.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = repoText,
                    onValueChange = { repoText = it },
                    singleLine = true,
                    label = { Text("GitHub 兜底仓库（清空清单 URL 时生效）", fontSize = 12.sp) },
                    shape = RoundedCornerShape(10.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ghToken,
                    onValueChange = { ghToken = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("GitHub Token（私有仓库可选）", fontSize = 12.sp) },
                    shape = RoundedCornerShape(10.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                Surface(
                    shape = CircleShape,
                    color = VermilionSoft,
                    border = BorderStroke(1.dp, Vermilion.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .springPressScale {
                            onSaveUpdateConfig(
                                AppUpdateConfig(
                                    autoCheckEnabled = autoCheck,
                                    githubRepo = repoText.trim().ifEmpty { AppBuildInfo.DEFAULT_GITHUB_REPO },
                                    customManifestUrl = customUrl.trim(),
                                    githubToken = ghToken.trim(),
                                ),
                            )
                        },
                ) {
                    Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = "保存更新配置",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Vermilion,
                        )
                    }
                }
            }
        }
    }
}
