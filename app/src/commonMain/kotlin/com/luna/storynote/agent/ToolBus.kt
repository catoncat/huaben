package com.luna.storynote.agent

import com.luna.storynote.model.BookBible
import com.luna.storynote.model.ClausePhraseSpan
import com.luna.storynote.model.SoftAnnotation
import com.luna.storynote.model.StoryBook
import com.luna.storynote.storage.StoryBookRepository
import java.io.File

data class ToolRequest(
    val toolId: ToolId,
    val params: Map<String, Any?> = emptyMap(),
)

sealed interface ToolResult {
    val toolId: ToolId
    val isSuccess: Boolean

    data class Success(
        override val toolId: ToolId,
        val data: Map<String, Any?> = emptyMap(),
        val book: StoryBook? = null,
        val bible: BookBible? = null,
    ) : ToolResult {
        override val isSuccess: Boolean = true
    }

    data class Denied(
        override val toolId: ToolId,
        val role: AgentTaskRole,
        val reason: String,
    ) : ToolResult {
        override val isSuccess: Boolean = false
    }

    data class Error(
        override val toolId: ToolId,
        val message: String,
    ) : ToolResult {
        override val isSuccess: Boolean = false
    }
}

class ToolBus @JvmOverloads constructor(
    private val repo: StoryBookRepository,
    private val onBookMutated: ((bookId: String, result: ToolResult.Success) -> Unit)? = null,
) {
    fun dispatch(role: AgentTaskRole, request: ToolRequest): ToolResult {
        val toolId = request.toolId
        if (!ToolWhitelists.isAllowed(role, toolId)) {
            return ToolResult.Denied(
                toolId = toolId,
                role = role,
                reason = "Tool ${toolId.toolName} is not permitted for $role",
            )
        }
        return try {
            val res = execute(toolId, request.params)
            if (res is ToolResult.Success && toolId in MUTATING_BOOK_TOOLS) {
                strParam(request.params, "bookId")?.let { onBookMutated?.invoke(it, res) }
            }
            res
        } catch (e: Exception) {
            ToolResult.Error(toolId, e.message ?: "Tool execution failed")
        }
    }

    private fun execute(toolId: ToolId, params: Map<String, Any?>): ToolResult {
        val bookId = strParam(params, "bookId")
            ?: return ToolResult.Error(toolId, "Missing required parameter: bookId")
        if (!repo.isValidBookId(bookId)) {
            return ToolResult.Error(toolId, "Invalid bookId: $bookId")
        }

        return when (toolId) {
            ToolId.READ_BOOK_STATE -> {
                val book = repo.getBook(bookId) ?: return ToolResult.Error(toolId, "Book not found: $bookId")
                ToolResult.Success(toolId, data = mapOf("book" to book, "clauses" to book.clauses, "pages" to book.paginate(), "takes" to book.takes), book = book)
            }

            ToolId.READ_PAGE -> {
                val book = repo.getBook(bookId) ?: return ToolResult.Error(toolId, "Book not found: $bookId")
                val pageIndex = intParam(params, "pageIndex") ?: 0
                val pages = book.paginate()
                if (pageIndex !in pages.indices) return ToolResult.Error(toolId, "Invalid pageIndex: $pageIndex")
                val page = pages[pageIndex]
                ToolResult.Success(
                    toolId = toolId,
                    data = mapOf("page" to page, "illustration" to book.activeIllustrationFor(pageIndex), "clauses" to page.paragraphs.flatMap { it.clauses }),
                    book = book,
                )
            }

            ToolId.READ_BOOK_BIBLE -> {
                repo.getBook(bookId) ?: return ToolResult.Error(toolId, "Book not found: $bookId")
                val bible = repo.bookBibles.read(bookId)
                ToolResult.Success(toolId = toolId, data = mapOf("bible" to bible), bible = bible)
            }

            ToolId.APPEND_CLAUSE -> insertClause(toolId, bookId, afterClauseId = null, params = params, label = "写下新句子")

            ToolId.INSERT_CLAUSE_AFTER -> {
                val afterId = strParam(params, "afterClauseId")
                insertClause(toolId, bookId, afterClauseId = afterId, params = params, label = "插入新句子")
            }

            ToolId.REPLACE_SPAN -> executeReplaceSpan(toolId, bookId, params)

            ToolId.EDIT_FULL_TEXT -> executeEditFullText(toolId, bookId, params)

            ToolId.RERECORD_CLAUSE_AUDIO -> executeRerecordClause(toolId, bookId, params)

            ToolId.ERASE_CLAUSE -> {
                val clauseId = strParam(params, "clauseId", "clause_id")
                    ?: return ToolResult.Error(toolId, "Missing required parameter: clauseId")
                val res = repo.removeClause(bookId, clauseId)
                    ?: return ToolResult.Error(toolId, "Book not found: $bookId")
                val (updated, removed) = res
                if (removed == null) {
                    return ToolResult.Error(toolId, "Clause not found: $clauseId")
                }
                ToolResult.Success(toolId, data = mapOf("removedClause" to removed), book = updated)
            }

            ToolId.TOGGLE_PARAGRAPH_BREAK -> {
                val clauseId = strParam(params, "clauseId", "clause_id")
                    ?: return ToolResult.Error(toolId, "Missing required parameter: clauseId")
                val existing = repo.getBook(bookId) ?: return ToolResult.Error(toolId, "Book not found: $bookId")
                if (existing.clauses.none { it.id == clauseId }) {
                    return ToolResult.Error(toolId, "Clause not found: $clauseId")
                }
                val updated = repo.toggleParagraphBreak(bookId, clauseId)
                    ?: return ToolResult.Error(toolId, "Book not found: $bookId")
                ToolResult.Success(toolId, book = updated)
            }

            ToolId.RESTORE_HISTORY_SNAPSHOT -> {
                val snapshotId = strParam(params, "snapshotId")
                    ?: return ToolResult.Error(toolId, "Missing required parameter: snapshotId")
                val clauseId = strParam(params, "clauseId", "clause_id")
                val updated = if (clauseId != null) {
                    repo.restoreClauseFromSnapshot(bookId, snapshotId, clauseId)
                } else {
                    repo.restoreSnapshot(bookId, snapshotId)
                } ?: return ToolResult.Error(toolId, "Snapshot or clause not found")
                ToolResult.Success(toolId, book = updated)
            }

            ToolId.ANNOTATE_SOFT_SUGGESTION -> executeAnnotateSoft(toolId, bookId, params)

            ToolId.UPDATE_BOOK_BIBLE -> {
                repo.getBook(bookId) ?: return ToolResult.Error(toolId, "Book not found: $bookId")
                val traits = strListParam(params, "protagonistTraits", "protagonist_traits")
                val scenes = strListParam(params, "keyScenesAndProps", "key_scenes_and_props")
                val plot = strParam(params, "plotSummary", "plot_summary")
                val bible = repo.bookBibles.mergeUpdate(bookId, traits, scenes, plot)
                ToolResult.Success(toolId, data = mapOf("bible" to bible), bible = bible)
            }

            ToolId.ATTACH_PAGE_ILLUSTRATION -> executeAttachIllustration(toolId, bookId, params)

            ToolId.SELECT_PAGE_ILLUSTRATION -> {
                val pageIndex = intParam(params, "pageIndex")
                    ?: return ToolResult.Error(toolId, "Missing required parameter: pageIndex")
                val illustrationId = strParam(params, "illustrationId")
                    ?: return ToolResult.Error(toolId, "Missing required parameter: illustrationId")
                val existing = repo.getBook(bookId) ?: return ToolResult.Error(toolId, "Book not found: $bookId")
                if (existing.illustrations.none { it.id == illustrationId && it.pageIndex == pageIndex }) {
                    return ToolResult.Error(toolId, "Illustration not found: $illustrationId on page $pageIndex")
                }
                val updated = repo.selectIllustration(bookId, pageIndex, illustrationId)
                    ?: return ToolResult.Error(toolId, "Book not found: $bookId")
                ToolResult.Success(toolId, book = updated)
            }

            ToolId.ADD_WORD_BOX_CARD -> executeAddWordBox(toolId, bookId, params)
        }
    }

    private fun insertClause(
        toolId: ToolId,
        bookId: String,
        afterClauseId: String?,
        params: Map<String, Any?>,
        label: String,
    ): ToolResult {
        val text = strParam(params, "text", "rawText")
            ?: return ToolResult.Error(toolId, "Missing required parameter: text")
        if (text.isBlank()) return ToolResult.Error(toolId, "Text cannot be blank")
        val book = repo.getBook(bookId) ?: return ToolResult.Error(toolId, "Book not found: $bookId")
        val takeId = strParam(params, "takeId").orEmpty()
        val takeFile = resolveToolFile(params, book.bookDir, allowStaging = true, "takeFile", "wavFile")
        val uttStartMs = longParam(params, "uttStartMs") ?: 0L
        val uttEndMs = longParam(params, "uttEndMs") ?: 0L
        val takeDurMs = longParam(params, "takeDurationMs") ?: 0L
        val paraBreak = boolParam(params, "paragraphBreakAfter", "forceParagraphBreak") ?: false
        val (updated, inserted) = repo.insertUtteranceWithClauses(
            bookId = bookId,
            afterClauseId = afterClauseId,
            rawText = text,
            takeId = takeId,
            takeWavFile = takeFile,
            uttStartMs = uttStartMs,
            uttEndMs = uttEndMs,
            takeDurationMs = takeDurMs,
            forceParagraphBreak = paraBreak,
            actionLabel = label,
            toolName = toolId.toolName,
        )
        return ToolResult.Success(toolId, data = mapOf("insertedClauses" to inserted), book = updated)
    }

    private fun executeReplaceSpan(toolId: ToolId, bookId: String, params: Map<String, Any?>): ToolResult {
        val clauseId = strParam(params, "clauseId", "clause_id")
            ?: return ToolResult.Error(toolId, "Missing required parameter: clauseId")
        val startIdx = intParam(params, "startIndex", "startIdx", "start_index")
            ?: return ToolResult.Error(toolId, "Missing required parameter: startIndex")
        val endIdx = intParam(params, "endIndex", "endIdx", "end_index")
            ?: return ToolResult.Error(toolId, "Missing required parameter: endIndex")
        val replacement = strParam(params, "replacement", "replacementWord")
            ?: return ToolResult.Error(toolId, "Missing required parameter: replacement")
        val cleanWord = replacement.trim().trim('。', '，', '！', '？', '.', ',', '!', '?')
        if (cleanWord.isEmpty()) {
            return ToolResult.Error(toolId, "Replacement text cannot be empty")
        }
        val existing = repo.getBook(bookId) ?: return ToolResult.Error(toolId, "Book not found: $bookId")
        val clause = existing.clauses.firstOrNull { it.id == clauseId }
            ?: return ToolResult.Error(toolId, "Clause not found: $clauseId")
        if (startIdx < 0 || endIdx > clause.text.length || startIdx >= endIdx) {
            return ToolResult.Error(toolId, "Invalid span [$startIdx, $endIdx) for clause length ${clause.text.length}")
        }
        val updated = repo.replaceRangeInClause(
            bookId = bookId,
            clauseId = clauseId,
            startIdx = startIdx,
            endIdx = endIdx,
            replacementWord = replacement,
            actionLabel = "修改词语",
            toolName = toolId.toolName,
        ) ?: return ToolResult.Error(toolId, "Book not found: $bookId")
        return ToolResult.Success(toolId, book = updated)
    }

    private fun executeEditFullText(toolId: ToolId, bookId: String, params: Map<String, Any?>): ToolResult {
        val fullText = strParam(params, "fullText", "full_text", "text")
            ?: return ToolResult.Error(toolId, "Missing required parameter: fullText")
        val existing = repo.getBook(bookId) ?: return ToolResult.Error(toolId, "Book not found: $bookId")
        val title = strParam(params, "title", "newTitle") ?: existing.title
        val res = repo.applyFullTextEdit(bookId, title, fullText)
            ?: return ToolResult.Error(toolId, "Book not found: $bookId")
        val (updated, changed) = res
        return ToolResult.Success(toolId, data = mapOf("changed" to changed), book = updated)
    }

    private fun executeRerecordClause(toolId: ToolId, bookId: String, params: Map<String, Any?>): ToolResult {
        val clauseId = strParam(params, "clauseId", "clause_id")
            ?: return ToolResult.Error(toolId, "Missing required parameter: clauseId")
        val text = strParam(params, "text", "newRawText")
            ?: return ToolResult.Error(toolId, "Missing required parameter: text")
        if (text.isBlank()) {
            return ToolResult.Error(toolId, "Clause text cannot be blank")
        }
        val existing = repo.getBook(bookId) ?: return ToolResult.Error(toolId, "Book not found: $bookId")
        if (existing.clauses.none { it.id == clauseId }) {
            return ToolResult.Error(toolId, "Clause not found: $clauseId")
        }
        val takeId = strParam(params, "takeId").orEmpty()
        val takeFile = resolveToolFile(params, existing.bookDir, allowStaging = true, "takeFile", "wavFile")
        val updated = repo.replaceClauseWithTake(
            bookId = bookId,
            clauseId = clauseId,
            newRawText = text,
            takeId = takeId,
            takeWavFile = takeFile,
            uttStartMs = longParam(params, "uttStartMs") ?: 0L,
            uttEndMs = longParam(params, "uttEndMs") ?: 0L,
            takeDurationMs = longParam(params, "takeDurationMs") ?: 0L,
        ) ?: return ToolResult.Error(toolId, "Book not found: $bookId")
        return ToolResult.Success(toolId, book = updated)
    }

    private fun executeAnnotateSoft(toolId: ToolId, bookId: String, params: Map<String, Any?>): ToolResult {
        val clauseId = strParam(params, "clauseId", "clause_id")
            ?: return ToolResult.Error(toolId, "Missing required parameter: clauseId")
        val existing = repo.getBook(bookId) ?: return ToolResult.Error(toolId, "Book not found: $bookId")
        val clause = existing.clauses.firstOrNull { it.id == clauseId }
            ?: return ToolResult.Error(toolId, "Clause not found: $clauseId")
        val rawAnnotations = parseAnnotations(params)
        val rawPhrases = parseContextualPhrases(params, clause.text)
        if (rawAnnotations == null && rawPhrases == null) {
            return ToolResult.Error(toolId, "Missing soft annotations payload")
        }
        val annotations = rawAnnotations?.filter { ann ->
            ann.charIndex in clause.text.indices &&
                ann.length > 0 &&
                ann.charIndex + ann.length <= clause.text.length
        }
        if (rawAnnotations != null && rawAnnotations.isNotEmpty() && annotations.isNullOrEmpty()) {
            return ToolResult.Error(toolId, "Soft annotation bounds out of range for clause length ${clause.text.length}")
        }
        val updated = repo.annotateClauseSoftSuggestions(
            bookId = bookId,
            clauseId = clauseId,
            annotations = annotations ?: clause.softAnnotations,
            contextualPhrases = rawPhrases ?: clause.contextualPhrases,
        ) ?: return ToolResult.Error(toolId, "Book not found: $bookId")
        val resData = buildMap<String, Any?> {
            if (annotations != null) put("annotations", annotations)
            if (rawPhrases != null) put("contextualPhrases", rawPhrases)
        }
        return ToolResult.Success(toolId, data = resData, book = updated)
    }

    private fun parseContextualPhrases(params: Map<String, Any?>, clauseText: String): List<ClausePhraseSpan>? {
        val hasKey = params.containsKey("contextualPhrases") || params.containsKey("contextual_phrases")
        if (!hasKey) return null
        val rawList = (params["contextualPhrases"] ?: params["contextual_phrases"]) as? List<*> ?: return emptyList()
        val valid = rawList.mapNotNull { item ->
            val span = when (item) {
                is ClausePhraseSpan -> item
                is Map<*, *> -> {
                    val map = item.entries.associate { (k, v) -> k.toString() to v }
                    val start = intParam(map, "startOffset", "start_offset") ?: -1
                    val end = intParam(map, "endOffset", "end_offset") ?: -1
                    val word = strParam(map, "word")?.trim().orEmpty()
                    ClausePhraseSpan(startOffset = start, endOffset = end, word = word)
                }
                else -> null
            } ?: return@mapNotNull null
            if (span.startOffset in clauseText.indices &&
                span.endOffset in (span.startOffset + 1)..clauseText.length &&
                span.word.length >= 2 &&
                clauseText.substring(span.startOffset, span.endOffset) == span.word
            ) {
                span
            } else {
                null
            }
        }
        return valid.distinctBy { it.startOffset to it.endOffset }.sortedBy { it.startOffset }
    }

    private fun parseAnnotations(params: Map<String, Any?>): List<SoftAnnotation>? {
        val rawList = params["annotations"] as? List<*>
        if (rawList != null) {
            return rawList.mapNotNull { item ->
                when (item) {
                    is SoftAnnotation -> item
                    is Map<*, *> -> {
                        val map = item.entries.associate { (k, v) -> k.toString() to v }
                        SoftAnnotation(
                            charIndex = intParam(map, "charIndex", "char_index") ?: 0,
                            length = (intParam(map, "length") ?: 1).coerceAtLeast(1),
                            suggestedWord = strParam(map, "suggestedWord", "suggested_word"),
                            correctedPinyin = strParam(map, "correctedPinyin", "corrected_pinyin"),
                            reason = strParam(map, "reason"),
                        )
                    }
                    else -> null
                }
            }
        }
        val charIndex = intParam(params, "charIndex", "char_index") ?: return null
        return listOf(
            SoftAnnotation(
                charIndex = charIndex,
                length = (intParam(params, "length") ?: 1).coerceAtLeast(1),
                suggestedWord = strParam(params, "suggestedWord", "suggested_word"),
                correctedPinyin = strParam(params, "correctedPinyin", "corrected_pinyin"),
                reason = strParam(params, "reason"),
            ),
        )
    }

    private fun executeAttachIllustration(toolId: ToolId, bookId: String, params: Map<String, Any?>): ToolResult {
        val pageIndex = intParam(params, "pageIndex") ?: 0
        val promptUsed = strParam(params, "promptUsed").orEmpty()
        val book = repo.getBook(bookId) ?: return ToolResult.Error(toolId, "Book not found: $bookId")
        val imageFile = resolveToolFile(params, book.bookDir, allowStaging = true, "imageFile", "imageFileName")
            ?: return ToolResult.Error(toolId, "Missing required parameter: imageFile or imageFileName")
        if (!imageFile.isFile) {
            return ToolResult.Error(toolId, "Illustration file does not exist: ${imageFile.name}")
        }
        val updated = repo.attachIllustration(bookId, pageIndex, imageFile, promptUsed)
            ?: return ToolResult.Error(toolId, "Book not found: $bookId")
        return ToolResult.Success(toolId, book = updated)
    }

    private fun executeAddWordBox(toolId: ToolId, bookId: String, params: Map<String, Any?>): ToolResult {
        val clauseId = strParam(params, "clauseId", "clause_id")
            ?: return ToolResult.Error(toolId, "Missing required parameter: clauseId")
        val word = strParam(params, "word")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.Error(toolId, "Missing required parameter: word")
        val pinyins = strListParam(params, "pinyins")
        val book = repo.getBook(bookId) ?: return ToolResult.Error(toolId, "Book not found: $bookId")
        val clause = book.clauses.firstOrNull { it.id == clauseId }
            ?: return ToolResult.Error(toolId, "Clause not found: $clauseId")
        val (words, added) = repo.addWordBoxCard(word, pinyins, book, clause)
        return ToolResult.Success(
            toolId = toolId,
            data = mapOf("collectedWords" to words, "added" to added),
            book = book,
        )
    }

    private fun strParam(params: Map<String, Any?>, vararg keys: String): String? {
        for (k in keys) {
            val v = params[k] ?: continue
            if (v is String) return v
        }
        return null
    }

    private fun intParam(params: Map<String, Any?>, vararg keys: String): Int? {
        for (k in keys) {
            when (val v = params[k]) {
                is Number -> return v.toInt()
                is String -> v.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun longParam(params: Map<String, Any?>, vararg keys: String): Long? {
        for (k in keys) {
            when (val v = params[k]) {
                is Number -> return v.toLong()
                is String -> v.toLongOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun boolParam(params: Map<String, Any?>, vararg keys: String): Boolean? {
        for (k in keys) {
            when (val v = params[k]) {
                is Boolean -> return v
                is String -> v.toBooleanStrictOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun strListParam(params: Map<String, Any?>, vararg keys: String): List<String> {
        for (k in keys) {
            val v = params[k] as? Collection<*> ?: continue
            return v.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        }
        return emptyList()
    }

    private fun resolveToolFile(
        params: Map<String, Any?>,
        bookDir: File,
        allowStaging: Boolean,
        vararg keys: String,
    ): File? {
        val bookRoot = bookDir.canonicalFile.toPath()
        for (k in keys) {
            when (val v = params[k]) {
                is File -> {
                    val canonical = v.canonicalFile.toPath()
                    val inBook = canonical.startsWith(bookRoot) && canonical != bookRoot
                    val inStaging = allowStaging && repo.isInStagingDir(v)
                    if (!inBook && !inStaging) {
                        throw IllegalArgumentException("File path must be inside book directory")
                    }
                    return v
                }
                is String -> if (v.isNotBlank()) {
                    if (File(v).isAbsolute || v.startsWith("/") || v.startsWith("\\") ||
                        v.contains("..") || v.indexOf('\u0000') >= 0
                    ) {
                        throw IllegalArgumentException("Invalid relative file path: $v")
                    }
                    val candidate = File(bookDir, v)
                    val canonical = candidate.canonicalFile.toPath()
                    if (!canonical.startsWith(bookRoot) || canonical == bookRoot) {
                        throw IllegalArgumentException("File path escapes book directory: $v")
                    }
                    return candidate
                }
            }
        }
        return null
    }

    companion object {
        private val MUTATING_BOOK_TOOLS = setOf(
            ToolId.APPEND_CLAUSE, ToolId.INSERT_CLAUSE_AFTER, ToolId.REPLACE_SPAN, ToolId.EDIT_FULL_TEXT,
            ToolId.RERECORD_CLAUSE_AUDIO, ToolId.ERASE_CLAUSE, ToolId.TOGGLE_PARAGRAPH_BREAK,
            ToolId.RESTORE_HISTORY_SNAPSHOT, ToolId.ANNOTATE_SOFT_SUGGESTION,
            ToolId.UPDATE_BOOK_BIBLE, ToolId.ATTACH_PAGE_ILLUSTRATION, ToolId.SELECT_PAGE_ILLUSTRATION,
        )
    }
}
