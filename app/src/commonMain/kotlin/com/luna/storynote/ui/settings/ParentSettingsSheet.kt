package com.luna.storynote.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luna.storynote.model.AiGatewayConfig
import com.luna.storynote.model.StudioUiState
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
import com.luna.storynote.ui.icons.IconCloseCross
import com.luna.storynote.ui.icons.IconEyeVisibility
import com.luna.storynote.ui.motion.ModalPaperSheet
import com.luna.storynote.ui.motion.springPressScale
import com.luna.storynote.update.AppUpdateConfig
import com.luna.storynote.update.AvailableAppUpdate
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParentSettingsSheet(
    uiState: StudioUiState,
    bookFont: FontFamily,
    onTestConnection: (AiGatewayConfig) -> Unit,
    onSave: (AiGatewayConfig) -> Unit,
    onResetDefaults: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    onCheckUpdate: () -> Unit = {},
    onDownloadAndInstall: (AvailableAppUpdate) -> Unit = {},
    onInstallReadyApk: (File) -> Unit = {},
    onSaveUpdateConfig: (AppUpdateConfig) -> Unit = {},
) {
    val cfg = uiState.aiConfig
    var baseUrl by remember { mutableStateOf(cfg.baseUrl) }
    var apiKey by remember { mutableStateOf(cfg.apiKey) }
    var textModel by remember { mutableStateOf(cfg.textModel) }
    var imageModel by remember { mutableStateOf(cfg.imageModel) }
    var useSeparateImage by remember { mutableStateOf(cfg.imageBaseUrl.isNotBlank() || cfg.imageApiKey.isNotBlank()) }
    var imageBaseUrl by remember { mutableStateOf(cfg.imageBaseUrl) }
    var imageApiKey by remember { mutableStateOf(cfg.imageApiKey) }
    var revealPrimaryKey by remember { mutableStateOf(false) }
    var revealImageKey by remember { mutableStateOf(false) }

    LaunchedEffect(cfg) {
        baseUrl = cfg.baseUrl
        apiKey = cfg.apiKey
        textModel = cfg.textModel
        imageModel = cfg.imageModel
        useSeparateImage = cfg.imageBaseUrl.isNotBlank() || cfg.imageApiKey.isNotBlank()
        imageBaseUrl = cfg.imageBaseUrl
        imageApiKey = cfg.imageApiKey
    }

    val draftConfig = AiGatewayConfig(
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        textModel = textModel.trim(),
        imageBaseUrl = if (useSeparateImage) imageBaseUrl.trim() else "",
        imageApiKey = if (useSeparateImage) imageApiKey.trim() else "",
        imageModel = imageModel.trim(),
    )

    val textSuggestions = remember(uiState.discoveredAiModels, cfg.textModel) {
        (uiState.discoveredAiModels + listOfNotNull(cfg.textModel.takeIf(String::isNotBlank))).distinct().take(8)
    }
    val imageSuggestions = remember(uiState.discoveredAiModels, cfg.imageModel) {
        val discoveredImgs = uiState.discoveredAiModels.filter {
            it.contains("image", ignoreCase = true) || it.contains("gpt-image", ignoreCase = true)
        }
        (discoveredImgs + listOfNotNull(cfg.imageModel.takeIf(String::isNotBlank))).distinct().take(6)
    }

    ModalPaperSheet(
        onDismiss = onDismiss,
        scrimMaxAlpha = 0.40f,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = BookPaper,
            border = BorderStroke(1.dp, BookOutline),
            shadowElevation = 14.dp,
            modifier = Modifier
                .padding(horizontal = 18.dp, vertical = 24.dp)
                .fillMaxWidth()
                .widthIn(max = 500.dp)
                .testTag("parent_settings_sheet")
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "家长设置", fontFamily = bookFont, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = InkPrimary)
                    Surface(
                        shape = CircleShape,
                        color = BookPaperWarm,
                        border = BorderStroke(1.dp, BookOutline),
                        modifier = Modifier.size(34.dp).clip(CircleShape).springPressScale(onClick = onDismiss),
                    ) {
                        Box(contentAlignment = Alignment.Center) { IconCloseCross(color = InkSecondary, size = 14.dp) }
                    }
                }

                ParentUpdateSection(
                    updateConfig = uiState.updateConfig,
                    updateState = uiState.updateState,
                    onCheckUpdate = onCheckUpdate,
                    onDownloadAndInstall = onDownloadAndInstall,
                    onInstallReadyApk = onInstallReadyApk,
                    onSaveUpdateConfig = onSaveUpdateConfig,
                )

                GatewayTextField(label = "接口地址", value = baseUrl, onValueChange = { baseUrl = it }, placeholder = "https://...")
                GatewaySecretField(
                    label = "API 密钥",
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    revealed = revealPrimaryKey,
                    onToggleReveal = { revealPrimaryKey = !revealPrimaryKey },
                )
                GatewayTextField(label = "文本模型", value = textModel, onValueChange = { textModel = it })
                ModelSuggestionChips(suggestions = textSuggestions, current = textModel, onSelect = { textModel = it })

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "独立生图接口", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = InkPrimary)
                    Switch(
                        checked = useSeparateImage,
                        onCheckedChange = { useSeparateImage = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = BookPaper,
                            checkedTrackColor = Vermilion,
                            uncheckedThumbColor = InkSecondary,
                            uncheckedTrackColor = BookPaperWarm,
                        ),
                    )
                }

                if (useSeparateImage) {
                    GatewayTextField(label = "生图接口地址", value = imageBaseUrl, onValueChange = { imageBaseUrl = it })
                    GatewaySecretField(
                        label = "生图 API 密钥",
                        value = imageApiKey,
                        onValueChange = { imageApiKey = it },
                        revealed = revealImageKey,
                        onToggleReveal = { revealImageKey = !revealImageKey },
                    )
                }

                GatewayTextField(label = "生图模型", value = imageModel, onValueChange = { imageModel = it })
                ModelSuggestionChips(suggestions = imageSuggestions, current = imageModel, onSelect = { imageModel = it })

                uiState.aiConnectivityMessage?.let { statusMsg ->
                    val ok = uiState.aiConnectivityOk == true
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (ok) JadeSoft else VermilionSoft,
                        border = BorderStroke(1.dp, if (ok) JadeInk.copy(alpha = 0.35f) else Vermilion.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = statusMsg,
                            color = if (ok) JadeInk else Vermilion,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }
                }

                Spacer(Modifier.height(2.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = BookPaperWarm,
                        border = BorderStroke(1.dp, BookOutline),
                        modifier = Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .testTag("parent_test_connection")
                            .springPressScale(enabled = !uiState.isTestingAiConnectivity) { onTestConnection(draftConfig) },
                    ) {
                        Box(modifier = Modifier.padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (uiState.isTestingAiConnectivity) "测试中..." else "测试连接",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = InkPrimary,
                            )
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        color = Vermilion,
                        modifier = Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .testTag("parent_save_button")
                            .springPressScale { onSave(draftConfig) },
                    ) {
                        Box(modifier = Modifier.padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
                            Text(text = "保存", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "恢复默认",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = InkSecondary,
                        modifier = Modifier.clip(CircleShape).clickable(onClick = onResetDefaults).padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    Text(
                        text = "清空",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Vermilion,
                        modifier = Modifier.clip(CircleShape).clickable(onClick = onClear).padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun gatewayFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = BookPaperWarm,
    unfocusedContainerColor = BookPaperWarm,
    focusedBorderColor = Vermilion,
    unfocusedBorderColor = BookOutline,
    focusedTextColor = InkPrimary,
    unfocusedTextColor = InkPrimary,
    cursorColor = Vermilion,
)

@Composable
private fun GatewayTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
) {
    Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = InkSecondary)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = placeholder?.let { p -> { Text(p, color = InkSubtle, fontSize = 13.sp) } },
        shape = RoundedCornerShape(12.dp),
        colors = gatewayFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GatewaySecretField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
) {
    Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = InkSecondary)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onToggleReveal),
                contentAlignment = Alignment.Center,
            ) {
                IconEyeVisibility(visible = revealed, color = InkSecondary, size = 18.dp)
            }
        },
        shape = RoundedCornerShape(12.dp),
        colors = gatewayFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelSuggestionChips(
    suggestions: List<String>,
    current: String,
    onSelect: (String) -> Unit,
) {
    if (suggestions.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        suggestions.forEach { modelId ->
            val selected = current.trim() == modelId
            Surface(
                shape = CircleShape,
                color = if (selected) VermilionSoft else BookPaperWarm,
                border = BorderStroke(1.dp, if (selected) Vermilion else BookOutline),
                modifier = Modifier.clip(CircleShape).clickable { onSelect(modelId) },
            ) {
                Text(
                    text = modelId,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) Vermilion else InkSecondary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}
