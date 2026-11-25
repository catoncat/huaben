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

        val messages = buildMessages(book.title, clause, bible, cleanInstruction)
        val resp = aiClient.chatWithTools(
            config = config,
            messages = messages,
            tools = listOf(REPLACE_SPAN_TOOL_SPEC),
            temperature = 0.1,
            maxTokens = 512,
            toolChoice = "required",
        )

        val args = resp.toolCalls.firstOrNull { it.name == ToolId.REPLACE_SPAN.toolName }?.arguments
            ?: parseJsonFallback(resp.content)
            ?: return VoiceRevisionResult(false, clauseId, null, "未生成替换操作")
        val targetClauseId = args.optString("clause_id", args.optString("clauseId", clauseId))
            .trim()
            .ifEmpty { clauseId }
        if (targetClauseId != clauseId) {
            return VoiceRevisionResult(false, clauseId, null, "替换目标超出当前句子")
        }

        val replacement = args.optString("replacement", args.optString("replacementWord", "")).trim()
        if (replacement.isEmpty()) {
            return VoiceRevisionResult(false, clauseId, null, "替换内容为空")
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
        val (startIdx, endIdx) = reconcileSpan(clause.text, rawStart, rawEnd, anchor)
            ?: return VoiceRevisionResult(false, clauseId, null, "替换范围无效")

        val dispatchRes = toolBus.dispatch(
            AgentTaskRole.VOICE_REVISION,
            ToolRequest(
                ToolId.REPLACE_SPAN,
                mapOf(
                    "bookId" to bookId,
                    "clauseId" to clauseId,
                    "startIndex" to startIdx,
                    "endIndex" to endIdx,
                    "replacement" to replacement,
                ),
            ),
        )
        return when (dispatchRes) {
            is ToolResult.Success -> {
                val updatedText = dispatchRes.book?.clauses?.firstOrNull { it.id == clauseId }?.text
                VoiceRevisionResult(applied = true, clauseId = clauseId, updatedText = updatedText)
            }
            is ToolResult.Denied -> VoiceRevisionResult(false, clauseId, null, dispatchRes.reason)
            is ToolResult.Error -> VoiceRevisionResult(false, clauseId, null, dispatchRes.message)
        }
    }

    private fun buildMessages(
        bookTitle: String,
        clause: Clause,
        bible: BookBible,
        instruction: String,
    ): List<ChatMessage> {
        val indexed = clause.text.mapIndexed { i, ch -> "$i:$ch" }.joinToString(" ")
        val tokens = JiebaWordSegmenter.segment(clause.text)
            .joinToString(" ") { "[${it.startOffset},${it.endOffset}):${it.word}" }

        val sys = """
            你是儿童绘本的局部语音修改助手。
            禁止输出任何解释或普通文本，必须直接调用函数工具 `replace_span`，在当前指定句子内做精准局部替换（左闭右开区间 [start_index, end_index)）。
            请同时提供 `original_span_text`（被替换的原文片段）以确保位置绝对准确。
            不要重写整句无关部分，也不要修改其他句子。
        """.trimIndent()

        val user = buildString {
            appendLine("故事标题: $bookTitle")
            if (bible.protagonistTraits.isNotEmpty()) {
                appendLine("主角设定: ${bible.protagonistTraits.joinToString("、")}")
            }
            appendLine("当前句子 ID: ${clause.id}")
            appendLine("当前句子原文: \"${clause.text}\"")
            appendLine("字符索引: $indexed")
            appendLine("Jieba分词边界: $tokens")
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
