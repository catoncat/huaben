package com.luna.storynote

import com.luna.storynote.agent.AgentTaskRole
import com.luna.storynote.agent.ToolBus
import com.luna.storynote.agent.ToolId
import com.luna.storynote.agent.ToolRequest
import com.luna.storynote.agent.ToolResult
import com.luna.storynote.ai.AiGatewayClient
import com.luna.storynote.ai.BookAiCoordinator
import com.luna.storynote.ai.IllustrationAgent
import com.luna.storynote.ai.SilentCopyEditorAgent
import com.luna.storynote.ai.VoiceRevisionAgent
import com.luna.storynote.ai.VoiceSparkAgent
import com.luna.storynote.model.AiGatewayConfig
import com.luna.storynote.model.BookBible
import com.luna.storynote.model.BookIllustration
import com.luna.storynote.model.StoryBook
import com.luna.storynote.storage.StoryBookRepository
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.Color as SkColor
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory

class M4EdgeRegressionTest {
    private fun createDummyPngBytes(): ByteArray {
        val surface = Surface.makeRasterN32Premul(64, 64)
        surface.canvas.drawRect(Rect.makeWH(64f, 64f), Paint().apply { color = SkColor.makeRGB(220, 185, 130) })
        val bytes = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
        surface.close()
        return bytes
    }

    private fun dummyPngBase64(): String = Base64.getEncoder().encodeToString(createDummyPngBytes())

    private fun testEnv(root: File): DesktopPlatformEnvironment = DesktopPlatformEnvironment(
        storageRoot = File(root, "storage").apply { mkdirs() },
        privateConfigDir = File(root, "cfg").apply { mkdirs() },
        authCacheFile = File(root, "cfg/auth.json"),
        piAgentDir = File(root, "pi").apply { mkdirs() },
    )

    private fun toolCallResponse(vararg calls: Pair<String, JSONObject>): JSONObject {
        val arr = JSONArray()
        calls.forEachIndexed { idx, (name, args) ->
            arr.put(
                JSONObject()
                    .put("id", "call_$idx")
                    .put("type", "function")
                    .put("function", JSONObject().put("name", name).put("arguments", args.toString())),
            )
        }
        val msg = JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", arr)
        return JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls").put("message", msg)))
    }

    @Test
    fun rollbackBeforeFirstIllustrationClearsPageAndCoverWhilePreservingGcAndLegacyFallback() {
        val tmp = createTempDirectory("m4-reg-ill-").toFile()
        try {
            val repo = StoryBookRepository(tmp)
            val bus = ToolBus(repo)
            val book = repo.createBlankBook()
            bus.dispatch(AgentTaskRole.USER_DIRECT, ToolRequest(ToolId.APPEND_CLAUSE, mapOf("bookId" to book.id, "text" to "小恐龙在草地上跑。")))

            val pngFile = repo.newStagingWav().let { File(it.parentFile, "tmp_ill_seed.png") }.apply {
                writeBytes(createDummyPngBytes())
            }
            val attached = repo.attachIllustration(book.id, 0, pngFile, "watercolor dino")!!
            val firstIll = attached.activeIllustrationFor(0)!!
            assertNotNull(attached.coverIllustrationFile())

            // Roll back to snapshot recorded right before attach_page_illustration
            val preIllSnap = repo.timeMachine.listSnapshots(book.id).first { it.toolName == "attach_page_illustration" }
            val restored = repo.restoreSnapshot(book.id, preIllSnap.id)!!
            assertNull(restored.activeIllustrationFor(0))
            assertNull(restored.coverIllustrationFile())
            assertTrue(restored.illustrations.any { it.id == firstIll.id })
            assertTrue(repo.timeMachine.collectProtectedAssetPaths(restored).contains(firstIll.imageFileName))

            // Reload from disk to verify blank sentinel survives JSON serialization
            val reloaded = repo.getBook(book.id)!!
            assertNull(reloaded.activeIllustrationFor(0))
            assertNull(reloaded.coverIllustrationFile())

            // Re-selecting or attaching a new illustration cleanly overwrites the blank sentinel
            val reselected = repo.selectIllustration(book.id, 0, firstIll.id)!!
            assertEquals(firstIll.id, reselected.activeIllustrationFor(0)?.id)
            assertNotNull(reselected.coverIllustrationFile())

            // Legacy pre-map book with empty activeIllustrationIds still uses fallback
            val legacy = StoryBook(
                id = "legacy", bookNumber = 99, title = "旧绘本", titleAudioFileName = "",
                coverColorIndex = 0, emblemIndex = 0, clauses = emptyList(),
                illustrations = listOf(BookIllustration("ill_legacy", 0, "illustrations/legacy.png", "p", 1L)),
                activeIllustrationIds = emptyMap(), createdAtMs = 1L, updatedAtMs = 1L, bookDir = reloaded.bookDir,
            )
            assertEquals("ill_legacy", legacy.activeIllustrationFor(0)?.id)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun illustrationCompilePromptPreservesTargetPageEmotionAndSceneFaithfully() {
        val tmp = createTempDirectory("m4-reg-prompt-").toFile()
        try {
            val agent = IllustrationAgent(AiGatewayClient(), ToolBus(StoryBookRepository(tmp)))
            val forestText = "小恐龙慢慢地走出了房间，他往前走啊走，走啊走，终于来到了大森林里"
            val upbeatPrompt = agent.compilePrompt("小恐龙的大森林", forestText, BookBible("b1", listOf("小恐龙"), listOf("大森林"), ""))
            assertTrue(upbeatPrompt.contains(forestText))
            assertTrue(upbeatPrompt.contains("小恐龙") && upbeatPrompt.contains("大森林"))
            assertTrue(upbeatPrompt.contains("watercolor") && upbeatPrompt.contains("no text") && upbeatPrompt.contains("严禁任何文字"))
            assertFalse(upbeatPrompt.contains("Do NOT depict cut dead tree stumps"))

            val sadForestText = "森林里的一切都被毁了，小恐龙难过地哭了起来"
            val sadPrompt = agent.compilePrompt("森林不见了", sadForestText, BookBible("b2", listOf("小恐龙"), listOf("森林"), ""))
            assertTrue(sadPrompt.contains(sadForestText))
            assertTrue(sadPrompt.contains("小恐龙") && sadPrompt.contains("watercolor") && sadPrompt.contains("严禁任何文字"))
            assertFalse(sadPrompt.contains("joyful") || sadPrompt.contains("cheerful") || sadPrompt.contains("smiling") || sadPrompt.contains("Do NOT depict"))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun switchingToBook2DuringBook1IllustrationDoesNotCorruptBook2State() {
        val tmp = createTempDirectory("m4-reg-switch-").toFile()
        val requestEntered = CountDownLatch(1)
        val allowResponse = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/images/generations") { ex ->
            requestEntered.countDown()
            allowResponse.await(5, TimeUnit.SECONDS)
            val bytes = JSONObject()
                .put("data", JSONArray().put(JSONObject().put("b64_json", dummyPngBase64())))
                .toString()
                .toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        val env = testEnv(tmp)
        val repo = StoryBookRepository(env.storageRoot)
        val b1 = repo.createBlankBook()
        val b2 = repo.createBlankBook()
        repo.insertUtteranceWithClauses(b1.id, null, "第一本书的故事。", "", null, 0L, 0L, 0L)
        repo.insertUtteranceWithClauses(b2.id, null, "第二本书的故事。", "", null, 0L, 0L, 0L)
        repo.bookBibles.write(BookBible(b2.id, listOf("Book2Hero"), listOf("Book2Forest"), "Book2Plot"))
        val controller = BookStudioController(env)
        try {
            val initDeadline = System.currentTimeMillis() + 3000L
            while (controller.uiState.value.books.size < 2 && System.currentTimeMillis() < initDeadline) Thread.sleep(15L)
            controller.saveAiConfig(
                AiGatewayConfig("http://127.0.0.1:${server.address.port}", "sk-test-dummy-switch", "auto/deepseek-v4.1-flash", imageModel = "codex/gpt-image-2"),
            )
            val cfgWait = System.currentTimeMillis() + 3000L
            while (!controller.uiState.value.isAiConfigured && System.currentTimeMillis() < cfgWait) Thread.sleep(15L)
            controller.openBook(b1.id)
            controller.illustrateCurrentPage()
            assertTrue(requestEntered.await(3, TimeUnit.SECONDS))

            // Switch to Book 2 while Book 1 illustration request is in flight
            controller.openBook(b2.id)
            assertEquals(b2.id, controller.uiState.value.activeBookId)
            assertEquals(listOf("Book2Hero"), controller.uiState.value.bookBible?.protagonistTraits)
            assertNull(controller.uiState.value.illustratingPageIndex)

            // Let Book 1 illustration finish in background
            allowResponse.countDown()
            val illDone = System.currentTimeMillis() + 4000L
            while (controller.repo.getBook(b1.id)?.activeIllustrationFor(0) == null && System.currentTimeMillis() < illDone) {
                Thread.sleep(20L)
            }
            Thread.sleep(80L)
            assertEquals(b2.id, controller.uiState.value.activeBookId)
            assertEquals(b2.id, controller.uiState.value.bookBible?.bookId)
            assertEquals(listOf("Book2Hero"), controller.uiState.value.bookBible?.protagonistTraits)
            assertTrue(controller.uiState.value.timeMachineSnapshots.all { it.bookId == b2.id })
        } finally {
            allowResponse.countDown()
            controller.dispose()
            server.stop(0)
            tmp.deleteRecursively()
        }
    }

    @Test
    fun deletingBookDuringIllustrationLeavesNoGhostIllustrationsDir() {
        val tmp = createTempDirectory("m4-reg-del-").toFile()
        val requestEntered = CountDownLatch(1)
        val allowResponse = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/images/generations") { ex ->
            requestEntered.countDown()
            allowResponse.await(5, TimeUnit.SECONDS)
            val bytes = JSONObject()
                .put("data", JSONArray().put(JSONObject().put("b64_json", dummyPngBase64())))
                .toString()
                .toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        val env = testEnv(tmp)
        val repo = StoryBookRepository(env.storageRoot)
        val book = repo.createBlankBook()
        repo.insertUtteranceWithClauses(book.id, null, "恐龙去海边。", "", null, 0L, 0L, 0L)
        val controller = BookStudioController(env)
        try {
            val initDeadline = System.currentTimeMillis() + 3000L
            while (controller.uiState.value.books.isEmpty() && System.currentTimeMillis() < initDeadline) {
                Thread.sleep(15L)
            }
            controller.saveAiConfig(
                AiGatewayConfig(
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    apiKey = "sk-test-dummy-del",
                    textModel = "auto/deepseek-v4.1-flash",
                    imageModel = "codex/gpt-image-2",
                ),
            )
            val cfgDeadline = System.currentTimeMillis() + 3000L
            while (!controller.uiState.value.isAiConfigured && System.currentTimeMillis() < cfgDeadline) {
                Thread.sleep(15L)
            }
            controller.openBook(book.id)
            controller.illustrateCurrentPage()
            assertTrue(requestEntered.await(3, TimeUnit.SECONDS))

            controller.deleteBook(book.id)
            val delDeadline = System.currentTimeMillis() + 3000L
            while (book.bookDir.exists() && System.currentTimeMillis() < delDeadline) {
                Thread.sleep(15L)
            }
            assertFalse(book.bookDir.exists())

            allowResponse.countDown()
            Thread.sleep(200L)
            assertFalse("Book dir must not be recreated after delete", book.bookDir.exists())
            assertFalse("Ghost illustrations dir must not exist", File(book.bookDir, "illustrations").exists())

            // Direct agent call after delete also creates no ghost directory
            val directRes = IllustrationAgent(AiGatewayClient(), ToolBus(repo)).illustratePage(
                controller.uiState.value.aiConfig,
                book.id,
                0,
            )
            assertFalse(directRes.success)
            assertFalse(book.bookDir.exists())
        } finally {
            allowResponse.countDown()
            controller.dispose()
            server.stop(0)
            tmp.deleteRecursively()
        }
    }

    @Test
    fun voiceRevisionRejectsMissingNonEmptyAnchorInsteadOfBlindIndexFallback() {
        val tmp = createTempDirectory("m4-reg-rev-").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val userMsg = JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content")
            val cid = Regex("""当前句子 ID: (\S+)""").find(userMsg)!!.groupValues[1]
            val resp = toolCallResponse(
                "replace_span" to JSONObject()
                    .put("clause_id", cid)
                    .put("start_index", 0)
                    .put("end_index", 2)
                    .put("original_span_text", "香蕉") // absent from clause text
                    .put("replacement", "西瓜"),
            ).toString().toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.start()
        try {
            val repo = StoryBookRepository(tmp)
            val bus = ToolBus(repo)
            val book = repo.createBlankBook()
            val app = bus.dispatch(AgentTaskRole.USER_DIRECT, ToolRequest(ToolId.APPEND_CLAUSE, mapOf("bookId" to book.id, "text" to "红红的苹果和黄黄的草莓。"))) as ToolResult.Success
            val cid = app.book!!.clauses.first().id
            val cfg = AiGatewayConfig(baseUrl = "http://127.0.0.1:${server.address.port}", apiKey = "sk-test-dummy-rev", textModel = "auto/deepseek-v4.1-flash")

            val res = VoiceRevisionAgent(AiGatewayClient(), bus).reviseClause(cfg, book.id, cid, "把香蕉改成西瓜")
            assertFalse(res.applied)
            assertEquals("红红的苹果和黄黄的草莓。", repo.getBook(book.id)!!.clauses.first().text)
        } finally {
            server.stop(0)
            tmp.deleteRecursively()
        }
    }

    @Test
    fun voiceSparkSanitizeQuestionStripsFencesListPrefixesAndExtraLines() {
        val tmp = createTempDirectory("m4-reg-spark-").toFile()
        try {
            val env = testEnv(tmp)
            val agent = VoiceSparkAgent(AiGatewayClient(), ToolBus(StoryBookRepository(env.storageRoot)), env)
            assertEquals("小恐龙想去哪里呢？", agent.sanitizeQuestion("```text\n小恐龙想去哪里呢？\n```"))
            assertEquals("小恐龙接下来会遇到谁呢？", agent.sanitizeQuestion("小恐龙接下来会遇到谁呢\n补充解释：引导孩子回答？"))
            assertEquals("小恐龙接下来去哪里玩？", agent.sanitizeQuestion("1. 小恐龙接下来去哪里玩？"))
            assertEquals("月亮船 要开去哪里呢？", agent.sanitizeQuestion("2、 **月亮船**  要开去哪里呢。"))
            assertEquals("小兔子带了什么礼物呢？", agent.sanitizeQuestion("- 小兔子带了什么礼物呢"))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun silentCopyEditorDedupsSameBatchOverlapsAndSkipsStaleClauseAfterLlmFlight() {
        val tmp = createTempDirectory("m4-reg-copy-").toFile()
        val repo = StoryBookRepository(tmp)
        val bus = ToolBus(repo)
        val book = repo.createBlankBook()
        val c1 = (bus.dispatch(AgentTaskRole.USER_DIRECT, ToolRequest(ToolId.APPEND_CLAUSE, mapOf("bookId" to book.id, "text" to "坐在草地上看星星。"))) as ToolResult.Success)
            .book!!.clauses.first()
        val c2 = (bus.dispatch(AgentTaskRole.USER_DIRECT, ToolRequest(ToolId.APPEND_CLAUSE, mapOf("bookId" to book.id, "text" to "我座在草地上。"))) as ToolResult.Success)
            .book!!.clauses.last()

        var mutateDuringLlm = false
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            ex.requestBody.readBytes()
            if (mutateDuringLlm) {
                bus.dispatch(
                    AgentTaskRole.USER_DIRECT,
                    ToolRequest(ToolId.REPLACE_SPAN, mapOf("bookId" to book.id, "clauseId" to c2.id, "startIndex" to 1, "endIndex" to 2, "replacement" to "躺")),
                )
            }
            val resp = if (!mutateDuringLlm) {
                toolCallResponse(
                    "annotate_soft_suggestion" to JSONObject()
                        .put("clause_id", c1.id).put("char_index", 0).put("length", 2)
                        .put("corrected_pinyin", "zuò zài"),
                    "annotate_soft_suggestion" to JSONObject()
                        .put("clause_id", c1.id).put("char_index", 1).put("length", 1)
                        .put("suggested_word", "上"),
                )
            } else {
                toolCallResponse(
                    "annotate_soft_suggestion" to JSONObject()
                        .put("clause_id", c2.id).put("char_index", 1).put("length", 1)
                        .put("suggested_word", "坐").put("corrected_pinyin", "zuò"),
                )
            }
            val bytes = resp.toString().toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val cfg = AiGatewayConfig(baseUrl = "http://127.0.0.1:${server.address.port}", apiKey = "sk-test-dummy-copy", textModel = "auto/deepseek-v4.1-flash")
            val agent = SilentCopyEditorAgent(AiGatewayClient(), bus)

            // 1. Same-batch overlapping annotations deduplicated
            val res1 = agent.reviewBook(cfg, book.id, listOf(c1.id))
            assertEquals(1, res1.annotatedClauses)
            val anns1 = repo.getBook(book.id)!!.clauses.first { it.id == c1.id }.softAnnotations
            assertEquals(1, anns1.size)
            assertEquals(0, anns1.first().charIndex)
            assertEquals(2, anns1.first().length)

            // 2. Stale clause skipped if text changed during async LLM call
            mutateDuringLlm = true
            val res2 = agent.reviewBook(cfg, book.id, listOf(c2.id))
            assertEquals(0, res2.annotatedClauses)
            val clause2After = repo.getBook(book.id)!!.clauses.first { it.id == c2.id }
            assertEquals("我躺在草地上。", clause2After.text)
            assertTrue(clause2After.softAnnotations.isEmpty())
        } finally {
            server.stop(0)
            tmp.deleteRecursively()
        }
    }

    @Test
    fun coordinatorAtomicCopyEditSwapHasNoOrphanedJobs() {
        val tmp = createTempDirectory("m4-reg-coord-").toFile()
        val calls = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            calls.incrementAndGet()
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val user = JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content")
            val cid = Regex("""\[([^\]]+)] 原文=""").find(user)!!.groupValues[1]
            val resp = toolCallResponse(
                "annotate_soft_suggestion" to JSONObject()
                    .put("clause_id", cid).put("char_index", 0).put("length", 1).put("suggested_word", "你"),
            ).toString().toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.start()
        val coordScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val env = testEnv(tmp)
            val repo = StoryBookRepository(env.storageRoot)
            val book = repo.createBlankBook()
            repo.insertUtteranceWithClauses(book.id, null, "我爱画画。", "", null, 0L, 0L, 0L)
            val cfg = AiGatewayConfig(baseUrl = "http://127.0.0.1:${server.address.port}", apiKey = "sk-test-dummy-atom", textModel = "auto/deepseek-v4.1-flash")
            val coord = BookAiCoordinator(
                env, ToolBus(repo), AiGatewayClient(), coordScope,
                MutableStateFlow(com.luna.storynote.model.StudioUiState(aiConfig = cfg, activeBookId = book.id)),
                { _, _ -> }, {},
            )
            val threads = (1..8).map { Thread { repeat(5) { coord.scheduleSilentCopyEdit(book.id) } }.apply { start() } }
            threads.forEach { it.join() }
            val deadline = System.currentTimeMillis() + 3000L
            while (calls.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20L)
            Thread.sleep(300L)
            assertEquals("Concurrent copy-edit scheduling must leave exactly 1 active debounced job", 1, calls.get())
        } finally {
            coordScope.cancel()
            server.stop(0)
            tmp.deleteRecursively()
        }
    }
}
