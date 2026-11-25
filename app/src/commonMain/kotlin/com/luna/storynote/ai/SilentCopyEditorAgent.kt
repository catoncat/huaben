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
import com.luna.storynote.model.ClausePhraseSpan
import com.luna.storynote.model.SoftAnnotation
import com.luna.storynote.model.StoryBook
import org.json.JSONArray
import org.json.JSONObject

data class SilentCopyEditResult(
    val annotatedClauses: Int,
    val bibleUpdated: Boolean,
)

class SilentCopyEditorAgent(
    private val aiClient: AiGatewayClient,
    private val toolBus: ToolBus,
) {
    fun reviewBook(
        config: AiGatewayConfig,
        bookId: String,
        targetClauseIds: List<String> = emptyList(),
    ): SilentCopyEditResult {
        if (!config.isConfigured) return SilentCopyEditResult(0, false)
        val bookRes = toolBus.dispatch(
            AgentTaskRole.SILENT_COPY_EDITOR,
            ToolRequest(ToolId.READ_BOOK_STATE, mapOf("bookId" to bookId)),
        ) as? ToolResult.Success ?: return SilentCopyEditResult(0, false)
        val book = bookRes.book ?: return SilentCopyEditResult(0, false)
        if (book.clauses.isEmpty()) return SilentCopyEditResult(0, false)

        val bibleRes = toolBus.dispatch(
            AgentTaskRole.SILENT_COPY_EDITOR,
            ToolRequest(ToolId.READ_BOOK_BIBLE, mapOf("bookId" to bookId)),
        ) as? ToolResult.Success
        val bible = bibleRes?.bible ?: BookBible(bookId = bookId)

        val targetSet = targetClauseIds.toSet()
        val messages = buildMessages(book, bible, targetSet)
        val resp = aiClient.chatWithTools(
            config = config,
            messages = messages,
            tools = listOf(ANNOTATE_TOOL_SPEC, UPDATE_BIBLE_TOOL_SPEC),
            temperature = 0.1,
            maxTokens = 1024,
        )

        val pendingByClause = linkedMapOf<String, MutableList<SoftAnnotation>>()
        val pendingPhrasesByClause = linkedMapOf<String, MutableList<ClausePhraseSpan>>()
        var bibleUpdated = false

        for (call in resp.toolCalls) {
            when (call.name) {
                ToolId.ANNOTATE_SOFT_SUGGESTION.toolName -> {
                    parseAndNormalizeAnnotation(call.arguments, book.clauses)?.let { (cid, ann) ->
                        pendingByClause.getOrPut(cid) { mutableListOf() }.add(ann)
                    }
                    parseContextualPhrases(call.arguments, book.clauses)?.let { (cid, phrases) ->
                        pendingPhrasesByClause.getOrPut(cid) { mutableListOf() }.addAll(phrases)
                    }
                }
                ToolId.UPDATE_BOOK_BIBLE.toolName -> {
                    if (applyBibleUpdate(bookId, call.arguments)) {
                        bibleUpdated = true
                    }
                }
            }
        }

        var annotatedCount = 0
        val targetCids = (pendingByClause.keys + pendingPhrasesByClause.keys).distinct()
        if (targetCids.isNotEmpty()) {
            val latestBook = (
                toolBus.dispatch(
                    AgentTaskRole.SILENT_COPY_EDITOR,
                    ToolRequest(ToolId.READ_BOOK_STATE, mapOf("bookId" to bookId)),
                ) as? ToolResult.Success
                )?.book
            if (latestBook != null) {
                for (cid in targetCids) {
                    val originalClause = book.clauses.firstOrNull { it.id == cid } ?: continue
                    val latestClause = latestBook.clauses.firstOrNull { it.id == cid } ?: continue
                    if (latestClause.text != originalClause.text) continue
                    val newAnns = pendingByClause[cid].orEmpty()
                    val newPhrases = pendingPhrasesByClause[cid].orEmpty()
                    val reqParams = mutableMapOf<String, Any?>("bookId" to bookId, "clauseId" to cid)
                    if (newAnns.isNotEmpty()) {
                        reqParams["annotations"] = mergeAnnotations(latestClause.softAnnotations, newAnns)
                    }
                    if (newPhrases.isNotEmpty()) {
                        reqParams["contextualPhrases"] = mergeContextualPhrases(latestClause.contextualPhrases, newPhrases)
                    }
                    val res = toolBus.dispatch(
                        AgentTaskRole.SILENT_COPY_EDITOR,
                        ToolRequest(ToolId.ANNOTATE_SOFT_SUGGESTION, reqParams),
                    )
                    if (res.isSuccess) annotatedCount++
                }
            }
        }

        return SilentCopyEditResult(annotatedClauses = annotatedCount, bibleUpdated = bibleUpdated)
    }

    private fun buildMessages(book: StoryBook, bible: BookBible, targetIds: Set<String>): List<ChatMessage> {
        val sys = """
            你是一位面向一年级儿童中文绘本的静默文字校对助手。
            你绝对不能修改原文，只能：
            1) 对明显同音错别字给出温和的 `annotate_soft_suggestion`（char_index 与 length 必须严格匹配下方字符索引或词边界，suggested_word 不能与原文相同）；
            2) 对语境中的多音字给出准确的带声调拼音 `corrected_pinyin`（如 zhī, zuò, lè）；
            3) 若下方 Jieba 分词将适合儿童整体认读的词组拆散（如“小/恐龙”“大/森林”“慢慢/的”“走/啊/走”），在同一工具中通过 `contextual_phrases`（start_offset, end_offset, word）补充完整词组边界（word 必须严格等于原文切片）；
            4) 用 `update_book_bible` 增量提炼主角固定视觉特征（protagonist_traits）、关键场景道具（key_scenes_and_props）和简短剧情摘要（plot_summary）。
            若无错别字、分词已合理且无需更新，不要调用多余工具。
        """.trimIndent()

        val clausesText = buildString {
            appendLine("书名: ${book.title}")
            appendLine("现有 BookBible:")
            appendLine("- protagonist_traits: ${bible.protagonistTraits.joinToString("、")}")
            appendLine("- key_scenes_and_props: ${bible.keyScenesAndProps.joinToString("、")}")
            appendLine("- plot_summary: ${bible.plotSummary}")
            appendLine("故事分句:")
            for (c in book.clauses) {
                val mark = if (targetIds.isEmpty() || c.id in targetIds) "*" else ""
                val indexed = c.text.mapIndexed { i, ch -> "$i:$ch" }.joinToString(" ")
                val tokens = JiebaWordSegmenter.segment(c.text)
                    .joinToString(" ") { "[${it.startOffset},${it.endOffset}):${it.word}" }
                appendLine("$mark[${c.id}] 原文=\"${c.text}\"")
                appendLine("  字符索引: $indexed")
                appendLine("  Jieba分词: $tokens")
            }
        }

        return listOf(
            ChatMessage(role = "system", content = sys),
            ChatMessage(role = "user", content = clausesText),
        )
    }

    private fun parseAndNormalizeAnnotation(
        args: JSONObject,
        clauses: List<Clause>,
    ): Pair<String, SoftAnnotation>? {
        val clauseId = args.optString("clause_id", "").ifBlank { args.optString("clauseId", "") }.trim()
        val clause = clauses.firstOrNull { it.id == clauseId } ?: return null
        val rawIdx = when {
            args.has("char_index") -> args.optInt("char_index", -1)
            args.has("charIndex") -> args.optInt("charIndex", -1)
            else -> -1
        }
        val rawLen = args.optInt("length", 1).coerceAtLeast(1)
        if (rawIdx !in clause.text.indices || rawIdx + rawLen > clause.text.length) return null

        var charIndex = rawIdx
        var length = rawLen
        var suggested = args.optString("suggested_word", args.optString("suggestedWord", ""))
            .trim()
            .takeIf { it.isNotEmpty() }
        var pinyin = args.optString("corrected_pinyin", args.optString("correctedPinyin", ""))
            .trim()
            .takeIf { it.isNotEmpty() }
        val reason = args.optString("reason", "").trim().takeIf { it.isNotEmpty() }

        var orig = clause.text.substring(charIndex, charIndex + length)
        if (suggested == orig) suggested = null

        // Shrink multi-char suggestion with exactly 1 differing char down to length == 1
        val curSuggested = suggested
        if (curSuggested != null && length > 1 && curSuggested.length == orig.length) {
            val diffs = (0 until orig.length).filter { orig[it] != curSuggested[it] }
            if (diffs.size == 1) {
                val d = diffs.first()
                charIndex += d
                length = 1
                suggested = curSuggested[d].toString()
                pinyin = pinyin?.split(Regex("\\s+"))?.let { parts ->
                    if (parts.size == orig.length) parts[d] else null
                }
                orig = clause.text.substring(charIndex, charIndex + 1)
            }
        }

        // Enforce alignment: either single char (length == 1) or exact Jieba WordTokenSpan bounds
        if (length > 1) {
            val tokens = JiebaWordSegmenter.segment(clause.text)
            val aligned = tokens.any { it.startOffset == charIndex && it.endOffset == charIndex + length }
            if (!aligned) return null
            if (pinyin != null && pinyin.split(Regex("\\s+")).size != length) {
                pinyin = null
            }
        }

        if (suggested == orig) suggested = null
        if (suggested == null && pinyin == null) return null

        return clauseId to SoftAnnotation(
            charIndex = charIndex,
            length = length,
            suggestedWord = suggested,
            correctedPinyin = pinyin,
            reason = reason,
        )
    }

    private fun parseContextualPhrases(
        args: JSONObject,
        clauses: List<Clause>,
    ): Pair<String, List<ClausePhraseSpan>>? {
        val clauseId = args.optString("clause_id", "").ifBlank { args.optString("clauseId", "") }.trim()
        val clause = clauses.firstOrNull { it.id == clauseId } ?: return null
        val arr = args.optJSONArray("contextual_phrases") ?: args.optJSONArray("contextualPhrases") ?: return null
        val phrases = (0 until arr.length()).mapNotNull { idx ->
            val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
            val start = if (obj.has("start_offset")) obj.optInt("start_offset", -1) else obj.optInt("startOffset", -1)
            val end = if (obj.has("end_offset")) obj.optInt("end_offset", -1) else obj.optInt("endOffset", -1)
            val word = obj.optString("word", "").trim()
            if (start in clause.text.indices &&
                end in (start + 2)..clause.text.length &&
                word.length >= 2 &&
                clause.text.substring(start, end) == word
            ) {
                ClausePhraseSpan(startOffset = start, endOffset = end, word = word)
            } else {
                null
            }
        }
        return if (phrases.isNotEmpty()) clauseId to phrases else null
    }

    private fun mergeContextualPhrases(
        existing: List<ClausePhraseSpan>,
        incoming: List<ClausePhraseSpan>,
    ): List<ClausePhraseSpan> {
        val accepted = mutableListOf<ClausePhraseSpan>()
        for (p in incoming) {
            val overlaps = accepted.any { a -> p.startOffset < a.endOffset && p.endOffset > a.startOffset }
            if (!overlaps) accepted.add(p)
        }
        val kept = existing.filter { old ->
            accepted.none { nw -> old.startOffset < nw.endOffset && old.endOffset > nw.startOffset }
        }
        return (kept + accepted)
            .distinctBy { Triple(it.startOffset, it.endOffset, it.word) }
            .sortedBy { it.startOffset }
    }

    private fun mergeAnnotations(
        existing: List<SoftAnnotation>,
        incoming: List<SoftAnnotation>,
    ): List<SoftAnnotation> {
        val acceptedIncoming = mutableListOf<SoftAnnotation>()
        for (ann in incoming) {
            val annEnd = ann.charIndex + ann.length
            val overlapsAccepted = acceptedIncoming.any { acc ->
                val accEnd = acc.charIndex + acc.length
                ann.charIndex < accEnd && annEnd > acc.charIndex
            }
            if (!overlapsAccepted) {
                acceptedIncoming.add(ann)
            }
        }
        val kept = existing.filter { old ->
            val oldEnd = old.charIndex + old.length
            acceptedIncoming.none { nw ->
                val newEnd = nw.charIndex + nw.length
                old.charIndex < newEnd && oldEnd > nw.charIndex
            }
        }
        return (kept + acceptedIncoming)
            .distinctBy { Triple(it.charIndex, it.length, it.suggestedWord to it.correctedPinyin) }
            .sortedBy { it.charIndex }
    }

    private fun applyBibleUpdate(bookId: String, args: JSONObject): Boolean {
        val traits = readStringArray(args, "protagonist_traits", "protagonistTraits")
        val scenes = readStringArray(args, "key_scenes_and_props", "keyScenesAndProps")
        val plot = args.optString("plot_summary", args.optString("plotSummary", "")).trim()
            .takeIf { it.isNotEmpty() }
        if (traits.isEmpty() && scenes.isEmpty() && plot == null) return false
        val res = toolBus.dispatch(
            AgentTaskRole.SILENT_COPY_EDITOR,
            ToolRequest(
                ToolId.UPDATE_BOOK_BIBLE,
                mapOf(
                    "bookId" to bookId,
                    "protagonistTraits" to traits,
                    "keyScenesAndProps" to scenes,
                    "plotSummary" to plot,
                ),
            ),
        )
        return res.isSuccess
    }

    private fun readStringArray(obj: JSONObject, vararg keys: String): List<String> {
        for (k in keys) {
            val arr = obj.optJSONArray(k) ?: continue
            return (0 until arr.length())
                .mapNotNull { arr.optString(it, "").trim().takeIf(String::isNotEmpty) }
        }
        return emptyList()
    }

    companion object {
        private val ANNOTATE_TOOL_SPEC = ChatToolSpec(
            name = ToolId.ANNOTATE_SOFT_SUGGESTION.toolName,
            description = "在指定句子上添加轻量级同音字提示、多音字校正或儿童语境词组边界，不改动原文。",
            parametersSchema = JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("clause_id", JSONObject().put("type", "string"))
                        .put("char_index", JSONObject().put("type", "integer"))
                        .put("length", JSONObject().put("type", "integer"))
                        .put("suggested_word", JSONObject().put("type", "string"))
                        .put("corrected_pinyin", JSONObject().put("type", "string"))
                        .put("reason", JSONObject().put("type", "string"))
                        .put(
                            "contextual_phrases",
                            JSONObject()
                                .put("type", "array")
                                .put(
                                    "items",
                                    JSONObject()
                                        .put("type", "object")
                                        .put(
                                            "properties",
                                            JSONObject()
                                                .put("start_offset", JSONObject().put("type", "integer"))
                                                .put("end_offset", JSONObject().put("type", "integer"))
                                                .put("word", JSONObject().put("type", "string")),
                                        )
                                        .put("required", JSONArray().put("start_offset").put("end_offset").put("word")),
                                ),
                        ),
                )
                .put("required", JSONArray().put("clause_id")),
        )

        private val UPDATE_BIBLE_TOOL_SPEC = ChatToolSpec(
            name = ToolId.UPDATE_BOOK_BIBLE.toolName,
            description = "增量更新绘本设定集（主角特征、关键场景道具、剧情摘要）。",
            parametersSchema = JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("protagonist_traits", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                        .put("key_scenes_and_props", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                        .put("plot_summary", JSONObject().put("type", "string")),
                ),
        )
    }
}
