package com.luna.storynote.storage

import com.luna.storynote.PlatformEnvironment
import com.luna.storynote.model.AiGatewayConfig
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class AiConfigStore(private val configDir: File) {
    private val lock = Any()
    val settingsFile: File = File(configDir, "ai_settings.json")

    fun readSaved(): AiGatewayConfig? = synchronized(lock) {
        if (!settingsFile.isFile) return null
        return try {
            BookJsonCodec.decodeAiConfig(JSONObject(settingsFile.readText(Charsets.UTF_8)))
        } catch (_: Exception) {
            null
        }
    }

    fun write(config: AiGatewayConfig) = synchronized(lock) {
        configDir.mkdirs()
        val normalized = config.copy(
            baseUrl = config.baseUrl.trim(),
            apiKey = config.apiKey.trim(),
            textModel = config.textModel.trim(),
            imageBaseUrl = config.imageBaseUrl.trim(),
            imageApiKey = config.imageApiKey.trim(),
            imageModel = config.imageModel.trim(),
        )
        BookJsonCodec.atomicWriteText(
            settingsFile,
            BookJsonCodec.encodeAiConfig(normalized).toString(2) + "\n",
        )
        applyOwnerOnlyPermissions(settingsFile)
    }

    fun loadOrDefault(env: PlatformEnvironment): AiGatewayConfig = synchronized(lock) {
        val saved = readSaved()
        return saved ?: env.loadDefaultAiConfig()
    }

    fun clear() = synchronized(lock) {
        if (settingsFile.isFile) {
            settingsFile.delete()
        }
    }

    private fun applyOwnerOnlyPermissions(file: File) {
        try {
            val perms = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            Files.setPosixFilePermissions(file.toPath(), perms)
        } catch (_: Exception) {
            try {
                file.setReadable(false, false)
                file.setReadable(true, true)
                file.setWritable(false, false)
                file.setWritable(true, true)
            } catch (_: Exception) {
            }
        }
    }
}
