package com.luna.storynote.update

import com.luna.storynote.storage.BookJsonCodec
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit

object AppBuildInfo {
    const val VERSION_NAME = "1.2.0"
    const val VERSION_CODE = 6
    const val DEFAULT_MANIFEST_URL = "https://cnb.cool/lzb-prod/story-note-releases/-/git/raw/main/latest.json"
    const val DEFAULT_GITHUB_REPO = "catoncat/story-note"
}

data class AppUpdateConfig(
    val autoCheckEnabled: Boolean = true,
    val githubRepo: String = AppBuildInfo.DEFAULT_GITHUB_REPO,
    val customManifestUrl: String = AppBuildInfo.DEFAULT_MANIFEST_URL,
    val githubToken: String = "",
)

data class AvailableAppUpdate(
    val versionName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val assetApiUrl: String = "",
    val releasePageUrl: String = "",
    val fileSizeBytes: Long = 0L,
    val requiresGithubAuth: Boolean = false,
)

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data class UpToDate(val currentVersion: String) : AppUpdateState
    data class Available(val update: AvailableAppUpdate) : AppUpdateState
    data class Downloading(val update: AvailableAppUpdate, val progress: Float) : AppUpdateState
    data class ReadyToInstall(val update: AvailableAppUpdate, val apkFile: File) : AppUpdateState
    data class Error(val message: String) : AppUpdateState
}

class UpdateConfigStore(private val configDir: File) {
    private val lock = Any()
    private val file = File(configDir, "update_settings.json")

    fun load(): AppUpdateConfig = synchronized(lock) {
        if (!file.isFile) return AppUpdateConfig()
        return try {
            val o = JSONObject(file.readText(Charsets.UTF_8))
            val ghToken = o.optString("github_token", "").trim()
            val rawManifest = if (o.has("custom_manifest_url")) {
                o.optString("custom_manifest_url", "").trim()
            } else {
                AppBuildInfo.DEFAULT_MANIFEST_URL
            }
            AppUpdateConfig(
                autoCheckEnabled = o.optBoolean("auto_check_enabled", true),
                githubRepo = o.optString("github_repo", AppBuildInfo.DEFAULT_GITHUB_REPO).trim()
                    .ifEmpty { AppBuildInfo.DEFAULT_GITHUB_REPO },
                customManifestUrl = if (rawManifest.isEmpty() && ghToken.isEmpty() && !o.optBoolean("use_github_fallback", false)) {
                    AppBuildInfo.DEFAULT_MANIFEST_URL
                } else {
                    rawManifest
                },
                githubToken = ghToken,
            )
        } catch (_: Exception) {
            AppUpdateConfig()
        }
    }

    fun save(config: AppUpdateConfig) = synchronized(lock) {
        configDir.mkdirs()
        val obj = JSONObject()
            .put("auto_check_enabled", config.autoCheckEnabled)
            .put("github_repo", config.githubRepo.trim().ifEmpty { AppBuildInfo.DEFAULT_GITHUB_REPO })
            .put("custom_manifest_url", config.customManifestUrl.trim())
            .put("use_github_fallback", config.customManifestUrl.trim().isEmpty())
            .put("github_token", config.githubToken.trim())
        BookJsonCodec.atomicWriteText(file, obj.toString(2) + "\n")
        applyOwnerOnly(file)
    }

    private fun applyOwnerOnly(f: File) {
        try {
            Files.setPosixFilePermissions(
                f.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (_: Exception) {
            try {
                f.setReadable(false, false)
                f.setReadable(true, true)
                f.setWritable(false, false)
                f.setWritable(true, true)
            } catch (_: Exception) {
            }
        }
    }
}

sealed interface UpdateCheckOutcome {
    data class UpdateFound(val update: AvailableAppUpdate) : UpdateCheckOutcome
    data class UpToDate(val latestVersion: String) : UpdateCheckOutcome
    data class Failed(val message: String) : UpdateCheckOutcome
}

class AppUpdateManager(
    private val httpClient: OkHttpClient = defaultUpdateHttpClient(),
) {
    fun checkForUpdate(
        config: AppUpdateConfig,
        currentVersion: String = AppBuildInfo.VERSION_NAME,
    ): UpdateCheckOutcome {
        val customUrl = config.customManifestUrl.trim()
        return if (customUrl.isNotEmpty()) {
            checkCustomManifest(customUrl, currentVersion)
        } else {
            checkGitHubRelease(config, currentVersion)
        }
    }

    private fun checkCustomManifest(url: String, currentVersion: String): UpdateCheckOutcome {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "StoryNote/$currentVersion")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return UpdateCheckOutcome.Failed("检查更新失败 (HTTP ${resp.code})")
                }
                val body = resp.body?.string().orEmpty()
                val obj = JSONObject(body)
                val latestVer = normalizeVersion(obj.optString("version", obj.optString("tag_name", "")))
                val dlUrl = obj.optString("download_url", obj.optString("apk_url", "")).trim()
                if (latestVer.isEmpty() || dlUrl.isEmpty()) {
                    return UpdateCheckOutcome.Failed("更新清单格式不完整")
                }
                if (compareSemver(latestVer, currentVersion) > 0) {
                    UpdateCheckOutcome.UpdateFound(
                        AvailableAppUpdate(
                            versionName = latestVer,
                            releaseNotes = obj.optString("notes", obj.optString("body", "")).trim(),
                            downloadUrl = dlUrl,
                            releasePageUrl = obj.optString("release_url", dlUrl).trim(),
                            fileSizeBytes = obj.optLong("size_bytes", 0L),
                            requiresGithubAuth = false,
                        ),
                    )
                } else {
                    UpdateCheckOutcome.UpToDate(latestVer)
                }
            }
        } catch (e: Exception) {
            UpdateCheckOutcome.Failed("无法连接更新源：${e.message ?: "网络异常"}")
        }
    }

    private fun checkGitHubRelease(config: AppUpdateConfig, currentVersion: String): UpdateCheckOutcome {
        val repo = config.githubRepo.trim().ifEmpty { AppBuildInfo.DEFAULT_GITHUB_REPO }
        val apiUrl = "https://api.github.com/repos/$repo/releases/latest"
        return try {
            val builder = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "StoryNote/$currentVersion")
            val token = config.githubToken.trim()
            if (token.isNotEmpty()) {
                builder.header("Authorization", "Bearer $token")
            }
            httpClient.newCall(builder.build()).execute().use { resp ->
                if (resp.code == 404) {
                    val hint = if (token.isEmpty()) {
                        "未读取到公开版本（私有仓库请填写 GitHub Token 或自定义更新源）"
                    } else {
                        "仓库暂无 Release 或 Token 无权限"
                    }
                    return UpdateCheckOutcome.Failed(hint)
                }
                if (resp.code == 401 || resp.code == 403) {
                    return UpdateCheckOutcome.Failed("GitHub 认证失败或频率受限 (HTTP ${resp.code})")
                }
                if (!resp.isSuccessful) {
                    return UpdateCheckOutcome.Failed("检查更新失败 (HTTP ${resp.code})")
                }
                val obj = JSONObject(resp.body?.string().orEmpty())
                val tag = normalizeVersion(obj.optString("tag_name", ""))
                if (tag.isEmpty()) return UpdateCheckOutcome.Failed("未找到版本号")
                if (compareSemver(tag, currentVersion) <= 0) {
                    return UpdateCheckOutcome.UpToDate(tag)
                }

                val assets = obj.optJSONArray("assets")
                var apkBrowserUrl = ""
                var apkApiUrl = ""
                var apkSize = 0L
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        val name = a.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkBrowserUrl = a.optString("browser_download_url", "").trim()
                            apkApiUrl = a.optString("url", "").trim()
                            apkSize = a.optLong("size", 0L)
                            break
                        }
                    }
                }
                val htmlUrl = obj.optString("html_url", "https://github.com/$repo/releases").trim()
                val dlUrl = apkBrowserUrl.ifEmpty { apkApiUrl }.ifEmpty { htmlUrl }
                UpdateCheckOutcome.UpdateFound(
                    AvailableAppUpdate(
                        versionName = tag,
                        releaseNotes = obj.optString("body", "").trim(),
                        downloadUrl = dlUrl,
                        assetApiUrl = apkApiUrl,
                        releasePageUrl = htmlUrl,
                        fileSizeBytes = apkSize,
                        requiresGithubAuth = token.isNotEmpty() && apkApiUrl.isNotEmpty(),
                    ),
                )
            }
        } catch (e: Exception) {
            UpdateCheckOutcome.Failed("检查更新失败：${e.message ?: "网络异常"}")
        }
    }

    fun downloadApk(
        update: AvailableAppUpdate,
        config: AppUpdateConfig,
        updatesDir: File,
        onProgress: (Float) -> Unit,
    ): File {
        updatesDir.mkdirs()
        val safeVer = update.versionName.replace(Regex("[^0-9A-Za-z._-]"), "_")
        val finalApk = File(updatesDir, "StoryNote-v$safeVer.apk")
        val partFile = File(updatesDir, ".StoryNote-v$safeVer.apk.part")
        if (partFile.exists()) partFile.delete()

        val token = config.githubToken.trim()
        val useApiAsset = update.requiresGithubAuth && token.isNotEmpty() && update.assetApiUrl.isNotBlank()
        val targetUrl = if (useApiAsset) update.assetApiUrl else update.downloadUrl

        val reqBuilder = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", "StoryNote/${AppBuildInfo.VERSION_NAME}")
        if (useApiAsset) {
            reqBuilder.header("Accept", "application/octet-stream")
            reqBuilder.header("Authorization", "Bearer $token")
        }

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("下载安装包失败 (HTTP ${resp.code})")
            }
            val body = resp.body ?: throw IllegalStateException("安装包内容为空")
            val total = body.contentLength().takeIf { it > 0L } ?: update.fileSizeBytes
            var downloaded = 0L
            var lastReported = -1f
            body.byteStream().use { input ->
                FileOutputStream(partFile).use { fos ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                        downloaded += n
                        if (total > 0L) {
                            val pct = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            if (pct - lastReported >= 0.02f || pct >= 1f) {
                                lastReported = pct
                                onProgress(pct)
                            }
                        }
                    }
                    fos.fd.sync()
                }
            }
        }

        if (!isValidApkArchive(partFile)) {
            partFile.delete()
            throw IllegalStateException("下载的文件不是有效的安卓安装包")
        }
        if (!partFile.renameTo(finalApk)) {
            partFile.copyTo(finalApk, overwrite = true)
            partFile.delete()
        }
        onProgress(1f)
        return finalApk
    }

    companion object {
        fun normalizeVersion(raw: String): String =
            raw.trim().removePrefix("v").removePrefix("V").trim()

        fun compareSemver(a: String, b: String): Int {
            val pa = normalizeVersion(a).substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val pb = normalizeVersion(b).substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(pa.size, pb.size)
            for (i in 0 until maxLen) {
                val va = pa.getOrElse(i) { 0 }
                val vb = pb.getOrElse(i) { 0 }
                if (va != vb) return va.compareTo(vb)
            }
            return 0
        }

        private fun isValidApkArchive(file: File): Boolean {
            if (!file.isFile || file.length() < 128 * 1024L) return false
            val header = ByteArray(4)
            file.inputStream().use { if (it.read(header) < 4) return false }
            return header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
        }

        fun defaultUpdateHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
