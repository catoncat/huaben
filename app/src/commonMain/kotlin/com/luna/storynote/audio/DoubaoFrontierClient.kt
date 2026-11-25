package com.luna.storynote.audio

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

class AsrException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class DoubaoCredentials(
    val deviceId: String,
    val cdid: String,
    val appKey: String,
    val updatedAtMs: Long,
) {
    val wsUrl: String
        get() = "$FRONTIER_WS_BASE_URL?aid=$ANDROID_AID&device_id=${URLEncoder.encode(deviceId, "UTF-8")}"

    companion object {
        const val ANDROID_REGISTER_URL = "https://log.snssdk.com/service/2/device_register/"
        const val ANDROID_SETTINGS_URL = "https://is.snssdk.com/service/settings/v3/"
        const val FRONTIER_WS_BASE_URL = "wss://frontier-audio-ime-ws.doubao.com/ocean/api/v1/ws"
        const val ANDROID_AID = "401734"
        const val ANDROID_APP_NAME = "oime"
        const val ANDROID_VERSION_CODE = "100102018"
        const val ANDROID_VERSION_NAME = "1.1.2"
        const val ANDROID_CHANNEL = "official"
        const val ANDROID_PACKAGE = "com.bytedance.android.doubaoime"
        const val ANDROID_USER_AGENT =
            "com.bytedance.android.doubaoime/100102018 (Linux; U; Android 16; en_US; Pixel 7 Pro; " +
                "Build/BP2A.250605.031.A2; Cronet/TTNetVersion:94cf429a 2025-11-17 QuicVersion:1f89f732 2025-05-08)"
    }
}

data class FrontierParsedFrame(
    val event: String,
    val statusCode: Long,
    val payloadJson: JSONObject?,
)

object FrontierProtocol {
    const val SAMPLE_RATE = 16000
    const val PCM_FRAME_BYTES = 640 // 20ms @ 16kHz mono S16_LE

    fun encodeVarint(value: Long): ByteArray {
        var v = value
        val out = ByteArrayOutputStream(10)
        while ((v and -0x80L) != 0L) {
            out.write((v.toInt() and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v.toInt() and 0x7F)
        return out.toByteArray()
    }

    private fun writeFieldBytes(out: ByteArrayOutputStream, fieldNum: Int, data: ByteArray) {
        if (data.isEmpty()) return
        out.write(encodeVarint(((fieldNum shl 3) or 2).toLong()))
        out.write(encodeVarint(data.size.toLong()))
        out.write(data)
    }

    fun buildPacket(
        token: String,
        method: String,
        payload: String,
        audio: ByteArray?,
        requestId: String,
    ): ByteArray {
        val out = ByteArrayOutputStream(128 + (audio?.size ?: 0))
        if (token.isNotEmpty()) writeFieldBytes(out, 2, token.toByteArray(Charsets.UTF_8))
        writeFieldBytes(out, 3, "ASR".toByteArray(Charsets.UTF_8))
        writeFieldBytes(out, 5, method.toByteArray(Charsets.UTF_8))
        if (payload.isNotEmpty()) writeFieldBytes(out, 6, payload.toByteArray(Charsets.UTF_8))
        if (audio != null && audio.isNotEmpty()) writeFieldBytes(out, 7, audio)
        if (requestId.isNotEmpty()) writeFieldBytes(out, 8, requestId.toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    fun buildSessionConfig(sampleRate: Int = SAMPLE_RATE): String {
        val audioInfo = JSONObject()
            .put("channel", 1)
            .put("format", "pcm")
            .put("sample_rate", sampleRate)
        val extra = JSONObject()
            .put("app_name", "com.android.chrome")
            .put("cell_compress_rate", 8)
            .put("enable_asr_threepass", true)
            .put("enable_asr_twopass", true)
            .put("input_mode", "tool")
        return JSONObject()
            .put("audio_info", audioInfo)
            .put("enable_punctuation", true)
            .put("enable_speech_rejection", false)
            .put("extra", extra)
            .toString()
    }

    private fun readVarint(data: ByteArray, startOffset: Int): Pair<Long, Int>? {
        var offset = startOffset
        var result = 0L
        var shift = 0
        while (offset < data.size) {
            val b = data[offset].toInt() and 0xFF
            offset++
            result = result or ((b.toLong() and 0x7FL) shl shift)
            if ((b and 0x80) == 0) return Pair(result, offset)
            shift += 7
            if (shift >= 64) return null
        }
        return null
    }

    fun parseResponse(data: ByteArray): FrontierParsedFrame? {
        var offset = 0
        var event = ""
        var statusCode = 20_000_000L
        var payloadObj: JSONObject? = null

        while (offset < data.size) {
            val tagPair = readVarint(data, offset) ?: return null
            val tag = tagPair.first.toInt()
            offset = tagPair.second
            val fieldNum = tag ushr 3
            when (tag and 0x7) {
                0 -> {
                    val vPair = readVarint(data, offset) ?: return null
                    offset = vPair.second
                    if (fieldNum == 5) statusCode = vPair.first
                }
                2 -> {
                    val lenPair = readVarint(data, offset) ?: return null
                    val chunkLen = lenPair.first.toInt()
                    offset = lenPair.second
                    if (chunkLen < 0 || offset + chunkLen > data.size) return null
                    when (fieldNum) {
                        4 -> event = String(data, offset, chunkLen, Charsets.UTF_8)
                        7 -> payloadObj = try {
                            JSONObject(String(data, offset, chunkLen, Charsets.UTF_8))
                        } catch (_: Exception) {
                            null
                        }
                    }
                    offset += chunkLen
                }
                1 -> {
                    if (offset + 8 > data.size) return null
                    offset += 8
                }
                5 -> {
                    if (offset + 4 > data.size) return null
                    offset += 4
                }
                else -> return null
            }
        }
        return FrontierParsedFrame(event = event, statusCode = statusCode, payloadJson = payloadObj)
    }
}

class DoubaoAuthRepository(
    private val cacheFile: File,
    private val httpClient: OkHttpClient,
) {
    private val lock = Any()
    private val cacheTtlMs = 6L * 3600L * 1000L

    fun resolve(forceRefresh: Boolean = false): DoubaoCredentials = synchronized(lock) {
        val now = System.currentTimeMillis()
        val cached = readCached()
        if (!forceRefresh && cached != null && (now - cached.updatedAtMs) < cacheTtlMs) {
            return cached
        }

        var did = cached?.deviceId.orEmpty()
        var cdid = cached?.cdid.orEmpty()
        if (forceRefresh || !isValidDeviceId(did) || cdid.isBlank()) {
            val reg = registerVirtualDevice(now)
            did = reg.first
            cdid = reg.second
        }

        val appKey = fetchAppKey(did, cdid, System.currentTimeMillis())
        val fresh = DoubaoCredentials(did, cdid, appKey, System.currentTimeMillis())
        writeCached(fresh)
        return fresh
    }

    private fun isValidDeviceId(value: String): Boolean =
        value.length in 4..32 && value.all { it in '0'..'9' }

    private fun readCached(): DoubaoCredentials? {
        if (!cacheFile.isFile) return null
        return try {
            val obj = JSONObject(cacheFile.readText(Charsets.UTF_8))
            val did = obj.optString("device_id", "").trim()
            val cdid = obj.optString("cdid", "").trim()
            val appKey = obj.optString("app_key", "").trim()
            val updated = obj.optLong("updated_at_ms", 0L)
            if (isValidDeviceId(did) && cdid.isNotEmpty() && appKey.isNotEmpty()) {
                DoubaoCredentials(did, cdid, appKey, updated)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeCached(creds: DoubaoCredentials) {
        try {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.parentFile, ".${cacheFile.name}.tmp")
            val obj = JSONObject()
                .put("version", 1)
                .put("device_id", creds.deviceId)
                .put("cdid", creds.cdid)
                .put("app_key", creds.appKey)
                .put("updated_at_ms", creds.updatedAtMs)
            tmp.writeText(obj.toString(2), Charsets.UTF_8)
            tmp.renameTo(cacheFile)
        } catch (_: Exception) {
        }
    }

    private fun registerVirtualDevice(nowMs: Long): Pair<String, String> {
        val cdid = UUID.randomUUID().toString()
        val openudid = UUID.randomUUID().toString().replace("-", "").take(16)
        val clientudid = UUID.randomUUID().toString()
        val query = buildQueryString(
            listOf(
                "device_platform" to "android", "os" to "android", "ssmix" to "a",
                "_rticket" to nowMs.toString(), "cdid" to cdid,
                "channel" to DoubaoCredentials.ANDROID_CHANNEL, "aid" to DoubaoCredentials.ANDROID_AID,
                "app_name" to DoubaoCredentials.ANDROID_APP_NAME,
                "version_code" to DoubaoCredentials.ANDROID_VERSION_CODE,
                "version_name" to DoubaoCredentials.ANDROID_VERSION_NAME,
                "manifest_version_code" to DoubaoCredentials.ANDROID_VERSION_CODE,
                "update_version_code" to DoubaoCredentials.ANDROID_VERSION_CODE,
                "resolution" to "1080*2400", "dpi" to "420", "device_type" to "Pixel 7 Pro",
                "device_brand" to "google", "language" to "zh", "os_api" to "34",
                "os_version" to "16", "ac" to "wifi",
            ),
        )
        val header = JSONObject()
            .put("device_id", 0).put("install_id", 0)
            .put("aid", DoubaoCredentials.ANDROID_AID.toInt())
            .put("app_name", DoubaoCredentials.ANDROID_APP_NAME)
            .put("version_code", DoubaoCredentials.ANDROID_VERSION_CODE.toInt())
            .put("version_name", DoubaoCredentials.ANDROID_VERSION_NAME)
            .put("manifest_version_code", DoubaoCredentials.ANDROID_VERSION_CODE.toInt())
            .put("update_version_code", DoubaoCredentials.ANDROID_VERSION_CODE.toInt())
            .put("channel", DoubaoCredentials.ANDROID_CHANNEL)
            .put("package", DoubaoCredentials.ANDROID_PACKAGE)
            .put("device_platform", "android").put("os", "android")
            .put("os_api", "34").put("os_version", "16")
            .put("device_type", "Pixel 7 Pro").put("device_brand", "google")
            .put("device_model", "Pixel 7 Pro").put("resolution", "1080*2400")
            .put("dpi", "420").put("language", "zh").put("timezone", 8)
            .put("access", "wifi").put("rom", "UP1A.231005.007").put("rom_version", "UP1A.231005.007")
            .put("openudid", openudid).put("clientudid", clientudid).put("cdid", cdid)
            .put("region", "CN").put("tz_name", "Asia/Shanghai").put("tz_offset", 28800)
            .put("sim_region", "cn").put("carrier_region", "cn").put("cpu_abi", "arm64-v8a")

        val bodyJson = JSONObject()
            .put("magic_tag", "ss_app_log").put("header", header).put("_gen_time", nowMs).toString()

        val req = Request.Builder()
            .url("${DoubaoCredentials.ANDROID_REGISTER_URL}?$query")
            .header("User-Agent", DoubaoCredentials.ANDROID_USER_AGENT)
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw AsrException("豆包设备注册失败 (HTTP ${resp.code})")
            val parsed = JSONObject(resp.body?.string().orEmpty())
            val did = parsed.optString("device_id_str", "").ifBlank {
                parsed.opt("device_id")?.toString().orEmpty()
            }.trim()
            if (!isValidDeviceId(did)) throw AsrException("豆包设备注册未返回有效 device_id")
            return Pair(did, cdid)
        }
    }

    private fun fetchAppKey(deviceId: String, cdid: String, nowMs: Long): String {
        val query = buildQueryString(
            listOf(
                "device_platform" to "android", "os" to "android", "ssmix" to "a",
                "_rticket" to nowMs.toString(), "cdid" to cdid,
                "channel" to DoubaoCredentials.ANDROID_CHANNEL, "aid" to DoubaoCredentials.ANDROID_AID,
                "app_name" to DoubaoCredentials.ANDROID_APP_NAME,
                "version_code" to DoubaoCredentials.ANDROID_VERSION_CODE,
                "version_name" to DoubaoCredentials.ANDROID_VERSION_NAME,
                "device_id" to deviceId,
            ),
        )
        val bodyBytes = "body=null".toByteArray(Charsets.UTF_8)
        val stub = MessageDigest.getInstance("MD5").digest(bodyBytes).joinToString("") { "%02X".format(it) }

        val req = Request.Builder()
            .url("${DoubaoCredentials.ANDROID_SETTINGS_URL}?$query")
            .header("User-Agent", DoubaoCredentials.ANDROID_USER_AGENT)
            .header("x-ss-stub", stub)
            .post(bodyBytes.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw AsrException("豆包配置获取失败 (HTTP ${resp.code})")
            val parsed = JSONObject(resp.body?.string().orEmpty())
            val appKey = parsed.optJSONObject("data")?.optJSONObject("settings")
                ?.optJSONObject("asr_config")?.optString("app_key", "").orEmpty().trim()
            if (appKey.isEmpty()) throw AsrException("豆包配置缺少 app_key")
            return appKey
        }
    }

    private fun buildQueryString(params: List<Pair<String, String>>): String =
        params.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }

    companion object {
        fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .writeTimeout(12, TimeUnit.SECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .build()
    }
}
