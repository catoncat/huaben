package com.luna.storynote

import com.luna.storynote.agent.CommittedUtteranceHandler
import com.luna.storynote.agent.ToolBus
import com.luna.storynote.audio.CommittedUtterance
import com.luna.storynote.literacy.WordSpan
import com.luna.storynote.model.ActiveWordInspection
import com.luna.storynote.model.AiGatewayConfig
import com.luna.storynote.model.MicIntent
import com.luna.storynote.model.StudioUiState
import com.luna.storynote.model.UiMotionEvent
import com.luna.storynote.storage.StoryBookRepository
import com.luna.storynote.ui.CandidateReplacement
import com.luna.storynote.ui.resolveCandidateReplacement
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory

class M4CoordinatorAndUiEdgeTest {
    @Test
    fun tianZiGeResolvesTrimmedSoftSuggestionAndSubSpanReplacement() {
        val inspection = ActiveWordInspection(
            clauseId = "c1",
            span = WordSpan("小恐龙", 2, 5, listOf("xiǎo", "kǒng", "lóng")),
            homophones = listOf("小孔龙"),
            softSuggestion = "  孔龙 ",
            initialCharIndexInWord = 1,
            softAnnotationOffsetInWord = 1,
            softAnnotationLength = 2,
        )
        val action = resolveCandidateReplacement(inspection, 1, "孔龙")
        assertEquals(CandidateReplacement.ClauseSpan(3, 5, "孔龙"), action)
    }

    @Test
    fun controllerSchedulesDebouncedSilentCopyEditorAndExecutesVoiceRevision() {
        val tmp = createTempDirectory("m4-ctrl-").toFile()
        val copyCalls = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val req = JSONObject(body)
            val sys = req.getJSONArray("messages").getJSONObject(0).getString("content")
            val user = req.getJSONArray("messages").getJSONObject(1).getString("content")
            val resp = if (sys.contains("局部语音修改")) {
                val cid = Regex("""当前句子 ID: (\S+)""").find(user)!!.groupValues[1]
                JSONObject().put(
                    "choices",
                    JSONArray().put(
                        JSONObject().put("finish_reason", "tool_calls").put(
                            "message",
                            JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put(
                                "tool_calls",
                                JSONArray().put(
                                    JSONObject().put("id", "c_rev").put("type", "function").put(
                                        "function",
                                        JSONObject().put("name", "replace_span").put(
                                            "arguments",
                                            JSONObject().put("clause_id", cid).put("start_index", 5).put("end_index", 7)
                                                .put("original_span_text", "红色").put("replacement", "金色").toString(),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
            } else {
                copyCalls.incrementAndGet()
                val cid = Regex("""\[([^\]]+)] 原文=""").find(user)!!.groupValues[1]
                JSONObject().put(
                    "choices",
                    JSONArray().put(
                        JSONObject().put("finish_reason", "tool_calls").put(
                            "message",
                            JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put(
                                "tool_calls",
                                JSONArray().put(
                                    JSONObject().put("id", "c_ann").put("type", "function").put(
                                        "function",
                                        JSONObject().put("name", "annotate_soft_suggestion").put(
                                            "arguments",
                                            JSONObject().put("clause_id", cid).put("char_index", 1).put("length", 1)
                                                .put("suggested_word", "狐").put("corrected_pinyin", "hú").toString(),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
            }
            val bytes = resp.toString().toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val env = DesktopPlatformEnvironment(
                storageRoot = File(tmp, "storage").apply { mkdirs() },
                privateConfigDir = File(tmp, "cfg").apply { mkdirs() },
                authCacheFile = File(tmp, "cfg/auth.json"),
                piAgentDir = File(tmp, "pi").apply { mkdirs() },
            )
            val repo = StoryBookRepository(env.storageRoot)
            val created = repo.createBlankBook()
            val (_, inserted) = repo.insertUtteranceWithClauses(created.id, null, "小恐龙拿着红色的泡泡机。", "", null, 0L, 0L, 0L)
            val cid = inserted.first().id

            val controller = BookStudioController(env)
            try {
                val deadline = System.currentTimeMillis() + 3000L
                while (controller.uiState.value.books.isEmpty() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20L)
                }
                controller.saveAiConfig(
                    AiGatewayConfig(
                        baseUrl = "http://127.0.0.1:${server.address.port}",
                        apiKey = "sk-test-dummy-m4-ctrl",
                        textModel = "auto/deepseek-v4.1-flash",
                    ),
                )
                val cfgDeadline = System.currentTimeMillis() + 3000L
                while (!controller.uiState.value.isAiConfigured && System.currentTimeMillis() < cfgDeadline) {
                    Thread.sleep(20L)
                }
                controller.openBook(created.id)
                controller.submitVoiceRevision(cid, "把红色改成金色")

                val revDeadline = System.currentTimeMillis() + 4000L
                while (controller.uiState.value.activeBook?.clauses?.firstOrNull()?.text != "小恐龙拿着金色的泡泡机。" &&
                    System.currentTimeMillis() < revDeadline
                ) {
                    Thread.sleep(20L)
                }
                assertEquals("小恐龙拿着金色的泡泡机。", controller.uiState.value.activeBook!!.clauses.first().text)
                assertEquals(UiMotionEvent.ClauseRevised(cid), controller.uiState.value.lastMotionEvent)

                // Verify CommittedUtteranceHandler triggers onSpeechLanded & VoiceRevision deletes scratch wav
                val handlerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                try {
                    val landedIds = mutableListOf<String>()
                    val revInstrs = mutableListOf<String>()
                    val scratchWav = File(tmp, "cmd_scratch.wav").apply { writeText("wav") }
                    val handler = CommittedUtteranceHandler(
                        repo = repo,
                        toolBus = ToolBus(repo),
                        scope = handlerScope,
                        uiState = MutableStateFlow(StudioUiState(activeBookId = created.id)),
                        refreshBookState = { _, _ -> },
                        onSpeechLanded = { _, ids -> landedIds.addAll(ids) },
                        onVoiceRevision = { _, _, instr -> revInstrs.add(instr) },
                    )
                    handler.handle(
                        created.id,
                        MicIntent.VoiceRevision(cid),
                        CommittedUtterance("t1", scratchWav, 100L, "把金色改成银色", 0L, 100L),
                    )
                    val wavDeadline = System.currentTimeMillis() + 2000L
                    while (revInstrs.isEmpty() && System.currentTimeMillis() < wavDeadline) {
                        Thread.sleep(20L)
                    }
                    assertFalse(scratchWav.exists())
                    assertEquals(listOf("把金色改成银色"), revInstrs)
                    assertTrue(landedIds.isEmpty())

                    // Also verify WriteAtCursor triggers onSpeechLanded and 1s debounced copy-editor coalesces rapid triggers
                    val wav2 = repo.newStagingWav().also { f ->
                        com.luna.storynote.audio.WavFileStreamWriter(f).apply { appendSilenceMs(200); close() }
                    }
                    handler.handle(
                        created.id,
                        MicIntent.WriteAtCursor,
                        CommittedUtterance("t2", wav2, 200L, "小狐狸笑了。", 0L, 200L),
                    )
                    val speechDeadline = System.currentTimeMillis() + 2000L
                    while (landedIds.isEmpty() && System.currentTimeMillis() < speechDeadline) {
                        Thread.sleep(20L)
                    }
                    assertTrue(landedIds.isNotEmpty())
                } finally {
                    handlerScope.cancel()
                }

                // Trigger rapid debounced copy-edit calls directly through BookAiCoordinator via controller
                val beforeCopyCalls = copyCalls.get()
                val aiClient = com.luna.storynote.ai.AiGatewayClient()
                val coordScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                try {
                    val stateFlow = MutableStateFlow(controller.uiState.value)
                    val coord = com.luna.storynote.ai.BookAiCoordinator(
                        env = env,
                        toolBus = ToolBus(repo),
                        aiGatewayClient = aiClient,
                        scope = coordScope,
                        uiState = stateFlow,
                        refreshBookState = { _, _ -> },
                        postNotice = {},
                    )
                    coord.scheduleSilentCopyEdit(created.id, listOf(cid))
                    coord.scheduleSilentCopyEdit(created.id, listOf(cid))
                    Thread.sleep(300L)
                    assertEquals("Must debounce before 1000ms", beforeCopyCalls, copyCalls.get())
                    val copyDeadline = System.currentTimeMillis() + 2500L
                    while (copyCalls.get() == beforeCopyCalls && System.currentTimeMillis() < copyDeadline) {
                        Thread.sleep(30L)
                    }
                    assertEquals("Rapid calls must coalesce into 1 request", beforeCopyCalls + 1, copyCalls.get())
                } finally {
                    coordScope.cancel()
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
