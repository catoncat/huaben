package com.luna.storynote.storage

import com.luna.storynote.model.AiGatewayConfig
import com.luna.storynote.model.AudioSpan
import com.luna.storynote.model.AudioTake
import com.luna.storynote.model.BookBible
import com.luna.storynote.model.BookIllustration
import com.luna.storynote.model.Clause
import com.luna.storynote.model.ClausePhraseSpan
import com.luna.storynote.model.SoftAnnotation
import com.luna.storynote.model.StoryBook
import com.luna.storynote.model.TimeMachineSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object BookJsonCodec {
    fun atomicWriteText(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    fun encodeTake(take: AudioTake): JSONObject =
        JSONObject()
            .put("id", take.id)
            .put("wav_file", take.wavFileName)
            .put("duration_ms", take.durationMs)
            .put("created_at_ms", take.createdAtEpochMs)

    fun decodeTake(o: JSONObject): AudioTake? {
        val id = o.optString("id", "").trim()
        val wav = o.optString("wav_file", "").trim()
        if (id.isEmpty() || wav.isEmpty()) return null
        return AudioTake(
            id = id,
            wavFileName = wav,
            durationMs = o.optLong("duration_ms", 0L).coerceAtLeast(0L),
            createdAtEpochMs = o.optLong("created_at_ms", 0L),
        )
    }

    fun encodeSoftAnnotation(a: SoftAnnotation): JSONObject =
        JSONObject()
            .put("char_index", a.charIndex)
            .put("length", a.length)
            .apply {
                if (!a.suggestedWord.isNullOrBlank()) put("suggested_word", a.suggestedWord)
                if (!a.correctedPinyin.isNullOrBlank()) put("corrected_pinyin", a.correctedPinyin)
                if (!a.reason.isNullOrBlank()) put("reason", a.reason)
            }

    fun decodeSoftAnnotation(o: JSONObject): SoftAnnotation =
        SoftAnnotation(
            charIndex = o.optInt("char_index", 0),
            length = o.optInt("length", 1).coerceAtLeast(1),
            suggestedWord = o.optString("suggested_word", "").takeIf { it.isNotBlank() },
            correctedPinyin = o.optString("corrected_pinyin", "").takeIf { it.isNotBlank() },
            reason = o.optString("reason", "").takeIf { it.isNotBlank() },
        )

    fun encodePhraseSpan(p: ClausePhraseSpan): JSONObject =
        JSONObject()
            .put("start_offset", p.startOffset)
            .put("end_offset", p.endOffset)
            .put("word", p.word)

    fun decodePhraseSpan(o: JSONObject): ClausePhraseSpan? {
        val word = o.optString("word", "").trim()
        val start = o.optInt("start_offset", -1)
        val end = o.optInt("end_offset", -1)
        if (word.isEmpty() || start < 0 || end <= start) return null
        return ClausePhraseSpan(startOffset = start, endOffset = end, word = word)
    }

    fun adjustPhraseSpansAfterReplace(
        phrases: List<ClausePhraseSpan>,
        startIdx: Int,
        endIdx: Int,
        delta: Int,
    ): List<ClausePhraseSpan> =
        phrases.mapNotNull { span ->
            when {
                span.endOffset <= startIdx -> span
                span.startOffset >= endIdx -> span.copy(
                    startOffset = (span.startOffset + delta).coerceAtLeast(0),
                    endOffset = (span.endOffset + delta).coerceAtLeast(1),
                )
                else -> null
            }
        }

    fun encodeClause(c: Clause): JSONObject =
        JSONObject()
            .put("id", c.id)
            .put("text", c.text)
            .put("paragraph_break_after", c.paragraphBreakAfter)
            .apply {
                c.audioSpan?.let { span ->
                    put(
                        "audio_span",
                        JSONObject()
                            .put("take_id", span.takeId)
                            .put("start_ms", span.startMs)
                            .put("end_ms", span.endMs),
                    )
                }
                if (c.softAnnotations.isNotEmpty()) {
                    val arr = JSONArray()
                    c.softAnnotations.forEach { arr.put(encodeSoftAnnotation(it)) }
                    put("soft_annotations", arr)
                }
                if (c.contextualPhrases.isNotEmpty()) {
                    val arr = JSONArray()
                    c.contextualPhrases.forEach { arr.put(encodePhraseSpan(it)) }
                    put("contextual_phrases", arr)
                }
            }

    fun decodeClause(o: JSONObject, fallbackIdx: Int): Clause? {
        val text = o.optString("text", "").trim()
        if (text.isEmpty()) return null
        val spanObj = o.optJSONObject("audio_span")
        val span = if (spanObj != null) {
            val takeId = spanObj.optString("take_id", "").trim()
            if (takeId.isNotEmpty()) {
                AudioSpan(
                    takeId = takeId,
                    startMs = spanObj.optLong("start_ms", 0L).coerceAtLeast(0L),
                    endMs = spanObj.optLong("end_ms", 0L).coerceAtLeast(0L),
                )
            } else {
                null
            }
        } else {
            null
        }
        val annArr = o.optJSONArray("soft_annotations")
        val anns = if (annArr != null) {
            (0 until annArr.length()).mapNotNull { i -> annArr.optJSONObject(i)?.let(::decodeSoftAnnotation) }
        } else {
            emptyList()
        }
        val phraseArr = o.optJSONArray("contextual_phrases")
        val phrases = if (phraseArr != null) {
            (0 until phraseArr.length()).mapNotNull { i ->
                phraseArr.optJSONObject(i)?.let(::decodePhraseSpan)
            }.filter { it.endOffset <= text.length && text.substring(it.startOffset, it.endOffset) == it.word }
        } else {
            emptyList()
        }
        return Clause(
            id = o.optString("id", "c_$fallbackIdx"),
            text = text,
            audioSpan = span,
            paragraphBreakAfter = o.optBoolean("paragraph_break_after", false),
            softAnnotations = anns,
            contextualPhrases = phrases,
        )
    }

    fun encodeIllustration(ill: BookIllustration): JSONObject =
        JSONObject()
            .put("id", ill.id)
            .put("page_index", ill.pageIndex)
            .put("image_file", ill.imageFileName)
            .put("prompt_used", ill.promptUsed)
            .put("created_at_ms", ill.createdAtEpochMs)

    fun decodeIllustration(o: JSONObject): BookIllustration? {
        val id = o.optString("id", "").trim()
        val img = o.optString("image_file", "").trim()
        if (id.isEmpty() || img.isEmpty()) return null
        return BookIllustration(
            id = id,
            pageIndex = o.optInt("page_index", 0),
            imageFileName = img,
            promptUsed = o.optString("prompt_used", ""),
            createdAtEpochMs = o.optLong("created_at_ms", 0L),
        )
    }

    fun encodeActiveIllustrations(map: Map<Int, String>): JSONObject =
        JSONObject().apply {
            map.forEach { (k, v) -> put(k.toString(), v) }
        }

    fun decodeActiveIllustrations(o: JSONObject?): Map<Int, String> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<Int, String>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val pageIdx = k.toIntOrNull() ?: continue
            val illId = if (o.isNull(k)) "" else o.optString(k, "").trim()
            out[pageIdx] = illId
        }
        return out
    }

    fun encodeSnapshot(s: TimeMachineSnapshot): JSONObject =
        JSONObject()
            .put("id", s.id)
            .put("book_id", s.bookId)
            .put("timestamp_ms", s.timestampEpochMs)
            .put("action_label", s.actionLabel)
            .put("tool_name", s.toolName)
            .put("clauses", JSONArray().apply { s.clauses.forEach { put(encodeClause(it)) } })
            .put("takes", JSONArray().apply { s.takes.forEach { put(encodeTake(it)) } })
            .put("illustrations", JSONArray().apply { s.illustrations.forEach { put(encodeIllustration(it)) } })
            .put("active_illustrations", encodeActiveIllustrations(s.activeIllustrationIds))

    fun decodeSnapshot(o: JSONObject): TimeMachineSnapshot? {
        val id = o.optString("id", "").trim()
        val bookId = o.optString("book_id", "").trim()
        if (id.isEmpty() || bookId.isEmpty()) return null
        val cArr = o.optJSONArray("clauses")
        val clauses = if (cArr != null) {
            (0 until cArr.length()).mapNotNull { i -> cArr.optJSONObject(i)?.let { decodeClause(it, i) } }
        } else {
            emptyList()
        }
        val tArr = o.optJSONArray("takes")
        val takes = if (tArr != null) {
            (0 until tArr.length()).mapNotNull { i -> tArr.optJSONObject(i)?.let(::decodeTake) }
        } else {
            emptyList()
        }
        val iArr = o.optJSONArray("illustrations")
        val ills = if (iArr != null) {
            (0 until iArr.length()).mapNotNull { i -> iArr.optJSONObject(i)?.let(::decodeIllustration) }
        } else {
            emptyList()
        }
        return TimeMachineSnapshot(
            id = id,
            bookId = bookId,
            timestampEpochMs = o.optLong("timestamp_ms", 0L),
            actionLabel = o.optString("action_label", ""),
            toolName = o.optString("tool_name", ""),
            clauses = clauses,
            takes = takes,
            illustrations = ills,
            activeIllustrationIds = decodeActiveIllustrations(o.optJSONObject("active_illustrations")),
        )
    }

    fun encodeBookBible(b: BookBible): JSONObject =
        JSONObject()
            .put("book_id", b.bookId)
            .put("protagonist_traits", JSONArray(b.protagonistTraits))
            .put("key_scenes_and_props", JSONArray(b.keyScenesAndProps))
            .put("plot_summary", b.plotSummary)
            .put("updated_at_ms", b.updatedAtEpochMs)

    fun decodeBookBible(o: JSONObject, fallbackBookId: String): BookBible {
        fun readStrList(key: String): List<String> {
            val arr = o.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i -> arr.optString(i, "").trim().takeIf { it.isNotEmpty() } }
        }
        return BookBible(
            bookId = o.optString("book_id", fallbackBookId).ifBlank { fallbackBookId },
            protagonistTraits = readStrList("protagonist_traits"),
            keyScenesAndProps = readStrList("key_scenes_and_props"),
            plotSummary = o.optString("plot_summary", ""),
            updatedAtEpochMs = o.optLong("updated_at_ms", 0L),
        )
    }

    fun encodeAiConfig(c: AiGatewayConfig): JSONObject =
        JSONObject()
            .put("base_url", c.baseUrl)
            .put("api_key", c.apiKey)
            .put("text_model", c.textModel)
            .put("image_base_url", c.imageBaseUrl)
            .put("image_api_key", c.imageApiKey)
            .put("image_model", c.imageModel)

    fun decodeAiConfig(o: JSONObject): AiGatewayConfig =
        AiGatewayConfig(
            baseUrl = o.optString("base_url", "").trim(),
            apiKey = o.optString("api_key", "").trim(),
            textModel = o.optString("text_model", "").trim(),
            imageBaseUrl = o.optString("image_base_url", "").trim(),
            imageApiKey = o.optString("image_api_key", "").trim(),
            imageModel = o.optString("image_model", "").trim(),
        )

    fun encodeBookV4(book: StoryBook): JSONObject =
        JSONObject()
            .put("version", 4)
            .put("id", book.id)
            .put("book_number", book.bookNumber)
            .put("title", book.title)
            .put("title_audio_file", book.titleAudioFileName)
            .put("cover_color_index", book.coverColorIndex)
            .put("emblem_index", book.emblemIndex)
            .put("clauses", JSONArray().apply { book.clauses.forEach { put(encodeClause(it)) } })
            .put("takes", JSONArray().apply { book.takes.forEach { put(encodeTake(it)) } })
            .put("illustrations", JSONArray().apply { book.illustrations.forEach { put(encodeIllustration(it)) } })
            .put("active_illustrations", encodeActiveIllustrations(book.activeIllustrationIds))
            .put("created_at_ms", book.createdAtMs)
            .put("updated_at_ms", book.updatedAtMs)

    fun renderBookMarkdown(book: StoryBook): String {
        val pages = book.paginate()
        return buildString {
            appendLine("# ${book.title}")
            appendLine()
            for (page in pages) {
                for (para in page.paragraphs) {
                    appendLine(para.clauses.joinToString("") { it.text })
                    appendLine()
                }
            }
        }
    }
}
