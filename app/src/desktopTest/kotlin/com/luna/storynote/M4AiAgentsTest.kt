package com.luna.storynote

import com.luna.storynote.agent.AgentTaskRole
import com.luna.storynote.agent.ToolBus
import com.luna.storynote.agent.ToolId
import com.luna.storynote.agent.ToolRequest
import com.luna.storynote.agent.ToolResult
import com.luna.storynote.ai.AiGatewayClient
import com.luna.storynote.ai.IllustrationAgent
import com.luna.storynote.ai.SilentCopyEditorAgent
import com.luna.storynote.ai.VoiceRevisionAgent
import com.luna.storynote.ai.VoiceSparkAgent
import com.luna.storynote.model.AiGatewayConfig
import com.luna.storynote.model.BookBible
import com.luna.storynote.storage.StoryBookRepository
import com.sun.net.httpserver.HttpServer
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory

class M4AiAgentsTest {
    private fun createDummyPngBase64(): String {
        val surface = Surface.makeRasterN32Premul(64, 64)
        surface.canvas.drawRect(Rect.makeWH(64f, 64f), Paint().apply { color = SkColor.makeRGB(235, 195, 140) })
        val bytes = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
        surface.close()
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun startLoopbackGateway(
        chatResponder: (JSONObject) -> JSONObject = { JSONObject() },
        imageResponder: (JSONObject) -> JSONObject = {
            JSONObject().put("data", JSONArray().put(JSONObject().put("b64_json", createDummyPngBase64())))
        },
    ): Pair<HttpServer, CopyOnWriteArrayList<Pair<String, String>>> {
        val captured = CopyOnWriteArrayList<Pair<String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            captured.add("/v1/chat/completions" to body)
            val respBytes = chatResponder(JSONObject(body)).toString().toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, respBytes.size.toLong())
            ex.responseBody.use { it.write(respBytes) }
        }
        server.createContext("/v1/images/generations") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            captured.add("/v1/images/generations" to body)
            val respBytes = imageResponder(JSONObject(body)).toString().toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, respBytes.size.toLong())
            ex.responseBody.use { it.write(respBytes) }
        }
        server.start()
        return server to captured
    }

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

    private fun textChatResponse(text: String): JSONObject {
        val msg = JSONObject().put("role", "assistant").put("content", text)
        return JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message", msg)))
    }

    @Test
    fun silentCopyEditorAnnotatesWithoutMutatingClauseTextAndUpdatesBible() {
        val tmp = createTempDirectory("m4-copy-").toFile()
        val (srv, captured) = startLoopbackGateway(
            chatResponder = { req ->
                val userMsg = req.getJSONArray("messages").getJSONObject(1).getString("content")
                val cid = Regex("""\[([^\]]+)] 原文=""").find(userMsg)!!.groupValues[1]
                toolCallResponse(
                    "annotate_soft_suggestion" to JSONObject()
                        .put("clause_id", cid).put("char_index", 1).put("length", 1)
                        .put("suggested_word", " 坐 ").put("corrected_pinyin", "zuò").put("reason", "同音字"),
                    "annotate_soft_suggestion" to JSONObject()
                        .put("clause_id", cid).put("char_index", 99).put("length", 2) // out-of-bounds dropped
                        .put("suggested_word", "坏"),
                    "annotate_soft_suggestion" to JSONObject()
                        .put("clause_id", cid).put("char_index", 0).put("length", 1) // no-op dropped
                        .put("suggested_word", "我"),
                    "update_book_bible" to JSONObject()
                        .put("protagonist_traits", JSONArray().put("戴黄帽子的小朋友"))
                        .put("key_scenes_and_props", JSONArray().put("棉花糖火山"))
                        .put("plot_summary", "坐在火山旁看星星"),
                )
            },
        )
        try {
            val repo = StoryBookRepository(tmp)
            val bus = ToolBus(repo)
            val book = repo.createBlankBook()
            bus.dispatch(AgentTaskRole.USER_DIRECT, ToolRequest(ToolId.APPEND_CLAUSE, mapOf("bookId" to book.id, "text" to "我座在棉花糖火山旁。")))
            val cfg = AiGatewayConfig(baseUrl = "http://127.0.0.1:${srv.address.port}", apiKey = "sk-test-dummy-m4-01", textModel = "auto/deepseek-v4.1-flash")

            val result = SilentCopyEditorAgent(AiGatewayClient(), bus).reviewBook(cfg, book.id)
            assertEquals(1, result.annotatedClauses)
            assertTrue(result.bibleUpdated)

            val updated = repo.getBook(book.id)!!
            assertEquals("我座在棉花糖火山旁。", updated.clauses.first().text)
            assertEquals(1, updated.clauses.first().softAnnotations.size)
            assertEquals("坐", updated.clauses.first().softAnnotations.first().suggestedWord)
            assertEquals("zuò", updated.clauses.first().softAnnotations.first().correctedPinyin)
            assertTrue(repo.bookBibles.read(book.id).keyScenesAndProps.contains("棉花糖火山"))
            assertTrue(captured.any { it.first == "/v1/chat/completions" })
        } finally {
            srv.stop(0)
            tmp.deleteRecursively()
        }
    }

    @Test
    fun voiceRevisionAgentReconcilesAnchorReplacesSpanAndSnapshots() {
        val tmp = createTempDirectory("m4-revise-").toFile()
        val (srv, _) = startLoopbackGateway(
            chatResponder = { req ->
                val userMsg = req.getJSONArray("messages").getJSONObject(1).getString("content")
                val cid = Regex("""当前句子 ID: (\S+)""").find(userMsg)!!.groupValues[1]
                toolCallResponse(
                    "replace_span" to JSONObject()
                        .put("clause_id", cid)
                        .put("start_index", 4) // deliberately off by 1, anchor reconciles to index 5
                        .put("end_index", 6)
                        .put("original_span_text", "红色")
                        .put("replacement", "金色"),
                )
            },
        )
        try {
            val repo = StoryBookRepository(tmp)
            val bus = ToolBus(repo)
            val book = repo.createBlankBook()
            val app = bus.dispatch(AgentTaskRole.USER_DIRECT, ToolRequest(ToolId.APPEND_CLAUSE, mapOf("bookId" to book.id, "text" to "小恐龙拿着红色的泡泡机。"))) as ToolResult.Success
            val cid = app.book!!.clauses.first().id
            val cfg = AiGatewayConfig(baseUrl = "http://127.0.0.1:${srv.address.port}", apiKey = "sk-test-dummy-m4-02", textModel = "auto/deepseek-v4.1-flash")

            val res = VoiceRevisionAgent(AiGatewayClient(), bus).reviseClause(cfg, book.id, cid, "把红色改成金色")
            assertTrue(res.applied)
            assertEquals("小恐龙拿着金色的泡泡机。", res.updatedText)
            assertEquals("小恐龙拿着金色的泡泡机。", repo.getBook(book.id)!!.clauses.first().text)

            val revSnap = repo.timeMachine.listSnapshots(book.id).first { it.toolName == "replace_span" }
            bus.dispatch(AgentTaskRole.USER_DIRECT, ToolRequest(ToolId.RESTORE_HISTORY_SNAPSHOT, mapOf("bookId" to book.id, "snapshotId" to revSnap.id)))
            assertEquals("小恐龙拿着红色的泡泡机。", repo.getBook(book.id)!!.clauses.first().text)
        } finally {
            srv.stop(0)
            tmp.deleteRecursively()
        }
    }

    @Test
    fun illustrationAgentCompilesDeterministicPromptAttachesAndDrivesCover() {
        val tmp = createTempDirectory("m4-illu-").toFile()
        val (srv, captured) = startLoopbackGateway()
        try {
            val repo = StoryBookRepository(tmp)
            val bus = ToolBus(repo)
            val book = repo.createBlankBook()
            repo.updateBookTitleAndCover(book.id, newTitle = "雨靴小恐龙")
            bus.dispatch(AgentTaskRole.USER_DIRECT, ToolRequest(ToolId.APPEND_CLAUSE, mapOf("bookId" to book.id, "text" to "小恐龙在彩虹森林散步。")))
            repo.bookBibles.write(BookBible(book.id, listOf("蓝色圆滚滚小恐龙", "黄色雨靴"), listOf("彩虹森林"), "散步"))

            val cfg = AiGatewayConfig(
                baseUrl = "http://127.0.0.1:${srv.address.port}",
                apiKey = "sk-test-dummy-m4-03",
                textModel = "auto/deepseek-v4.1-flash",
                imageModel = "codex/gpt-image-2",
            )
            val res = IllustrationAgent(AiGatewayClient(), bus).illustratePage(cfg, book.id, 0)
            assertTrue(res.success)
            assertNotNull(res.illustrationId)
            // Must NOT hit /v1/chat/completions
            assertFalse(captured.any { it.first == "/v1/chat/completions" })
            val imgPrompt = JSONObject(captured.first { it.first == "/v1/images/generations" }.second).getString("prompt")
            assertTrue(imgPrompt.contains("黄色雨靴"))
            assertTrue(imgPrompt.contains("水彩") && imgPrompt.contains("watercolor"))
            assertTrue(imgPrompt.contains("no text") && imgPrompt.contains("严禁任何文字"))

            val updated = repo.getBook(book.id)!!
            assertNotNull(updated.coverIllustrationFile())
            assertTrue(updated.coverIllustrationFile()!!.isFile)
            val illDirFiles = File(updated.bookDir, "illustrations").listFiles()?.map { it.name }.orEmpty()
            assertTrue(illDirFiles.none { it.startsWith("tmp_ill_") })
        } finally {
            srv.stop(0)
            tmp.deleteRecursively()
        }
    }

    @Test
    fun voiceSparkAgentIsStrictlyReadOnlyAndSpeaksSingleQuestion() {
        val tmp = createTempDirectory("m4-spark-").toFile()
        val (srv, captured) = startLoopbackGateway(
            chatResponder = { textChatResponse("“噗噗接下来想带谁一起走过彩虹桥呢？”\n补充说明") },
        )
        val spoken = mutableListOf<String>()
        try {
            val baseEnv = DesktopPlatformEnvironment(storageRoot = tmp)
            val env = object : PlatformEnvironment by baseEnv {
                override fun speakStandardWord(word: String) {
                    spoken.add(word)
                }
                override fun stopSpeaking() = Unit
            }
            val repo = StoryBookRepository(env.storageRoot)
            val bus = ToolBus(repo)
            val book = repo.createBlankBook()
            bus.dispatch(AgentTaskRole.USER_DIRECT, ToolRequest(ToolId.APPEND_CLAUSE, mapOf("bookId" to book.id, "text" to "小恐龙噗噗走到彩虹桥前。")))
            repo.bookBibles.write(BookBible(book.id, listOf("小恐龙噗噗"), listOf("彩虹桥"), "站在桥前"))

            val beforeJson = File(repo.getBook(book.id)!!.bookDir, "book.json").readText()
            val cfg = AiGatewayConfig(baseUrl = "http://127.0.0.1:${srv.address.port}", apiKey = "sk-test-dummy-m4-04", textModel = "auto/deepseek-v4.1-flash")

            val res = VoiceSparkAgent(AiGatewayClient(), bus, env).spark(cfg, book.id)
            assertEquals("噗噗接下来想带谁一起走过彩虹桥呢？", res.spokenQuestion)
            assertEquals(listOf("噗噗接下来想带谁一起走过彩虹桥呢？"), spoken)
            assertEquals(beforeJson, File(repo.getBook(book.id)!!.bookDir, "book.json").readText())
            assertTrue(captured.first { it.first == "/v1/chat/completions" }.second.contains("噗噗"))
        } finally {
            srv.stop(0)
            tmp.deleteRecursively()
        }
    }
}
