package com.luna.storynote.e2e.fixtures

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.awt.Color
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

data class CapturedHttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, List<String>>,
    val body: String,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    fun headerIgnoreCase(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

class FakeAiGatewayServer(
    initialModels: List<String> = listOf("auto/deepseek-v4.1-flash", "codex/gpt-image-2"),
) : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "fake-ai-gateway").apply { isDaemon = true }
    }
    private val callSeq = AtomicInteger(0)

    val port: Int get() = server.address.port
    val baseUrl: String get() = "http://127.0.0.1:$port"

    val capturedRequests: MutableList<CapturedHttpRequest> =
        Collections.synchronizedList(mutableListOf())

    @Volatile
    var configuredModels: List<String> = initialModels

    @Volatile
    var nextModelsStatus: Int? = null

    @Volatile
    var nextChatStatus: Int? = null

    @Volatile
    var nextImageStatus: Int? = null

    @Volatile
    var nextDelayMs: Long = 0L

    @Volatile
    var malformedJson: Boolean = false

    @Volatile
    var useUrlImageResponse: Boolean = false

    @Volatile
    var imageSizePx: Int = 64

    val chatResponseQueue: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()

    @Volatile
    var chatHandler: ((CapturedHttpRequest, JSONObject) -> String)? = null

    init {
        server.executor = executor
        server.createContext("/v1/models") { ex -> handleModels(ex) }
        server.createContext("/v1/chat/completions") { ex -> handleChatCompletions(ex) }
        server.createContext("/v1/images/generations") { ex -> handleImageGenerations(ex) }
        server.createContext("/mock-assets/page.png") { ex -> handleMockPngAsset(ex) }
        server.start()
    }

    fun clearRequests() {
        capturedRequests.clear()
    }

    fun enqueueChatText(content: String) {
        chatResponseQueue.add(textResponse(content))
    }

    fun enqueueChatToolCall(toolName: String, argumentsJson: JSONObject) {
        chatResponseQueue.add(toolCallResponse(toolName, argumentsJson))
    }

    fun enqueueChatToolCalls(calls: List<Pair<String, JSONObject>>) {
        chatResponseQueue.add(multiToolCallResponse(calls))
    }

    private fun handleModels(ex: HttpExchange) {
        val req = capture(ex)
        maybeDelay()
        val forced = consumeModelsStatus()
        if (forced != null) {
            sendJson(ex, forced, """{"error":{"message":"injected models error $forced"}}""")
            return
        }
        if (malformedJson) {
            malformedJson = false
            sendJson(ex, 200, "{broken_json")
            return
        }
        val dataArr = JSONArray()
        for (m in configuredModels) {
            dataArr.put(JSONObject().put("id", m).put("object", "model"))
        }
        val resp = JSONObject().put("object", "list").put("data", dataArr).toString()
        sendJson(ex, 200, resp)
    }

    private fun handleChatCompletions(ex: HttpExchange) {
        val req = capture(ex)
        maybeDelay()
        val forced = consumeChatStatus()
        if (forced != null) {
            sendJson(ex, forced, """{"error":{"message":"injected chat error $forced"}}""")
            return
        }
        if (malformedJson) {
            malformedJson = false
            sendJson(ex, 200, "{not-valid-json")
            return
        }
        val bodyObj = try {
            JSONObject(req.body.ifBlank { "{}" })
        } catch (_: Exception) {
            sendJson(ex, 400, """{"error":{"message":"invalid request json"}}""")
            return
        }

        val queued = chatResponseQueue.poll()
        val payload = when {
            queued != null -> queued
            chatHandler != null -> chatHandler!!.invoke(req, bodyObj)
            else -> textResponse("故事里接下来会发生什么有趣的事呢？")
        }
        sendJson(ex, 200, payload)
    }

    private fun handleImageGenerations(ex: HttpExchange) {
        val req = capture(ex)
        maybeDelay()
        val forced = consumeImageStatus()
        if (forced != null) {
            sendJson(ex, forced, """{"error":{"message":"injected image error $forced"}}""")
            return
        }
        if (malformedJson) {
            malformedJson = false
            sendJson(ex, 200, "<<<corrupt>>>")
            return
        }
        val bodyObj = try {
            JSONObject(req.body.ifBlank { "{}" })
        } catch (_: Exception) {
            sendJson(ex, 400, """{"error":{"message":"invalid image request json"}}""")
            return
        }
        val prompt = bodyObj.optString("prompt", "").trim()
        if (prompt.isEmpty()) {
            sendJson(ex, 400, """{"error":{"message":"prompt is required"}}""")
            return
        }
        val item = JSONObject()
        if (useUrlImageResponse) {
            item.put("url", "$baseUrl/mock-assets/page.png")
        } else {
            val b64 = Base64.getEncoder().encodeToString(createPngBytes(imageSizePx, imageSizePx))
            item.put("b64_json", b64)
        }
        val resp = JSONObject()
            .put("created", System.currentTimeMillis() / 1000L)
            .put("data", JSONArray().put(item))
            .toString()
        sendJson(ex, 200, resp)
    }

    private fun handleMockPngAsset(ex: HttpExchange) {
        capture(ex)
        val png = createPngBytes(imageSizePx, imageSizePx)
        ex.responseHeaders.add("Content-Type", "image/png")
        ex.sendResponseHeaders(200, png.size.toLong())
        ex.responseBody.use { it.write(png) }
    }

    private fun capture(ex: HttpExchange): CapturedHttpRequest {
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val hdrs = LinkedHashMap<String, List<String>>()
        for ((k, v) in ex.requestHeaders) {
            if (k != null) hdrs[k] = v.toList()
        }
        val rec = CapturedHttpRequest(
            method = ex.requestMethod,
            path = ex.requestURI.rawPath,
            headers = hdrs,
            body = body,
        )
        capturedRequests.add(rec)
        return rec
    }

    private fun maybeDelay() {
        val d = nextDelayMs
        if (d > 0L) {
            nextDelayMs = 0L
            Thread.sleep(d)
        }
    }

    private fun consumeModelsStatus(): Int? {
        val s = nextModelsStatus
        nextModelsStatus = null
        return s
    }

    private fun consumeChatStatus(): Int? {
        val s = nextChatStatus
        nextChatStatus = null
        return s
    }

    private fun consumeImageStatus(): Int? {
        val s = nextImageStatus
        nextImageStatus = null
        return s
    }

    private fun sendJson(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    fun textResponse(content: String): String {
        val msg = JSONObject()
            .put("role", "assistant")
            .put("content", content)
        return wrapChoice(msg, finishReason = "stop")
    }

    fun toolCallResponse(toolName: String, arguments: JSONObject): String =
        multiToolCallResponse(listOf(toolName to arguments))

    fun multiToolCallResponse(calls: List<Pair<String, JSONObject>>): String {
        val toolCallsArr = JSONArray()
        calls.forEachIndexed { idx, (name, args) ->
            toolCallsArr.put(
                JSONObject()
                    .put("id", "call_${callSeq.incrementAndGet()}_$idx")
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", name)
                            .put("arguments", args.toString()),
                    ),
            )
        }
        val msg = JSONObject()
            .put("role", "assistant")
            .put("content", JSONObject.NULL)
            .put("tool_calls", toolCallsArr)
        return wrapChoice(msg, finishReason = "tool_calls")
    }

    private fun wrapChoice(message: JSONObject, finishReason: String): String =
        JSONObject()
            .put("id", "chatcmpl_${callSeq.incrementAndGet()}")
            .put("object", "chat.completion")
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("message", message)
                        .put("finish_reason", finishReason),
                ),
            )
            .toString()

    companion object {
        fun createPngBytes(width: Int = 64, height: Int = 64): ByteArray {
            val w = width.coerceIn(16, 512)
            val h = height.coerceIn(16, 512)
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            try {
                g.paint = GradientPaint(0f, 0f, Color(0xF6, 0xEC, 0xD7), w.toFloat(), h.toFloat(), Color(0xD8, 0xE6, 0xDF))
                g.fillRect(0, 0, w, h)
                g.color = Color(0xD4, 0x6A, 0x4C)
                g.fillOval(w / 4, h / 4, w / 2, h / 2)
            } finally {
                g.dispose()
            }
            return ByteArrayOutputStream().use { out ->
                ImageIO.write(img, "png", out)
                out.toByteArray()
            }
        }
    }
}
