package com.luna.storynote

/**
 * Formats raw spoken ASR transcripts into short, breathable Grade-1 storybook paragraphs
 * ("一小段一小段"), normalizing Chinese punctuation and breaking up long run-on spoken clauses
 * without altering the child's authentic vocabulary.
 */
object StoryBookFormatter {
    private val SENTENCE_ENDERS = setOf('。', '！', '？', '…')
    private val CLOSING_QUOTES = setOf('”', '’', '》', '）')

    private val PARAGRAPH_STARTERS = listOf(
        "从前", "有一天", "突然", "忽然", "这时候", "就在这时", "第二天", "第三天",
        "后来", "过了好久", "过了一会儿", "到了晚上", "到了早上", "最后", "终于", "从此以后", "原来",
    )

    private val RUN_ON_CONNECTORS = listOf(
        "突然", "忽然", "结果", "于是", "可是", "但是", "没想到", "后来", "最后", "然后",
    )

    fun formatIntoParagraphs(rawText: String, targetParagraphChars: Int = 38): List<String> {
        if (rawText.isBlank()) return emptyList()

        // Preserve explicit blank-line breaks if user/parent manually edited paragraphs
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
                ch == '.' && prevCjk && ! (i + 1 < trimmed.length && trimmed[i + 1].isDigit()) -> sb.append('。')
                ch == '!' && (prevCjk || nextCjk) -> sb.append('！')
                ch == '?' && (prevCjk || nextCjk) -> sb.append('？')
                ch == ';' && (prevCjk || nextCjk) -> sb.append('；')
                ch == ':' && prevCjk -> sb.append('：')
                ch == ' ' && prevCjk && nextCjk -> Unit // remove stray spaces between Hanzi
                else -> sb.append(ch)
            }
        }

        var cleaned = sb.toString()
            .replace(Regex("[，,]{2,}"), "，")
            .replace(Regex("[。]{2,}"), "。")
            .replace(Regex("[！!]{2,}"), "！")
            .replace(Regex("[？?]{2,}"), "？")
            .trim()

        // Break overly long comma-chained spoken sentences (> 48 chars without period) at natural connectors
        cleaned = breakRunOnCommaChains(cleaned)

        val last = cleaned.lastOrNull()
        if (last != null && isCjk(last)) {
            cleaned += "。"
        } else if (last == '，' || last == '、') {
            cleaned = cleaned.dropLast(1) + "。"
        }
        return cleaned
    }

    private fun breakRunOnCommaChains(text: String): String {
        val clauses = text.split('，')
        if (clauses.size <= 2) return text
        val out = StringBuilder()
        var sinceSentenceEnd = 0

        for (idx in clauses.indices) {
            val clause = clauses[idx].trim()
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
                // Consume trailing ellipsis or closing quotes into the same sentence
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

    internal fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x4E00..0x9FFF) || (code in 0x3400..0x4DBF)
    }
}
