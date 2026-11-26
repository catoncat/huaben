package com.luna.storynote.model

import com.luna.storynote.literacy.StoryBookFormatter
import java.io.File

data class AudioTake(
    val id: String,
    val wavFileName: String, // relative to bookDir, e.g. "takes/take_abc.wav" or legacy "clips/s_0.wav"
    val durationMs: Long,
    val createdAtEpochMs: Long,
)

data class AudioSpan(
    val takeId: String,
    val startMs: Long,
    val endMs: Long,
)

data class SoftAnnotation(
    val charIndex: Int,
    val length: Int,
    val suggestedWord: String? = null,
    val correctedPinyin: String? = null,
    val reason: String? = null,
)

data class ClausePhraseSpan(
    val startOffset: Int,
    val endOffset: Int,
    val word: String,
)

data class Clause(
    val id: String,
    val text: String,
    val audioSpan: AudioSpan? = null,
    val paragraphBreakAfter: Boolean = false,
    val softAnnotations: List<SoftAnnotation> = emptyList(),
    val contextualPhrases: List<ClausePhraseSpan> = emptyList(),
) {
    val durationMs: Long
        get() = audioSpan?.let { (it.endMs - it.startMs).coerceAtLeast(0L) } ?: 0L

    val durationSec: Double
        get() = durationMs / 1000.0
}

typealias SentenceClip = Clause

data class StoryParagraphGroup(
    val clauses: List<Clause>,
) {
    val sentences: List<Clause>
        get() = clauses
}

data class BookPage(
    val pageIndex: Int, // 0-based
    val paragraphs: List<StoryParagraphGroup>,
) {
    val clauseIds: List<String>
        get() = paragraphs.flatMap { g -> g.clauses.map { it.id } }

    val sentenceIds: List<String>
        get() = clauseIds
}

data class BookIllustration(
    val id: String,
    val pageIndex: Int,
    val imageFileName: String, // relative to bookDir, e.g. "illustrations/p0_v1.png"
    val promptUsed: String,
    val createdAtEpochMs: Long,
)

data class TimeMachineSnapshot(
    val id: String,
    val bookId: String,
    val timestampEpochMs: Long,
    val actionLabel: String,
    val toolName: String,
    val clauses: List<Clause>,
    val takes: List<AudioTake>,
    val illustrations: List<BookIllustration>,
    val activeIllustrationIds: Map<Int, String>,
    val changeSummary: String = "",
    val focusClauseId: String? = null,
)

data class BookBible(
    val bookId: String,
    val protagonistTraits: List<String> = emptyList(),
    val keyScenesAndProps: List<String> = emptyList(),
    val plotSummary: String = "",
    val updatedAtEpochMs: Long = 0L,
)

data class StoryBook(
    val id: String,
    val bookNumber: Int,
    val title: String,
    val titleAudioFileName: String,
    val coverColorIndex: Int,
    val emblemIndex: Int,
    val clauses: List<Clause>,
    val takes: List<AudioTake> = emptyList(),
    val illustrations: List<BookIllustration> = emptyList(),
    val activeIllustrationIds: Map<Int, String> = emptyMap(),
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val bookDir: File,
) {
    val sentences: List<Clause>
        get() = clauses

    val totalDurationSec: Double
        get() = clauses.sumOf { it.durationSec }

    val totalChars: Int
        get() = clauses.sumOf { c -> c.text.count { StoryBookFormatter.isCjk(it) } }

    fun takeById(takeId: String): AudioTake? =
        takes.firstOrNull { it.id == takeId }

    fun takeWavFile(takeId: String): File? {
        val take = takeById(takeId) ?: return null
        if (take.wavFileName.isBlank()) return null
        val f = File(bookDir, take.wavFileName)
        return if (f.isFile) f else null
    }

    fun resolveAudio(clause: Clause): Pair<File, AudioSpan>? {
        val span = clause.audioSpan ?: return null
        val file = takeWavFile(span.takeId) ?: return null
        return file to span
    }

    fun audioFileFor(clause: Clause): File? =
        resolveAudio(clause)?.first

    fun titleAudioFile(): File? {
        if (titleAudioFileName.isBlank()) return null
        val f = File(bookDir, titleAudioFileName)
        return if (f.isFile) f else null
    }

    fun activeIllustrationFor(pageIndex: Int): BookIllustration? {
        if (activeIllustrationIds.containsKey(pageIndex)) {
            val activeId = activeIllustrationIds[pageIndex].orEmpty()
            if (activeId.isBlank()) return null
            return illustrations.firstOrNull { it.id == activeId }
        }
        return illustrations.lastOrNull { it.pageIndex == pageIndex }
    }

    fun illustrationFile(illustration: BookIllustration): File? {
        if (illustration.imageFileName.isBlank()) return null
        val f = File(bookDir, illustration.imageFileName)
        return if (f.isFile) f else null
    }

    fun coverIllustrationFile(): File? {
        val page0 = activeIllustrationFor(0) ?: return null
        return illustrationFile(page0)
    }

    /**
     * Deterministically groups `clauses` into breathable Grade-1 short paragraphs,
     * then paginates them into real book pages.
     */
    fun paginate(): List<BookPage> {
        if (clauses.isEmpty()) {
            return listOf(BookPage(pageIndex = 0, paragraphs = emptyList()))
        }

        val paragraphs = mutableListOf<StoryParagraphGroup>()
        val curPara = mutableListOf<Clause>()
        var curParaChars = 0

        for (c in clauses) {
            val prevEndedSentence = curPara.lastOrNull()?.let { endsWithSentencePunct(it.text) } == true
            val startsTransition = PARAGRAPH_TRANSITIONS.any { c.text.trimStart('“', ' ').startsWith(it) }
            if (curPara.isNotEmpty() && prevEndedSentence && startsTransition && curParaChars >= 16) {
                paragraphs.add(StoryParagraphGroup(curPara.toList()))
                curPara.clear()
                curParaChars = 0
            }

            curPara.add(c)
            curParaChars += c.text.length

            val curEndedSentence = endsWithSentencePunct(c.text)
            val shouldBreakPara = (c.paragraphBreakAfter && curEndedSentence) ||
                (curEndedSentence && curParaChars >= 24) ||
                curParaChars >= 90
            if (shouldBreakPara) {
                paragraphs.add(StoryParagraphGroup(curPara.toList()))
                curPara.clear()
                curParaChars = 0
            }
        }
        if (curPara.isNotEmpty()) {
            paragraphs.add(StoryParagraphGroup(curPara.toList()))
        }

        val pages = mutableListOf<BookPage>()
        val curPageParas = mutableListOf<StoryParagraphGroup>()
        var curPageChars = 0

        for (para in paragraphs) {
            val paraChars = para.clauses.sumOf { it.text.length }
            val isFirstPage = pages.isEmpty()
            val maxChars = if (isFirstPage) 38 else 54
            val maxParas = 2

            if (curPageParas.isNotEmpty() && (curPageChars + paraChars > maxChars || curPageParas.size >= maxParas)) {
                pages.add(BookPage(pageIndex = pages.size, paragraphs = curPageParas.toList()))
                curPageParas.clear()
                curPageChars = 0
            }
            curPageParas.add(para)
            curPageChars += paraChars
        }
        if (curPageParas.isNotEmpty()) {
            pages.add(BookPage(pageIndex = pages.size, paragraphs = curPageParas.toList()))
        }
        return pages
    }

    fun pageIndexOfClause(clauseId: String?): Int {
        if (clauseId == null) return (paginate().size - 1).coerceAtLeast(0)
        val pages = paginate()
        val idx = pages.indexOfFirst { p -> p.clauseIds.contains(clauseId) }
        return if (idx >= 0) idx else (pages.size - 1).coerceAtLeast(0)
    }

    fun pageIndexOfSentence(sentenceId: String?): Int = pageIndexOfClause(sentenceId)

    companion object {
        private val SENTENCE_END_PUNCT = setOf('。', '！', '？', '…', '”', '’')
        private val COMMA_OR_PAUSE_PUNCT = setOf('，', '、', '；', '：', ',')
        private val PARAGRAPH_TRANSITIONS = listOf(
            "从前", "有一天", "突然", "忽然", "这时候", "第二天", "后来", "过了一会儿", "最后", "终于", "原来",
        )

        private fun endsWithSentencePunct(text: String): Boolean {
            val trimmed = text.trimEnd()
            if (trimmed.isEmpty()) return false
            val last = trimmed.last()
            if (last in COMMA_OR_PAUSE_PUNCT) return false
            if (last == '”' || last == '’') {
                val prev = trimmed.getOrNull(trimmed.length - 2)
                if (prev != null && prev in COMMA_OR_PAUSE_PUNCT) return false
                return true
            }
            return last in SENTENCE_END_PUNCT
        }
    }
}

data class CollectedWordCard(
    val word: String,
    val pinyins: List<String>,
    val bookId: String,
    val clauseId: String,
    val clauseText: String,
    val takeAudioPath: String,
    val takeStartMs: Long = 0L,
    val takeEndMs: Long = 0L,
    val collectedAtMs: Long,
) {
    val sentenceId: String get() = clauseId
    val sentenceText: String get() = clauseText
    val sentenceAudioPath: String get() = takeAudioPath
}
