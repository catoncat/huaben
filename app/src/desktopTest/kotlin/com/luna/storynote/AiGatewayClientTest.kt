package com.luna.storynote

import com.luna.storynote.ai.AiGatewayClient
import com.luna.storynote.ai.ChatMessage
import com.luna.storynote.ai.ChatToolSpec
import com.luna.storynote.model.AiGatewayConfig
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Base64
import java.util.Collections

class AiGatewayClientTest {
    private lateinit var server: HttpServer
    private lateinit var cdnServer: HttpServer
    private val requests = Collections.synchronizedList(mutableListOf<CapturedReq>())
    private val cdnHeaders = Collections.synchronizedList(mutableListOf<String?>())

    @Volatile
    private var modelsStatus = 200

    @Volatile
    private var modelsBody = ""

    @Volatile
    private var chatHandler: ((JSONObject) -> String)? = null

    @Volatile
    private var imageHandler: ((JSONObject) -> String)? = null

    private data class CapturedReq(val path: String, val auth: String?, val body: String)

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        cdnServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        server.createContext("/v1/models") { ex ->
            val auth = ex.requestHeaders.getFirst("Authorization")
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            requests.add(CapturedReq(ex.requestURI.path, auth, body))
            val resp = if (modelsBody.isNotEmpty()) {
                modelsBody
            } else {
                JSONObject()
                    .put(
                        "data",
                        JSONArray()
                            .put(JSONObject().put("id", "auto/deepseek-v4.1-flash"))
                            .put(JSONObject().put("id", "codex/gpt-image-2")),
                    )
                    .toString()
            }
            val bytes = resp.toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(modelsStatus, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        server.createContext("/v1/chat/completions") { ex ->
            val auth = ex.requestHeaders.getFirst("Authorization")
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            requests.add(CapturedReq(ex.requestURI.path, auth, body))
            val reqJson = JSONObject(body)
            val resp = chatHandler?.invoke(reqJson) ?: JSONObject()
                .put(
                    "choices",
                    JSONArray().put(
                        JSONObject()
                            .put("finish_reason", "stop")
                            .put("message", JSONObject().put("role", "assistant").put("content", "OK")),
                    ),
                )
                .toString()
            val bytes = resp.toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        server.createContext("/v1/images/generations") { ex ->
            val auth = ex.requestHeaders.getFirst("Authorization")
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            requests.add(CapturedReq(ex.requestURI.path, auth, body))
            val reqJson = JSONObject(body)
            val resp = imageHandler?.invoke(reqJson) ?: JSONObject()
                .put(
                    "data",
                    JSONArray().put(
                        JSONObject().put(
                            "b64_json",
                            Base64.getEncoder().encodeToString(byteArrayOf(10, 20, 30, 40)),
                        ),
                    ),
                )
                .toString()
            val bytes = resp.toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        server.createContext("/assets/same-host.png") { ex ->
            requests.add(CapturedReq(ex.requestURI.path, ex.requestHeaders.getFirst("Authorization"), ""))
            val png = byteArrayOf(1, 2, 3, 4, 5)
            ex.sendResponseHeaders(200, png.size.toLong())
            ex.responseBody.use { it.write(png) }
        }

        cdnServer.createContext("/cdn.png") { ex ->
            cdnHeaders.add(ex.requestHeaders.getFirst("Authorization"))
            val png = byteArrayOf(9, 8, 7, 6)
            ex.sendResponseHeaders(200, png.size.toLong())
            ex.responseBody.use { it.write(png) }
        }

        server.start()
        cdnServer.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        cdnServer.stop(0)
    }

    private fun baseUrl(): String = "http://127.0.0.1:${server.address.port}"

    @Test
    fun normalizesBaseUrlsAndTestsConnection() {
        val client = AiGatewayClient()
        assertTrue(client.imageReadTimeoutMillis >= 90_000L)

        assertEquals("https://example.invalid", client.normalizeBaseUrl("https://example.invalid///"))
        assertEquals("https://example.invalid", client.normalizeBaseUrl("https://example.invalid/v1/"))
        assertEquals("https://example.invalid", client.normalizeBaseUrl("https://example.invalid/V1"))

        val cfg = AiGatewayConfig(
            baseUrl = "${baseUrl()}/v1/",
            apiKey = "sk-test-dummy-gateway-01",
            textModel = "auto/deepseek-v4.1-flash",
            imageModel = "codex/gpt-image-2",
        )
        val res = client.testConnection(cfg)
        assertTrue(res.ok)
        assertEquals(listOf("auto/deepseek-v4.1-flash", "codex/gpt-image-2"), res.models)
        assertEquals(res.models, client.testConnectivity(cfg).models)
        assertEquals("Bearer sk-test-dummy-gateway-01", requests.first().auth)
    }

    @Test
    fun chatWithToolsParsesTextAndToolCalls() {
        val client = AiGatewayClient()
        val cfg = AiGatewayConfig(
            baseUrl = baseUrl(),
            apiKey = "sk-test-dummy-chat-02",
            textModel = "auto/deepseek-v4.1-flash",
        )
        chatHandler = { req ->
            assertEquals(1, req.getJSONArray("tools").length())
            JSONObject()
                .put(
                    "choices",
                    JSONArray().put(
                        JSONObject()
                            .put("finish_reason", "tool_calls")
                            .put(
                                "message",
                                JSONObject()
                                    .put("role", "assistant")
                                    .put("content", JSONObject.NULL)
                                    .put(
                                        "tool_calls",
                                        JSONArray().put(
                                            JSONObject()
                                                .put("id", "call_1")
                                                .put("type", "function")
                                                .put(
                                                    "function",
                                                    JSONObject()
                                                        .put("name", "replace_span")
                                                        .put("arguments", """{"clause_id":"c1","replacement":"金色"}"""),
                                                ),
                                        ),
                                    ),
                            ),
                    ),
                )
                .toString()
        }

        val tool = ChatToolSpec(
            name = "replace_span",
            description = "Replace word span",
            parametersSchema = JSONObject().put("type", "object"),
        )
        val out = client.chatWithTools(
            config = cfg,
            messages = listOf(ChatMessage(role = "user", content = "把红色改成金色")),
            tools = listOf(tool),
        )
        assertNull(out.content)
        assertEquals("tool_calls", out.finishReason)
        assertEquals(1, out.toolCalls.size)
        assertEquals("replace_span", out.toolCalls[0].name)
        assertEquals("金色", out.toolCalls[0].arguments.getString("replacement"))
    }

    @Test
    fun generateImageSupportsB64JsonUrlFallbackAndOmitsBlankModel() {
        val client = AiGatewayClient()
        val cfgNoModel = AiGatewayConfig(
            baseUrl = baseUrl(),
            apiKey = "sk-test-dummy-img-01",
            textModel = "auto/deepseek-v4.1-flash",
            imageModel = "",
        )
        imageHandler = { req ->
            assertFalse("Blank imageModel must be omitted", req.has("model"))
            assertFalse("response_format must not be sent", req.has("response_format"))
            JSONObject()
                .put(
                    "data",
                    JSONArray().put(
                        JSONObject().put(
                            "b64_json",
                            Base64.getEncoder().encodeToString(byteArrayOf(7, 8, 9)),
                        ),
                    ),
                )
                .toString()
        }
        assertArrayEquals(byteArrayOf(7, 8, 9), client.generateImage(cfgNoModel, "warm watercolor"))

        // Same-host URL fallback attaches auth; separate image endpoint key is used
        val cfgSameHost = cfgNoModel.copy(
            imageBaseUrl = "${baseUrl()}/v1",
            imageApiKey = "sk-test-dummy-img-sep",
            imageModel = "codex/gpt-image-2",
        )
        imageHandler = { req ->
            assertEquals("codex/gpt-image-2", req.getString("model"))
            JSONObject()
                .put("data", JSONArray().put(JSONObject().put("url", "${baseUrl()}/assets/same-host.png")))
                .toString()
        }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), client.generateImage(cfgSameHost, "fox"))
        val assetReq = requests.last { it.path == "/assets/same-host.png" }
        assertEquals("Bearer sk-test-dummy-img-sep", assetReq.auth)

        // Cross-host URL fallback must NOT leak Authorization header (even if host differs)
        // Here we test with localhost vs 127.0.0.1 which are different hostnames
        imageHandler = {
            JSONObject()
                .put("data", JSONArray().put(JSONObject().put("url", "http://localhost:${cdnServer.address.port}/cdn.png")))
                .toString()
        }
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), client.generateImage(cfgSameHost, "dino"))
        assertNull("Cross-host asset download must not send Authorization", cdnHeaders.first())
    }

    @Test
    fun redactsBearerSkAndZhhSecretsInErrorsAndDiagnostics() {
        val client = AiGatewayClient()
        modelsStatus = 401
        val skSecret = "sk-test-dummy-secret-9988"
        val zhhSecret = "zhh--test-dummy-secret-7766"
        modelsBody = """{"error":{"message":"Unauthorized for Bearer $skSecret and $zhhSecret"}}"""

        val cfg = AiGatewayConfig(
            baseUrl = baseUrl(),
            apiKey = zhhSecret,
            textModel = "auto/deepseek-v4.1-flash",
            imageApiKey = skSecret,
        )
        val res = client.testConnection(cfg)
        assertFalse(res.ok)
        assertFalse(res.message.contains(skSecret))
        assertFalse(res.message.contains(zhhSecret))
        assertTrue(res.message.contains("***"))

        try {
            client.listModels(baseUrl(), zhhSecret)
        } catch (e: IOException) {
            assertFalse(e.message.orEmpty().contains(zhhSecret))
            assertFalse(e.message.orEmpty().contains(skSecret))
        }

        // Base64 / URL-safe Bearer tokens containing +, /, = must be fully redacted
        assertEquals(
            "Auth failed: Bearer ***",
            AiGatewayClient.redactSecrets("Auth failed: Bearer AbCd+EfG/Hij=="),
        )
    }

    @Test
    fun downloadImageFromUrlStripsAuthorizationOnDifferentPortOrScheme() {
        val client = AiGatewayClient()
        val cfg = AiGatewayConfig(
            baseUrl = baseUrl(),
            apiKey = "sk-test-dummy-port-check",
            textModel = "auto/deepseek-v4.1-flash",
            imageModel = "codex/gpt-image-2",
        )
        // Same host (127.0.0.1) but different port must NOT receive Authorization header
        imageHandler = {
            JSONObject()
                .put("data", JSONArray().put(JSONObject().put("url", "http://127.0.0.1:${cdnServer.address.port}/cdn.png")))
                .toString()
        }
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), client.generateImage(cfg, "dino"))
        assertNull("Same-host different-port URL must not send Authorization", cdnHeaders.last())

        // Verify scheme + host + effective port matching directly
        val httpsDefault = java.net.URI("https://gateway.example.com")
        assertTrue(AiGatewayClient.isSameGatewayOrigin(httpsDefault, java.net.URI("https://gateway.example.com:443/img.png")))
        assertFalse(AiGatewayClient.isSameGatewayOrigin(httpsDefault, java.net.URI("http://gateway.example.com/img.png")))
        assertFalse(AiGatewayClient.isSameGatewayOrigin(httpsDefault, java.net.URI("http://gateway.example.com:443/img.png")))
        assertTrue(
            AiGatewayClient.isSameGatewayOrigin(
                java.net.URI("http://gateway.example.com:80"),
                java.net.URI("http://gateway.example.com/img.png"),
            ),
        )
    }
}
