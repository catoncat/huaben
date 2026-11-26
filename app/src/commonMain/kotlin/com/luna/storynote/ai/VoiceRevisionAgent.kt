package com.luna.storynote.ai

import com.luna.storynote.agent.AgentTaskRole
import com.luna.storynote.agent.ToolBus
import com.luna.storynote.agent.ToolId
import com.luna.storynote.agent.ToolRequest
import com.luna.storynote.agent.ToolResult
import com.luna.storynote.literacy.JiebaWordSegmenter
import com.luna.storynote.model.AiGatewayConfig
import com.luna.storynote.model.BookBible
import com.luna.storynote.model.Clause
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

data class VoiceRevisionResult(
    val applied: Boolean,
    val clauseId: String,
    val updatedText: String?,
    val errorMessage: String? = null,
)

class VoiceRevisionAgent(
    private val aiClient: AiGatewayClient,
    private val toolBus: ToolBus,
) {
    fun reviseClause(
        config: AiGatewayConfig,
        bookId: String,
        clauseId: String,
        instruction: String,
    ): VoiceRevisionResult {
        val cleanInstruction = instruction.trim()
        if (cleanInstruction.isEmpty()) {
            return VoiceRevisionResult(false, clauseId, null, "修改指令为空")
        }
        if (!config.isConfigured) {
            return VoiceRevisionResult(false, clauseId, null, "AI未配置")
        }

        val bookRes = toolBus.dispatch(
            AgentTaskRole.VOICE_REVISION,
            ToolRequest(ToolId.READ_BOOK_STATE, mapOf("bookId" to bookId)),
        ) as? ToolResult.Success ?: return VoiceRevisionResult(false, clauseId, null, "读取故事失败")
        val book = bookRes.book ?: return VoiceRevisionResult(false, clauseId, null, "故事不存在")
        val clause = book.clauses.firstOrNull { it.id == clauseId }
            ?: return VoiceRevisionResult(false, clauseId, null, "句子不存在")

        val bibleRes = toolBus.dispatch(
            AgentTaskRole.VOICE_REVISION,
            ToolRequest(ToolId.READ_BOOK_BIBLE, mapOf("bookId" to bookId)),
        ) as? ToolResult.Success
        val bible = bibleRes?.bible ?: BookBible(bookId = bookId)

        val messages = buildMessages(book.title, book.clauses, clause, bible, cleanInstruction)
        val resp = aiClient.chatWithTools(
            config = config,
            messages = messages,
            tools = listOf(REPLACE_SPAN_TOOL_SPEC),
            temperature = 0.1,
            maxTokens = 768,
            toolChoice = "required",
        )

        val rawCalls = resp.toolCalls.filter { it.name == ToolId.REPLACE_SPAN.toolName }.map { it.arguments }
        val calls = rawCalls.ifEmpty {
            parseJsonFallback(resp.content)?.let { listOf(it) }.orEmpty()
        }
        if (calls.isEmpty()) {
            return VoiceRevisionResult(false, clauseId, null, "未生成替换操作")
        }

        val validClauseIds = book.clauses.map { it.id }.toHashSet()
        var appliedCount = 0
        var lastError: String? = null
        var latestBook = book

        for (args in calls.take(6)) {
            val targetClauseId = args.optString("clause_id", args.optString("clauseId", clauseId))
                .trim()
                .ifEmpty { clauseId }
            if (targetClauseId !in validClauseIds) {
                lastError = "替换目标句子不存在"
                continue
            }
            val liveClause = latestBook.clauses.firstOrNull { it.id == targetClauseId } ?: continue
            val replacement = args.optString("replacement", args.optString("replacementWord", "")).trim()
            if (replacement.isEmpty()) {
                lastError = "替换内容为空"
                continue
            }
            val rawStart = when {
                args.has("start_index") -> args.optInt("start_index", -1)
                args.has("startIndex") -> args.optInt("startIndex", -1)
                else -> -1
            }
            val rawEnd = when {
                args.has("end_index") -> args.optInt("end_index", -1)
                args.has("endIndex") -> args.optInt("endIndex", -1)
                else -> -1
            }
            val anchor = args.optString("original_span_text", args.optString("originalSpanText", "")).trim()
            val (startIdx, endIdx) = reconcileSpan(liveClause.text, rawStart, rawEnd, anchor) ?: run {
                lastError = "替换范围无效"
                continue
            }

            when (
                val dispatchRes = toolBus.dispatch(
                    AgentTaskRole.VOICE_REVISION,
                    ToolRequest(
                        ToolId.REPLACE_SPAN,
                        mapOf(
                            "bookId" to bookId,
                            "clauseId" to targetClauseId,
                            "startIndex" to startIdx,
                            "endIndex" to endIdx,
                            "replacement" to replacement,
                        ),
                    ),
                )
            ) {
                is ToolResult.Success -> {
                    appliedCount++
                    dispatchRes.book?.let { latestBook = it }
                }
                is ToolResult.Denied -> lastError = dispatchRes.reason
                is ToolResult.Error -> lastError = dispatchRes.message
            }
        }

        return if (appliedCount > 0) {
            val updatedText = latestBook.clauses.firstOrNull { it.id == clauseId }?.text
            VoiceRevisionResult(applied = true, clauseId = clauseId, updatedText = updatedText)
        } else {
            VoiceRevisionResult(false, clauseId, null, lastError ?: "未生成有效修改")
        }
    }

    private fun buildMessages(
        bookTitle: String,
        allClauses: List<Clause>,
        focusClause: Clause,
        bible: BookBible,
        instruction: String,
    ): List<ChatMessage> {
        val indexed = focusClause.text.mapIndexed { i, ch -> "$i:$ch" }.joinToString(" ")
        val tokens = JiebaWordSegmenter.segment(focusClause.text)
            .joinToString(" ") { "[${it.startOffset},${it.endOffset}):${it.word}" }

        val sys = """
            你是儿童绘本的「AI调整」（局部语音修改）助手。
            禁止输出任何解释或普通文本，必须直接调用一次或多次函数工具 `replace_span` 完成精准修改（左闭右开区间 [start_index, end_index)）。
            默认优先修改小作者当前选中的焦点句子；但如果小作者明确要求同时修改相邻句子或全书多处（例如“把这几句里的蓝色都改成粉色”），可对对应 `clause_id` 连续多次调用 `replace_span`。
            每次调用必须提供 `original_span_text`（被替换的原文片段）以确保位置绝对准确，绝不改动无关内容。
        """.trimIndent()

        val focusIdx = allClauses.indexOfFirst { it.id == focusClause.id }.coerceAtLeast(0)
        val contextWindow = allClauses.subList(
            (focusIdx - 3).coerceAtLeast(0),
            (focusIdx + 4).coerceAtMost(allClauses.size),
        )

        val user = buildString {
            appendLine("故事标题: $bookTitle")
            if (bible.protagonistTraits.isNotEmpty()) {
                appendLine("主角设定: ${bible.protagonistTraits.joinToString("、")}")
            }
            appendLine("当前句子 ID: ${focusClause.id}")
            appendLine("当前句子原文: \"${focusClause.text}\"")
            appendLine("焦点句字符索引: $indexed")
            appendLine("焦点句分词边界: $tokens")
            if (contextWindow.size > 1) {
                appendLine("前后相邻句子（仅当指令明确涉及多句时才修改）:")
                for (c in contextWindow) {
                    appendLine("  - [${c.id}] ${c.text}")
                }
            }
            appendLine("小作者语音指令: \"$instruction\"")
        }

        return listOf(
            ChatMessage(role = "system", content = sys),
            ChatMessage(role = "user", content = user),
        )
    }

    private fun reconcileSpan(
        text: String,
        rawStart: Int,
        rawEnd: Int,
        anchor: String,
    ): Pair<Int, Int>? {
        val validRaw = rawStart in 0 until rawEnd && rawEnd <= text.length
        if (anchor.isNotEmpty()) {
            if (validRaw && text.substring(rawStart, rawEnd) == anchor) {
                return rawStart to rawEnd
            }
            val matches = findAllOccurrences(text, anchor)
            if (matches.isEmpty()) return null
            if (matches.size == 1) {
                val s = matches.first()
                return s to (s + anchor.length)
            }
            val refStart = rawStart.coerceAtLeast(0)
            val nearest = matches.minByOrNull { abs(it - refStart) }!!
            return nearest to (nearest + anchor.length)
        }
        return if (validRaw) rawStart to rawEnd else null
    }

    private fun parseJsonFallback(content: String?): JSONObject? {
        val raw = content?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            val obj = JSONObject(raw.substring(start, end + 1))
            if (obj.has("replacement") || obj.has("replacementWord")) obj else null
        }.getOrNull()
    }

    private fun findAllOccurrences(text: String, needle: String): List<Int> {
        val out = mutableListOf<Int>()
        var idx = text.indexOf(needle)
        while (idx >= 0) {
            out.add(idx)
            idx = text.indexOf(needle, idx + 1)
        }
        return out
    }

    companion object {
        private val REPLACE_SPAN_TOOL_SPEC = ChatToolSpec(
            name = ToolId.REPLACE_SPAN.toolName,
            description = "在指定句子中替换 [start_index, end_index) 区间文本。",
            parametersSchema = JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("clause_id", JSONObject().put("type", "string"))
                        .put("start_index", JSONObject().put("type", "integer"))
                        .put("end_index", JSONObject().put("type", "integer"))
                        .put("replacement", JSONObject().put("type", "string"))
                        .put("original_span_text", JSONObject().put("type", "string")),
                )
                .put("required", JSONArray().put("clause_id").put("start_index").put("end_index").put("replacement")),
        )
    }
}
