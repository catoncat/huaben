package com.luna.storynote

import com.luna.storynote.model.AiGatewayConfig
import com.luna.storynote.model.StoryBook
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import kotlin.io.path.createTempDirectory

class ParentSettingsAndControllerConfigTest {
    private class StubAndroidLikeEnv(
        override val storageRoot: File,
        override val privateConfigDir: File,
    ) : PlatformEnvironment {
        override val authCacheFile: File = File(privateConfigDir, "doubao_auth.json")
        override fun createPcmMic(): PlatformPcmMic = throw UnsupportedOperationException()
        override fun createAudioPlayer(): PlatformAudioPlayer = object : PlatformAudioPlayer {
            override fun playRange(wavFile: File, startMs: Long, endMs: Long, onCompleted: () -> Unit) = Unit
            override fun stop() = Unit
            override val isPlaying: Boolean = false
        }
        override fun speakStandardWord(word: String) = Unit
        override fun stopSpeaking() = Unit
        override fun loadDefaultAiConfig(): AiGatewayConfig = AiGatewayConfig()
        override fun openStrokeAssetStream(): InputStream? =
            DesktopPlatformEnvironment::class.java.getResourceAsStream("/strokes.bin.gz")
        override fun setKeepScreenAwake(keepAwake: Boolean) = Unit
        override fun exportBook(book: StoryBook) = Unit
    }

    private fun awaitUntil(timeoutMs: Long = 3000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(20L)
        }
        assertTrue("Condition timed out", cond())
    }

    @Test
    fun desktopPrefillRespectsDefaultProviderAndClearVsResetSemantics() {
        val tmp = createTempDirectory("parent-cfg-desktop-").toFile()
        try {
            val piDir = File(tmp, "pi-agent").apply { mkdirs() }
            File(piDir, "models.json").writeText(
                JSONObject().put(
                    "providers",
                    JSONObject().put(
                        "magpie-railway",
                        JSONObject()
                            .put("baseUrl", "https://example.invalid")
                            .put("apiKey", "zhh--test-dummy-pi-001"),
                    ),
                ).toString(),
            )
            // When defaultProvider is another provider, its defaultModel must NOT override magpie-railway's model
            File(piDir, "settings.json").writeText(
                JSONObject()
                    .put("defaultProvider", "other-provider")
                    .put("defaultModel", "other/model-xyz")
                    .toString(),
            )
            val cfgDir = File(tmp, "config").apply { mkdirs() }
            val env = DesktopPlatformEnvironment(
                storageRoot = File(tmp, "storage").apply { mkdirs() },
                authCacheFile = File(cfgDir, "doubao_auth.json"),
                privateConfigDir = cfgDir,
                piAgentDir = piDir,
            )

            val controller = BookStudioController(env)
            try {
                awaitUntil { controller.isAiConfigured }
                assertEquals("auto/deepseek-v4.1-flash", controller.uiState.value.aiConfig.textModel)
                assertEquals("codex/gpt-image-2", controller.uiState.value.aiConfig.imageModel)

                // Clear persists empty config so next load does not snap back to pi defaults
                controller.clearAiConfig()
                awaitUntil { !controller.isAiConfigured && controller.aiConfigStore.settingsFile.isFile }
                assertFalse(controller.aiConfigStore.loadOrDefault(env).isConfigured)

                // Reset removes persisted override and restores runtime prefill defaults
                controller.resetAiConfigToDefaults()
                awaitUntil { controller.isAiConfigured }
                assertEquals("https://example.invalid", controller.uiState.value.aiConfig.baseUrl)
            } finally {
                controller.dispose()
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun controllerSavesTestsAndPersistsZeroKeyAndroidFlow() {
        val tmp = createTempDirectory("parent-cfg-android-").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/models") { ex ->
            val resp = JSONObject()
                .put("data", JSONArray().put(JSONObject().put("id", "auto/deepseek-v4.1-flash")))
                .toString()
                .toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.start()
        try {
            val env = StubAndroidLikeEnv(
                storageRoot = File(tmp, "storage").apply { mkdirs() },
                privateConfigDir = File(tmp, "config").apply { mkdirs() },
            )
            val controller = BookStudioController(env)
            try {
                Thread.sleep(100L)
                assertFalse(controller.isAiConfigured)

                controller.openParentSettings()
                assertTrue(controller.uiState.value.isParentSettingsOpen)

                val cfg = AiGatewayConfig(
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    apiKey = "sk-test-dummy-android-01",
                    textModel = "auto/deepseek-v4.1-flash",
                )
                val testRes = runBlocking(Dispatchers.IO) { controller.testAiConnection(cfg) }
                assertTrue(testRes.ok)
                assertEquals(listOf("auto/deepseek-v4.1-flash"), testRes.models)

                controller.saveAiConfig(cfg)
                awaitUntil { controller.isAiConfigured && !controller.uiState.value.isParentSettingsOpen }

                val reloaded = BookStudioController(env)
                try {
                    awaitUntil { reloaded.isAiConfigured }
                    assertEquals(cfg.baseUrl, reloaded.uiState.value.aiConfig.baseUrl)
                    assertEquals(cfg.apiKey, reloaded.uiState.value.aiConfig.apiKey)
                } finally {
                    reloaded.dispose()
                }
            } finally {
                controller.dispose()
            }
        } finally {
            server.stop(0)
            tmp.deleteRecursively()
        }
    }
}
