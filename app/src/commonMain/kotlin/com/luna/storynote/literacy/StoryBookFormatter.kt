package com.luna.storynote.literacy

import com.luna.storynote.model.AudioSpan
import com.luna.storynote.model.Clause
import kotlin.math.abs
import kotlin.math.ceil

data class ClauseDraft(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val paragraphBreakAfter: Boolean,
)

object StoryBookFormatter {
    private val SENTENCE_ENDERS = setOf('。', '！', '？', '…')
    private val CLAUSE_SPLIT_PUNCT = setOf('。', '！', '？', '…', '；', '，', '、', '：')
    private val CLOSING_QUOTES = setOf('”', '’', '》', '）', ')')

    private val PARAGRAPH_STARTERS = listOf(
        "从前", "有一天", "突然", "忽然", "这时候", "就在这时", "第二天", "第三天",
        "后来", "过了好久", "过了一会儿", "到了晚上", "到了早上", "最后", "终于", "从此以后", "原来",
    )

    private val RUN_ON_CONNECTORS = listOf(
        "突然", "忽然", "结果", "于是", "可是", "但是", "没想到", "后来", "最后", "然后",
    )

    fun formatIntoParagraphs(rawText: String, targetParagraphChars: Int = 38): List<String> {
        if (rawText.isBlank()) return emptyList()
        val manualBlocks = rawText
            .replace("\r\n", "\n")
            .split(Regex("\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (manualBlocks.size > 1) {
            return manualBlocks.flatMap { splitBlockIntoShortParagraphs(normalizeChinesePunct(it), targetParagraphChars) }
        }

        val normalized = normalizeChinesePunct(rawText.replace('\n', ' '))
        return splitBlockIntoShortParagraphs(normalized, targetParagraphChars)
    }

    fun formatToText(rawText: String): String =
        formatIntoParagraphs(rawText).joinToString("\n\n")

    fun normalizeChinesePunct(input: String): String {
        if (input.isBlank()) return ""
        val sb = StringBuilder(input.length + 8)
        val trimmed = input.trim()
        for (i in trimmed.indices) {
            val ch = trimmed[i]
            val prevCjk = i > 0 && isCjk(trimmed[i - 1])
            val nextCjk = i + 1 < trimmed.length && isCjk(trimmed[i + 1])
            when {
                ch == ',' && (prevCjk || nextCjk) -> sb.append('，')
                ch == '.' && prevCjk && !(i + 1 < trimmed.length && trimmed[i + 1].isDigit()) -> sb.append('。')
                ch == '!' && (prevCjk || nextCjk) -> sb.append('！')
                ch == '?' && (prevCjk || nextCjk) -> sb.append('？')
                ch == ';' && (prevCjk || nextCjk) -> sb.append('；')
                ch == ':' && prevCjk -> sb.append('：')
                ch == ' ' && prevCjk && nextCjk -> Unit
                else -> sb.append(ch)
            }
        }

        var cleaned = sb.toString()
            .replace(Regex("[，,]{2,}"), "，")
            .replace(Regex("[。]{2,}"), "。")
            .replace(Regex("[！!]{2,}"), "！")
            .replace(Regex("[？?]{2,}"), "？")
            .trim()

        cleaned = breakRunOnCommaChains(cleaned)

        val last = cleaned.lastOrNull()
        if (last != null && isCjk(last)) {
            cleaned += "。"
        } else if (last == '，' || last == '、') {
            cleaned = cleaned.dropLast(1) + "。"
        }
        return cleaned
    }

    /**
     * Splits spoken text on punctuation and Jieba word boundaries into breathable 8–16 char clauses.
     */
    fun splitIntoClauses(
        rawText: String,
        minChars: Int = 8,
        maxChars: Int = 16,
        wordBoundaries: (String) -> List<IntRange> = JiebaWordSegmenter::tokenRanges,
    ): List<String> {
        val normalized = normalizeChinesePunct(rawText.replace('\n', ' '))
        if (normalized.isBlank()) return emptyList()

        // 1. Split at punctuation marks while keeping closing quotes attached
        val punctPieces = mutableListOf<String>()
        val buf = StringBuilder()
        var i = 0
        while (i < normalized.length) {
            val ch = normalized[i]
            buf.append(ch)
            if (ch in CLAUSE_SPLIT_PUNCT) {
                while (i + 1 < normalized.length &&
                    (normalized[i + 1] in CLAUSE_SPLIT_PUNCT || normalized[i + 1] in CLOSING_QUOTES)
                ) {
                    i++
                    buf.append(normalized[i])
                }
                val piece = buf.toString().trim()
                if (piece.isNotEmpty()) punctPieces.add(piece)
                buf.setLength(0)
            }
            i++
        }
        if (buf.isNotEmpty()) {
            val tail = buf.toString().trim()
            if (tail.isNotEmpty()) punctPieces.add(tail)
        }

        // 2. Break any >maxChars piece along Jieba word boundaries into balanced 8..16 char chunks
        val balanced = mutableListOf<String>()
        for (piece in punctPieces) {
            if (piece.length <= maxChars) {
                balanced.add(piece)
            } else {
                balanced.addAll(splitLongPieceAtWordBoundaries(piece, minChars, maxChars, wordBoundaries))
            }
        }

        // 3. Merge tiny <minChars fragments with an adjacent piece when combined <= maxChars
        val merged = mutableListOf<String>()
        for (piece in balanced) {
            if (merged.isEmpty()) {
                merged.add(piece)
                continue
            }
            val prev = merged.last()
            val prevStrongEnd = prev.lastOrNull() in SENTENCE_ENDERS ||
                (prev.length >= 2 && prev.lastOrNull() in CLOSING_QUOTES && prev[prev.length - 2] in SENTENCE_ENDERS)
            val shouldMergeIntoPrev = (piece.length < minChars || prev.length < minChars) &&
                !prevStrongEnd &&
                (prev.length + piece.length <= maxChars)
            if (shouldMergeIntoPrev) {
                merged[merged.lastIndex] = prev + piece
            } else {
                merged.add(piece)
            }
        }
        return merged.filter { it.isNotBlank() }
    }

    /**
     * Splits spoken utterance text into 8–16 char `ClauseDraft` items with proportional/VAD-aligned
     * `[startMs, endMs]` audio spans inside `[0, totalDurationMs]` (or offset by `baseOffsetMs`).
     */
    fun splitIntoClausesWithSpans(
        rawText: String,
        totalDurationMs: Long,
        vadPauseMs: List<Long> = emptyList(),
        finalParagraphBreak: Boolean = false,
        minChars: Int = 8,
        maxChars: Int = 16,
        wordBoundaries: (String) -> List<IntRange> = JiebaWordSegmenter::tokenRanges,
    ): List<ClauseDraft> {
        val texts = splitIntoClauses(rawText, minChars, maxChars, wordBoundaries)
        if (texts.isEmpty()) return emptyList()
        val safeTotalMs = totalDurationMs.coerceAtLeast(0L)
        if (texts.size == 1) {
            val txt = texts[0]
            val endsSentence = txt.lastOrNull() in SENTENCE_ENDERS ||
                (txt.length >= 2 && txt.lastOrNull() in CLOSING_QUOTES && txt[txt.length - 2] in SENTENCE_ENDERS)
            return listOf(ClauseDraft(txt, 0L, safeTotalMs, finalParagraphBreak && endsSentence))
        }

        val weights = texts.map { t -> t.count { isCjk(it) || it.isLetterOrDigit() }.coerceAtLeast(1) }
        val totalWeight = weights.sum().coerceAtLeast(1)
        val sortedPauses = vadPauseMs.filter { it in 1L until safeTotalMs }.sorted()

        val boundaries = LongArray(texts.size + 1)
        boundaries[0] = 0L
        boundaries[texts.size] = safeTotalMs

        var accWeight = 0
        for (idx in 0 until texts.size - 1) {
            accWeight += weights[idx]
            val proportional = (safeTotalMs * accWeight) / totalWeight
            val prevBound = boundaries[idx]
            val approxSpan = (safeTotalMs * weights[idx]) / totalWeight
            val snapWindow = (approxSpan * 0.35).toLong().coerceAtLeast(80L)
            val candidatePause = sortedPauses
                .filter { p ->
                    p > prevBound + 80L &&
                        p < safeTotalMs - 80L &&
                        abs(p - proportional) <= snapWindow
                }
                .minByOrNull { abs(it - proportional) }

            val (minBound, maxBound) = if (safeTotalMs >= texts.size) {
                (prevBound + 1L) to (safeTotalMs - (texts.size - 1 - idx).toLong())
            } else {
                prevBound to safeTotalMs
            }
            boundaries[idx + 1] = (candidatePause ?: proportional)
                .coerceIn(minBound, maxBound)
        }

        val drafts = ArrayList<ClauseDraft>(texts.size)
        var paraChars = 0
        for (idx in texts.indices) {
            val txt = texts[idx]
            paraChars += txt.length
            val nextStartsPara = idx + 1 < texts.size &&
                PARAGRAPH_STARTERS.any { texts[idx + 1].trimStart('“', ' ').startsWith(it) } &&
                paraChars >= 16
            val endsSentence = txt.lastOrNull() in SENTENCE_ENDERS ||
                (txt.length >= 2 && txt.lastOrNull() in CLOSING_QUOTES && txt[txt.length - 2] in SENTENCE_ENDERS)
            val breakAfter = when {
                !endsSentence -> false
                idx == texts.lastIndex -> finalParagraphBreak
                nextStartsPara -> true
                paraChars >= 34 -> true
                else -> false
            }
            if (breakAfter) paraChars = 0
            drafts.add(
                ClauseDraft(
                    text = txt,
                    startMs = boundaries[idx],
                    endMs = boundaries[idx + 1],
                    paragraphBreakAfter = breakAfter,
                ),
            )
        }
        return drafts
    }

    fun buildClausesForTake(
        rawText: String,
        takeId: String,
        uttStartMs: Long,
        uttEndMs: Long,
        clauseIdGenerator: (Int) -> String,
        vadPauseMs: List<Long> = emptyList(),
        finalParagraphBreak: Boolean = false,
    ): List<Clause> {
        val localDur = (uttEndMs - uttStartMs).coerceAtLeast(0L)
        val localPauses = vadPauseMs.map { it - uttStartMs }
        val drafts = splitIntoClausesWithSpans(
            rawText = rawText,
            totalDurationMs = localDur,
            vadPauseMs = localPauses,
            finalParagraphBreak = finalParagraphBreak,
        )
        return drafts.mapIndexed { idx, d ->
            Clause(
                id = clauseIdGenerator(idx),
                text = d.text,
                audioSpan = if (takeId.isNotBlank() && localDur > 0L) {
                    AudioSpan(
                        takeId = takeId,
                        startMs = uttStartMs + d.startMs,
                        endMs = uttStartMs + d.endMs,
                    )
                } else {
                    null
                },
                paragraphBreakAfter = d.paragraphBreakAfter,
            )
        }
    }

    private fun splitLongPieceAtWordBoundaries(
        piece: String,
        minChars: Int,
        maxChars: Int,
        wordBoundaries: (String) -> List<IntRange>,
    ): List<String> {
        val ranges = wordBoundaries(piece).ifEmpty {
            piece.indices.map { it..it }
        }
        val chunkCount = ceil(piece.length.toDouble() / 12.0).toInt().coerceAtLeast(2)
        val targetLen = ((piece.length + chunkCount - 1) / chunkCount).coerceIn(minChars, maxChars)

        val chunks = mutableListOf<String>()
        val cur = StringBuilder()

        for ((idx, r) in ranges.withIndex()) {
            val s = r.first.coerceIn(0, piece.length)
            val e = (r.last + 1).coerceIn(s, piece.length)
            if (e <= s) continue
            val tok = piece.substring(s, e)
            val isPunctOnly = tok.length == 1 && isClausePunctChar(tok[0])

            if (isPunctOnly) {
                if (cur.length + tok.length > maxChars) {
                    val lastNonPunct = (cur.length - 1 downTo 0).firstOrNull { !isClausePunctChar(cur[it]) } ?: -1
                    if (lastNonPunct > 0 &&
                        (0 until lastNonPunct).any { !isClausePunctChar(cur[it]) } &&
                        (cur.length - lastNonPunct + tok.length) <= maxChars
                    ) {
                        chunks.add(cur.substring(0, lastNonPunct))
                        val carried = cur.substring(lastNonPunct)
                        cur.setLength(0)
                        cur.append(carried)
                    }
                }
                cur.append(tok)
                continue
            }

            var trailingPunctLen = 0
            var lookIdx = idx + 1
            while (lookIdx < ranges.size) {
                val lr = ranges[lookIdx]
                val ls = lr.first.coerceIn(0, piece.length)
                val le = (lr.last + 1).coerceIn(ls, piece.length)
                if (le - ls == 1 && isClausePunctChar(piece[ls])) {
                    trailingPunctLen++
                    lookIdx++
                } else {
                    break
                }
            }

            if (cur.isNotEmpty()) {
                val curLen = cur.length
                val nextLen = curLen + tok.length
                val exceedsMax = nextLen > maxChars || (trailingPunctLen > 0 && nextLen + trailingPunctLen > maxChars)
                val closerToTarget = curLen >= minChars && abs(nextLen - targetLen) > abs(curLen - targetLen)
                if (exceedsMax || closerToTarget) {
                    chunks.add(cur.toString())
                    cur.setLength(0)
                }
            }

            if (tok.length > maxChars) {
                var off = 0
                while (off < tok.length) {
                    val end = (off + maxChars).coerceAtMost(tok.length)
                    chunks.add(tok.substring(off, end))
                    off = end
                }
            } else {
                cur.append(tok)
            }
        }
        if (cur.isNotEmpty()) {
            val tail = cur.toString()
            if (tail.length < minChars && chunks.isNotEmpty() && chunks.last().length + tail.length <= maxChars) {
                chunks[chunks.lastIndex] = chunks.last() + tail
            } else {
                chunks.add(tail)
            }
        }
        return chunks
    }

    private fun breakRunOnCommaChains(text: String): String {
        val clauses = text.split('，')
        if (clauses.size <= 2) return text
        val out = StringBuilder()
        var sinceSentenceEnd = 0

        for (clauseRaw in clauses) {
            val clause = clauseRaw.trim()
            if (clause.isEmpty()) continue
            if (out.isNotEmpty()) {
                val prevEnded = out.last() in SENTENCE_ENDERS
                if (!prevEnded) {
                    val startsConnector = RUN_ON_CONNECTORS.any { clause.startsWith(it) }
                    if ((sinceSentenceEnd >= 28 && startsConnector) || sinceSentenceEnd >= 48) {
                        out.append('。')
                        sinceSentenceEnd = 0
                    } else {
                        out.append('，')
                    }
                }
            }
            out.append(clause)
            val lastStop = clause.indexOfLast { it in SENTENCE_ENDERS }
            sinceSentenceEnd = if (lastStop >= 0) {
                clause.length - 1 - lastStop
            } else {
                sinceSentenceEnd + clause.length + 1
            }
        }
        return out.toString()
    }

    private fun splitBlockIntoShortParagraphs(text: String, targetChars: Int): List<String> {
        val sentences = splitIntoSentences(text)
        if (sentences.isEmpty()) return emptyList()

        val paragraphs = mutableListOf<String>()
        val current = StringBuilder()
        var currentSentenceCount = 0

        for (sentence in sentences) {
            val s = sentence.trim()
            if (s.isEmpty()) continue

            val startsTransition = PARAGRAPH_STARTERS.any { s.startsWith(it) }
            val shouldFlushBefore = current.isNotEmpty() && (
                (startsTransition && current.length >= 16) ||
                    current.length >= targetChars ||
                    (currentSentenceCount >= 2 && current.length >= 24) ||
                    (current.length + s.length > targetChars + 22 && current.length >= 18)
                )

            if (shouldFlushBefore) {
                paragraphs.add(current.toString().trim())
                current.setLength(0)
                currentSentenceCount = 0
            }

            current.append(s)
            currentSentenceCount++
        }

        if (current.isNotEmpty()) {
            paragraphs.add(current.toString().trim())
        }
        return paragraphs
    }

    private fun splitIntoSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        val buf = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            buf.append(ch)
            if (ch in SENTENCE_ENDERS) {
                while (i + 1 < text.length && (text[i + 1] in SENTENCE_ENDERS || text[i + 1] in CLOSING_QUOTES)) {
                    i++
                    buf.append(text[i])
                }
                result.add(buf.toString())
                buf.setLength(0)
            }
            i++
        }
        if (buf.isNotEmpty()) {
            result.add(buf.toString())
        }
        return result
    }

    fun deriveStoryTitle(paragraphs: List<String>, storyNumber: Int): String {
        val first = paragraphs.firstOrNull().orEmpty()
        if (first.isBlank()) return "第${storyNumber}篇故事"
        val clause = first
            .split(Regex("[。！？!?，,；;\\n]"), limit = 2)
            .firstOrNull()
            .orEmpty()
            .trim(' ', '“', '”', '《', '》', '：', ':')
        if (clause.isEmpty()) return "第${storyNumber}篇故事"
        return if (clause.length > 12) clause.take(12) else clause
    }

    private fun isClausePunctChar(ch: Char): Boolean = ch in CLAUSE_SPLIT_PUNCT || ch in CLOSING_QUOTES

    fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x4E00..0x9FFF) || (code in 0x3400..0x4DBF)
    }
}
