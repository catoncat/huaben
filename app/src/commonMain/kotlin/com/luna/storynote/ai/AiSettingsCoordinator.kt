package com.luna.storynote.ai

import com.luna.storynote.PlatformEnvironment
import com.luna.storynote.model.AiGatewayConfig
import com.luna.storynote.model.StudioUiState
import com.luna.storynote.storage.AiConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class AiSettingsCoordinator(
    private val env: PlatformEnvironment,
    private val aiConfigStore: AiConfigStore,
    private val aiGatewayClient: AiGatewayClient,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<StudioUiState>,
) {
    fun open() {
        uiState.update {
            it.copy(isParentSettingsOpen = true, aiConnectivityMessage = null, aiConnectivityOk = null)
        }
    }

    fun close() {
        uiState.update { it.copy(isParentSettingsOpen = false) }
    }

    fun save(config: AiGatewayConfig) {
        val normalized = config.copy(
            baseUrl = config.baseUrl.trim(),
            apiKey = config.apiKey.trim(),
            textModel = config.textModel.trim(),
            imageBaseUrl = config.imageBaseUrl.trim(),
            imageApiKey = config.imageApiKey.trim(),
            imageModel = config.imageModel.trim(),
        )
        scope.launch(Dispatchers.IO) {
            aiConfigStore.write(normalized)
            uiState.update {
                it.copy(
                    aiConfig = normalized,
                    isParentSettingsOpen = false,
                    aiConnectivityMessage = null,
                    aiConnectivityOk = null,
                )
            }
        }
    }

    fun clear() {
        scope.launch(Dispatchers.IO) {
            val empty = AiGatewayConfig()
            aiConfigStore.write(empty)
            uiState.update {
                it.copy(
                    aiConfig = empty,
                    discoveredAiModels = emptyList(),
                    aiConnectivityMessage = null,
                    aiConnectivityOk = null,
                )
            }
        }
    }

    fun resetToDefaults() {
        scope.launch(Dispatchers.IO) {
            aiConfigStore.clear()
            val defaults = env.loadDefaultAiConfig()
            uiState.update {
                it.copy(
                    aiConfig = defaults,
                    discoveredAiModels = emptyList(),
                    aiConnectivityMessage = null,
                    aiConnectivityOk = null,
                )
            }
        }
    }

    suspend fun testConnection(config: AiGatewayConfig): AiConnectionTestResult =
        withContext(Dispatchers.IO) { aiGatewayClient.testConnection(config) }

    fun testConnectivity(config: AiGatewayConfig) {
        uiState.update {
            it.copy(isTestingAiConnectivity = true, aiConnectivityMessage = null, aiConnectivityOk = null)
        }
        scope.launch {
            val res = testConnection(config)
            uiState.update { st ->
                st.copy(
                    isTestingAiConnectivity = false,
                    aiConnectivityMessage = res.message,
                    aiConnectivityOk = res.ok,
                    discoveredAiModels = if (res.ok && res.models.isNotEmpty()) res.models else st.discoveredAiModels,
                )
            }
        }
    }
}
