package com.luna.storynote

import com.luna.storynote.model.AiGatewayConfig
import com.luna.storynote.storage.AiConfigStore
import com.luna.storynote.storage.BookBibleStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory

class BookBibleAndAiConfigStoreTest {
    @Test
    fun bookBibleStorePersistsAndMergesDistinctTraits() {
        val tmp = createTempDirectory("bible-store-test-").toFile()
        try {
            val store = BookBibleStore(tmp)
            val initial = store.read("book_1")
            assertTrue(initial.protagonistTraits.isEmpty())

            store.mergeUpdate(
                bookId = "book_1",
                addTraits = listOf("蓝色小恐龙", "戴红围巾"),
                addScenesAndProps = listOf("棉花糖火山"),
                plotSummary = "小恐龙出发去探险。",
            )
            val updated = store.mergeUpdate(
                bookId = "book_1",
                addTraits = listOf("蓝色小恐龙", "大眼睛"),
                addScenesAndProps = listOf("星星地图"),
            )
            assertEquals(listOf("蓝色小恐龙", "戴红围巾", "大眼睛"), updated.protagonistTraits)
            assertEquals(listOf("棉花糖火山", "星星地图"), updated.keyScenesAndProps)
            assertEquals("小恐龙出发去探险。", updated.plotSummary)
            assertEquals(updated.protagonistTraits, store.read("book_1").protagonistTraits)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun aiConfigStoreRedactsSecretsAndDesktopLoadsMockPiAgentDir() {
        val tmp = createTempDirectory("ai-config-test-").toFile()
        try {
            val piDir = File(tmp, "pi-agent").apply { mkdirs() }
            File(piDir, "models.json").writeText(
                """
                {
                  "providers": {
                    "magpie-railway": {
                      "baseUrl": "https://example.invalid",
                      "apiKey": "sk-test-dummy-token-0001",
                      "models": [{"id": "auto/deepseek-v4.1-flash"}]
                    }
                  }
                }
                """.trimIndent(),
                Charsets.UTF_8,
            )
            File(piDir, "settings.json").writeText(
                """
                {
                  "defaultProvider": "magpie-railway",
                  "defaultModel": "auto/deepseek-v4.1-flash"
                }
                """.trimIndent(),
                Charsets.UTF_8,
            )

            val cfgDir = File(tmp, "config").apply { mkdirs() }
            val env = DesktopPlatformEnvironment(
                storageRoot = File(tmp, "storage").apply { mkdirs() },
                authCacheFile = File(cfgDir, "doubao_auth.json"),
                privateConfigDir = cfgDir,
                piAgentDir = piDir,
            )

            val store = AiConfigStore(cfgDir)
            val prefilled = store.loadOrDefault(env)
            assertTrue(prefilled.isConfigured)
            assertEquals("https://example.invalid", prefilled.baseUrl)
            assertEquals("auto/deepseek-v4.1-flash", prefilled.textModel)
            assertEquals("codex/gpt-image-2", prefilled.imageModel)
            assertFalse(prefilled.redactedSummary().contains("sk-test-dummy-token-0001"))

            val custom = AiGatewayConfig(
                baseUrl = "https://custom.invalid",
                apiKey = "sk-test-dummy-custom-99",
                textModel = "custom-model",
                imageModel = "custom-img",
            )
            store.write(custom)
            val configFile = File(cfgDir, "ai_settings.json")
            assertTrue(configFile.isFile)
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(configFile.toPath()),
            )
            val reloaded = store.loadOrDefault(env)
            assertEquals(custom, reloaded)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
