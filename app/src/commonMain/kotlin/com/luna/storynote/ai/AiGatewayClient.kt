package com.luna.storynote.ai

import com.luna.storynote.model.AiGatewayConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.Base64
import java.util.concurrent.TimeUnit

data class AiConnectionTestResult(
    val ok: Boolean,
    val models: List<String> = emptyList(),
    val message: String,
    val latencyMs: Long = 0L,
)

data class ToolCallRequest(
    val id: String,
    val name: String,
    val arguments: JSONObject,
    val rawArguments: String,
)

data class ChatMessage(
    val role: String,
    val content: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCallRequest> = emptyList(),
)

data class ChatToolSpec(
    val name: String,
    val description: String,
    val parametersSchema: JSONObject,
)

data class ChatCompletionResult(
    val content: String?,
    val toolCalls: List<ToolCallRequest>,
    val finishReason: String,
)

class AiGatewayClient @JvmOverloads constructor(
    private val httpClient: OkHttpClient = defaultTextClient(),
) {
    private val imageClient: OkHttpClient = httpClient.newBuilder()
        .readTimeout(maxOf(httpClient.readTimeoutMillis.toLong(), 120_000L), TimeUnit.MILLISECONDS)
        .writeTimeout(maxOf(httpClient.writeTimeoutMillis.toLong(), 20_000L), TimeUnit.MILLISECONDS)
        .callTimeout(150L, TimeUnit.SECONDS)
        .build()

    internal val imageReadTimeoutMillis: Long
        get() = imageClient.readTimeoutMillis.toLong()

    fun normalizeBaseUrl(raw: String): String {
        var cleaned = raw.trim().trimEnd('/')
        if (cleaned.lowercase().endsWith("/v1")) {
            cleaned = cleaned.dropLast(3).trimEnd('/')
        }
        val uri = try {
            URI(cleaned)
        } catch (e: Exception) {
            throw IOException("Invalid gateway base URL: ${redactSecrets(e.message.orEmpty())}")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw IOException("Gateway URL must use http or https")
        }
        if (uri.host.isNullOrBlank()) {
            throw IOException("Gateway URL is missing host")
        }
        return cleaned
    }

    fun listModels(config: AiGatewayConfig): List<String> =
        listModels(config.baseUrl, config.apiKey)

    fun listModels(baseUrl: String, apiKey: String): List<String> {
        val base = normalizeBaseUrl(baseUrl)
        val trimmedKey = apiKey.trim()
        val reqBuilder = Request.Builder()
            .url("$base/v1/models")
            .get()
            .header("Accept", "application/json")
        if (trimmedKey.isNotEmpty()) {
            reqBuilder.header("Authorization", "Bearer $trimmedKey")
        }
        return executeTextRequest(httpClient, reqBuilder.build(), trimmedKey) { bodyText ->
            val obj = try {
                JSONObject(bodyText)
            } catch (e: Exception) {
                throw IOException("Invalid /v1/models JSON: ${redactSecrets(e.message.orEmpty(), trimmedKey)}")
            }
            val arr = obj.optJSONArray("data") ?: JSONArray()
            val out = LinkedHashSet<String>()
            for (i in 0 until arr.length()) {
                val id = arr.optJSONObject(i)?.optString("id", "")?.trim().orEmpty()
                if (id.isNotEmpty()) out.add(id)
            }
            out.toList()
        }
    }

    fun testConnection(config: AiGatewayConfig): AiConnectionTestResult {
        val t0 = System.currentTimeMillis()
        if (config.baseUrl.isBlank()) {
            return AiConnectionTestResult(ok = false, message = "接口地址未填写", latencyMs = 0L)
        }
        if (config.apiKey.isBlank()) {
            return AiConnectionTestResult(ok = false, message = "API 密钥未填写", latencyMs = 0L)
        }
        return try {
            val models = listModels(config.baseUrl, config.apiKey)
            val latency = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
            AiConnectionTestResult(
                ok = true,
                models = models,
                message = "连接成功 · ${models.size} 个模型 (${latency}ms)",
                latencyMs = latency,
            )
        } catch (e: Exception) {
            val latency = (System.currentTimeMillis() - t0).coerceAtLeast(0L)
            val cleanErr = redactSecrets(e.message ?: "连接失败", config.apiKey, config.imageApiKey)
            AiConnectionTestResult(
                ok = false,
                models = emptyList(),
                message = cleanErr,
                latencyMs = latency,
            )
        }
    }

    fun testConnectivity(config: AiGatewayConfig): AiConnectionTestResult = testConnection(config)

    fun chatWithTools(
        config: AiGatewayConfig,
        messages: List<ChatMessage>,
        tools: List<ChatToolSpec> = emptyList(),
        temperature: Double = 0.2,
        maxTokens: Int? = null,
        toolChoice: String = "auto",
    ): ChatCompletionResult {
        val base = normalizeBaseUrl(config.baseUrl)
        val apiKey = config.apiKey.trim()
        if (apiKey.isEmpty()) throw IOException("API key is required")
        val model = config.textModel.trim()
        if (model.isEmpty()) throw IOException("Text model is required")

        val payload = JSONObject()
            .put("model", model)
            .put("temperature", temperature)
            .put("messages", encodeMessages(messages))
        if (maxTokens != null && maxTokens > 0) {
            payload.put("max_tokens", maxTokens)
        }
        if (tools.isNotEmpty()) {
            val toolsArr = JSONArray()
            for (t in tools) {
                val fn = JSONObject()
                    .put("name", t.name)
                    .put("description", t.description)
                    .put("parameters", t.parametersSchema)
                toolsArr.put(JSONObject().put("type", "function").put("function", fn))
            }
            payload.put("tools", toolsArr)
            payload.put("tool_choice", toolChoice.ifBlank { "auto" })
        }

        val req = Request.Builder()
            .url("$base/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return executeTextRequest(httpClient, req, apiKey) { bodyText ->
            parseChatCompletion(bodyText, apiKey)
        }
    }

    fun generateImage(
        config: AiGatewayConfig,
        prompt: String,
        size: String = "1024x1024",
    ): ByteArray {
        val base = normalizeBaseUrl(config.effectiveImageBaseUrl)
        val apiKey = config.effectiveImageApiKey
        if (apiKey.isEmpty()) throw IOException("Image API key is required")
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) throw IOException("Image prompt is required")

        val payload = JSONObject()
            .put("prompt", cleanPrompt)
            .put("n", 1)
            .put("size", size)
        if (config.imageModel.isNotBlank()) {
            payload.put("model", config.imageModel.trim())
        }

        val req = Request.Builder()
            .url("$base/v1/images/generations")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val respBody = executeTextRequest(imageClient, req, apiKey) { it }
        val root = try {
            JSONObject(respBody)
        } catch (e: Exception) {
            throw IOException("Invalid image response JSON: ${redactSecrets(e.message.orEmpty(), apiKey, config.apiKey)}")
        }
        val first = root.optJSONArray("data")?.optJSONObject(0)
            ?: throw IOException("Image response missing data[0]")

        val b64 = first.optString("b64_json", "").trim()
        if (b64.isNotEmpty() && !first.isNull("b64_json")) {
            val decoded = try {
                Base64.getDecoder().decode(b64)
            } catch (e: Exception) {
                throw IOException("Failed decoding b64_json: ${redactSecrets(e.message.orEmpty(), apiKey)}")
            }
            if (decoded.isEmpty()) throw IOException("Decoded image bytes were empty")
            return decoded
        }

        val imageUrl = first.optString("url", "").trim()
        if (imageUrl.isEmpty() || first.isNull("url")) {
            throw IOException("Image response missing b64_json and url")
        }
        return downloadImageFromUrl(imageUrl, base, apiKey)
    }

    private fun downloadImageFromUrl(url: String, gatewayBase: String, apiKey: String): ByteArray {
        val targetUri = try {
            URI(url)
        } catch (e: Exception) {
            throw IOException("Invalid image URL: ${redactSecrets(e.message.orEmpty(), apiKey)}")
        }
        val baseUri = runCatching { URI(gatewayBase) }.getOrNull()
        val reqBuilder = Request.Builder().url(url).get()
        if (isSameGatewayOrigin(baseUri, targetUri)) {
            reqBuilder.header("Authorization", "Bearer $apiKey")
        }
        try {
            imageClient.newCall(reqBuilder.build()).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                if (!resp.isSuccessful) {
                    throw IOException("Image asset download failed (${resp.code})")
                }
                if (bytes.isEmpty()) {
                    throw IOException("Downloaded image asset was empty")
                }
                return bytes
            }
        } catch (e: IOException) {
            throw IOException(redactSecrets(e.message ?: "Image download failed", apiKey))
        }
    }

    private fun <T> executeTextRequest(
        client: OkHttpClient,
        request: Request,
        secret: String,
        parser: (String) -> T,
    ): T {
        return try {
            client.newCall(request).execute().use { resp ->
                val bodyText = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val snippet = redactSecrets(extractErrorMessage(bodyText).ifBlank { bodyText.take(240) }, secret)
                    throw IOException("Gateway HTTP ${resp.code}: $snippet")
                }
                parser(bodyText)
            }
        } catch (e: IOException) {
            throw IOException(redactSecrets(e.message ?: "Gateway I/O error", secret), e)
        }
    }

    private fun extractErrorMessage(bodyText: String): String {
        if (bodyText.isBlank()) return ""
        return try {
            val obj = JSONObject(bodyText)
            obj.optJSONObject("error")?.optString("message", "").orEmpty()
                .ifBlank { obj.optString("message", "") }
        } catch (_: Exception) {
            bodyText.take(240)
        }
    }

    private fun encodeMessages(messages: List<ChatMessage>): JSONArray {
        val arr = JSONArray()
        for (m in messages) {
            val obj = JSONObject().put("role", m.role)
            if (m.content != null) {
                obj.put("content", m.content)
            }
            if (!m.toolCallId.isNullOrBlank()) {
                obj.put("tool_call_id", m.toolCallId)
            }
            if (m.toolCalls.isNotEmpty()) {
                val tcArr = JSONArray()
                for (tc in m.toolCalls) {
                    val fn = JSONObject()
                        .put("name", tc.name)
                        .put("arguments", tc.rawArguments.ifBlank { tc.arguments.toString() })
                    tcArr.put(
                        JSONObject()
                            .put("id", tc.id)
                            .put("type", "function")
                            .put("function", fn),
                    )
                }
                obj.put("tool_calls", tcArr)
            }
            arr.put(obj)
        }
        return arr
    }

    private fun parseChatCompletion(bodyText: String, secret: String): ChatCompletionResult {
        val root = try {
            JSONObject(bodyText)
        } catch (e: Exception) {
            throw IOException("Invalid chat JSON: ${redactSecrets(e.message.orEmpty(), secret)}")
        }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: throw IOException("Chat response missing choices[0]")
        val finishReason = choice.optString("finish_reason", "")
        val msg = choice.optJSONObject("message")
            ?: throw IOException("Chat response missing choices[0].message")
        val content = if (msg.isNull("content")) null else msg.optString("content", "").takeIf { it.isNotEmpty() }

        val toolCalls = mutableListOf<ToolCallRequest>()
        val tcArr = msg.optJSONArray("tool_calls")
        if (tcArr != null) {
            for (i in 0 until tcArr.length()) {
                val item = tcArr.optJSONObject(i) ?: continue
                val fn = item.optJSONObject("function") ?: continue
                val name = fn.optString("name", "").trim()
                if (name.isEmpty()) continue
                val rawArgs = fn.optString("arguments", "{}")
                val parsedArgs = try {
                    JSONObject(rawArgs.ifBlank { "{}" })
                } catch (_: Exception) {
                    JSONObject()
                }
                toolCalls.add(
                    ToolCallRequest(
                        id = item.optString("id", "call_$i"),
                        name = name,
                        arguments = parsedArgs,
                        rawArguments = rawArgs,
                    ),
                )
            }
        }
        return ChatCompletionResult(content = content, toolCalls = toolCalls, finishReason = finishReason)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val BEARER_TOKEN_RE = Regex("""(?i)\bBearer\s+\S{4,}""")
        private val SK_TOKEN_RE = Regex("""\bsk-[A-Za-z0-9_-]{8,}\b""")
        private val ZHH_TOKEN_RE = Regex("""\bzhh--[A-Za-z0-9_-]{8,}\b""")

        private fun effectivePort(uri: URI): Int = when {
            uri.port != -1 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            uri.scheme.equals("http", ignoreCase = true) -> 80
            else -> -1
        }

        internal fun isSameGatewayOrigin(baseUri: URI?, targetUri: URI): Boolean {
            if (baseUri == null) return false
            val baseScheme = baseUri.scheme?.takeIf { it.isNotBlank() } ?: return false
            val targetScheme = targetUri.scheme?.takeIf { it.isNotBlank() } ?: return false
            val baseHost = baseUri.host?.takeIf { it.isNotBlank() } ?: return false
            val targetHost = targetUri.host?.takeIf { it.isNotBlank() } ?: return false
            val basePort = effectivePort(baseUri)
            return basePort != -1 &&
                baseScheme.equals(targetScheme, ignoreCase = true) &&
                baseHost.equals(targetHost, ignoreCase = true) &&
                basePort == effectivePort(targetUri)
        }

        fun defaultTextClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10L, TimeUnit.SECONDS)
            .readTimeout(45L, TimeUnit.SECONDS)
            .writeTimeout(15L, TimeUnit.SECONDS)
            .build()

        fun redactSecrets(input: String, vararg extraSecrets: String): String {
            var out = input
            for (secret in extraSecrets) {
                val trimmed = secret.trim()
                if (trimmed.isNotEmpty()) {
                    out = out.replace(trimmed, "***")
                }
            }
            out = BEARER_TOKEN_RE.replace(out, "Bearer ***")
            out = SK_TOKEN_RE.replace(out, "sk-***")
            out = ZHH_TOKEN_RE.replace(out, "zhh--***")
            return out
        }
    }
}
