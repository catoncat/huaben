package com.luna.storynote.literacy

import com.luna.storynote.model.AudioSpan
import com.luna.storynote.model.Clause
import com.luna.storynote.model.StoryBook
import java.util.UUID

data class FullTextReconcileResult(
    val title: String,
    val titleAudioFileName: String,
    val clauses: List<Clause>,
    val hasMeaningfulChange: Boolean,
)

/**
 * Renders a StoryBook into plain editable paragraphs for adults and reconciles the edited
 * full text back into Grade-1 clauses while silently preserving original kid audio spans.
 */
object FullTextAudioAligner {
    private const val MIN_ALIGN_COVERAGE = 0.45
    private const val MAX_ADJACENT_MERGE_GAP_MS = 250L

    fun renderEditableFullText(book: StoryBook): String {
        if (book.clauses.isEmpty()) return ""
        return book.paginate()
            .flatMap { it.paragraphs }
            .joinToString("\n\n") { para -> para.clauses.joinToString("") { it.text } }
    }

    fun reconcileBook(
        book: StoryBook,
        editedTitle: String,
        editedFullText: String,
        clauseIdGenerator: () -> String = { "c_${UUID.randomUUID().toString().replace("-", "").take(8)}" },
    ): FullTextReconcileResult {
        val cleanedTitle = cleanBookTitle(editedTitle, fallback = book.title)
        val titleChanged = cleanedTitle != book.title
        val nextTitleAudio = reconcileTitleAudio(book.title, cleanedTitle, book.titleAudioFileName)

        val initialRendered = renderEditableFullText(book).trim()
        val normalizedEdited = editedFullText.replace("\r\n", "\n").replace('\r', '\n').trim()
        val nextClauses = if (normalizedEdited == initialRendered) {
            book.clauses
        } else {
            reconcileClauses(book, normalizedEdited, clauseIdGenerator)
        }

        val clausesChanged = !sameClauseStructure(book.clauses, nextClauses)
        return FullTextReconcileResult(
            title = cleanedTitle,
            titleAudioFileName = nextTitleAudio,
            clauses = nextClauses,
            hasMeaningfulChange = titleChanged || clausesChanged,
        )
    }

    fun reconcileClauses(
        book: StoryBook,
        editedFullText: String,
        clauseIdGenerator: () -> String = { "c_${UUID.randomUUID().toString().replace("-", "").take(8)}" },
    ): List<Clause> {
        val rawParagraphs = editedFullText
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split(Regex("\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (rawParagraphs.isEmpty()) return emptyList()

        val draftClauses = buildTargetClauseDrafts(book, rawParagraphs)
        if (draftClauses.isEmpty()) return emptyList()
        if (book.clauses.isEmpty()) {
            return draftClauses.map { d ->
                Clause(id = clauseIdGenerator(), text = d.text, audioSpan = null, paragraphBreakAfter = d.paragraphBreakAfter)
            }
        }

        val oldUnits = PhoneticSequenceAligner.extractSpokenUnits(book.clauses.map { it.text })
        val newUnits = PhoneticSequenceAligner.extractSpokenUnits(draftClauses.map { it.text })
        val newSpokenCounts = IntArray(draftClauses.size).also { counts ->
            for (u in newUnits) counts[u.clauseIdx]++
        }
        val alignment = PhoneticSequenceAligner.align(oldUnits, newUnits)

        val perNewClauseMatches = Array(draftClauses.size) { mutableListOf<UnitMatch>() }
        for (newIdx in alignment.indices) {
            val oldIdx = alignment[newIdx]
            if (oldIdx < 0) continue
            val oldUnit = oldUnits[oldIdx]
            val newUnit = newUnits[newIdx]
            val weight = PhoneticSequenceAligner.matchWeight(oldUnit, newUnit)
            if (weight <= 0.0) continue
            val oldSpan = book.clauses[oldUnit.clauseIdx].audioSpan ?: continue
            perNewClauseMatches[newUnit.clauseIdx].add(
                UnitMatch(oldUnit.clauseIdx, oldUnit.indexInClause, oldUnit.clauseSpokenCount, oldSpan, weight),
            )
        }

        val resolvedBindings = arrayOfNulls<ClauseBinding>(draftClauses.size)
        val oldClauseClaimers = HashMap<Int, MutableList<Int>>()
        for (newClauseIdx in draftClauses.indices) {
            val binding = resolveDominantBinding(
                oldClauses = book.clauses,
                matches = perNewClauseMatches[newClauseIdx],
                spokenCount = newSpokenCounts[newClauseIdx],
            ) ?: continue
            resolvedBindings[newClauseIdx] = binding
            for (oldIdx in binding.oldClauseIndices) {
                oldClauseClaimers.getOrPut(oldIdx) { mutableListOf() }.add(newClauseIdx)
            }
        }

        return materializeAlignedClauses(book.clauses, draftClauses, resolvedBindings, oldClauseClaimers, clauseIdGenerator)
    }

    private fun buildTargetClauseDrafts(book: StoryBook, rawParagraphs: List<String>): List<TargetClauseDraft> {
        val oldParagraphs = book.paginate().flatMap { it.paragraphs }
        val oldParagraphTexts = oldParagraphs.map { para -> para.clauses.joinToString("") { it.text } }
        val usedOldParaIndices = BooleanArray(oldParagraphTexts.size)
        val drafts = mutableListOf<TargetClauseDraft>()

        for ((pIdx, paraText) in rawParagraphs.withIndex()) {
            val isLastParagraph = pIdx == rawParagraphs.lastIndex
            val exactIdx = oldParagraphTexts.indices.firstOrNull { !usedOldParaIndices[it] && oldParagraphTexts[it] == paraText }
            if (exactIdx != null) {
                usedOldParaIndices[exactIdx] = true
                val orig = oldParagraphs[exactIdx].clauses
                for ((cIdx, oc) in orig.withIndex()) {
                    val breakAfter = if (cIdx == orig.lastIndex) !isLastParagraph else oc.paragraphBreakAfter
                    drafts.add(TargetClauseDraft(oc.text, breakAfter))
                }
            } else {
                val pieces = StoryBookFormatter.splitIntoClauses(paraText)
                for ((cIdx, piece) in pieces.withIndex()) {
                    drafts.add(TargetClauseDraft(piece, (cIdx == pieces.lastIndex) && !isLastParagraph))
                }
            }
        }
        return drafts
    }

    private fun resolveDominantBinding(
        oldClauses: List<Clause>,
        matches: List<UnitMatch>,
        spokenCount: Int,
    ): ClauseBinding? {
        if (matches.isEmpty() || spokenCount <= 0) return null
        val (takeId, takeMatches) = matches
            .groupBy { it.audioSpan.takeId }
            .maxByOrNull { (_, list) -> list.sumOf { it.weight } }
            ?: return null

        val byOldClause = takeMatches.groupBy { it.oldClauseIdx }
        val runs = mutableListOf<MutableList<Int>>()
        for (oldIdx in byOldClause.keys.sorted()) {
            val prevRun = runs.lastOrNull()
            if (prevRun != null && canBridgeAdjacent(oldClauses, prevRun.last(), oldIdx, takeId)) {
                prevRun.add(oldIdx)
            } else {
                runs.add(mutableListOf(oldIdx))
            }
        }

        val bestRun = runs.maxByOrNull { run -> run.sumOf { idx -> byOldClause[idx].orEmpty().sumOf { it.weight } } }
            ?: return null
        val runWeight = bestRun.sumOf { idx -> byOldClause[idx].orEmpty().sumOf { it.weight } }
        val coverage = runWeight / spokenCount.toDouble()
        val minWeight = if (spokenCount <= 2) 0.95 else 1.5
        if (coverage < MIN_ALIGN_COVERAGE || runWeight < minWeight) return null

        return ClauseBinding(
            takeId = takeId,
            oldClauseIndices = bestRun,
            matchesByOldClause = bestRun.associateWith { byOldClause[it].orEmpty() },
        )
    }

    private fun materializeAlignedClauses(
        oldClauses: List<Clause>,
        draftClauses: List<TargetClauseDraft>,
        resolvedBindings: Array<ClauseBinding?>,
        oldClauseClaimers: Map<Int, List<Int>>,
        clauseIdGenerator: () -> String,
    ): List<Clause> {
        val claimedOldIds = HashSet<String>()
        val result = ArrayList<Clause>(draftClauses.size)

        for (newIdx in draftClauses.indices) {
            val draft = draftClauses[newIdx]
            val binding = resolvedBindings[newIdx]
            if (binding == null) {
                result.add(Clause(id = clauseIdGenerator(), text = draft.text, audioSpan = null, paragraphBreakAfter = draft.paragraphBreakAfter))
                continue
            }

            val firstOldIdx = binding.oldClauseIndices.first()
            val lastOldIdx = binding.oldClauseIndices.last()
            val firstOldSpan = oldClauses[firstOldIdx].audioSpan!!
            val lastOldSpan = oldClauses[lastOldIdx].audioSpan!!

            val startMs = boundaryStartMs(firstOldIdx, firstOldSpan, newIdx, oldClauseClaimers[firstOldIdx].orEmpty(), resolvedBindings)
            val endMs = boundaryEndMs(lastOldIdx, lastOldSpan, newIdx, oldClauseClaimers[lastOldIdx].orEmpty(), resolvedBindings)
                .coerceAtLeast(if (lastOldSpan.endMs > startMs) startMs + 1L else startMs)

            val primaryOldIdx = binding.oldClauseIndices.maxByOrNull { idx ->
                binding.matchesByOldClause[idx].orEmpty().sumOf { it.weight }
            } ?: firstOldIdx
            val primaryOld = oldClauses[primaryOldIdx]
            val reuseId = claimedOldIds.add(primaryOld.id)
            val exactPreserved = reuseId && binding.oldClauseIndices.size == 1 && draft.text == primaryOld.text

            result.add(
                Clause(
                    id = if (reuseId) primaryOld.id else clauseIdGenerator(),
                    text = draft.text,
                    audioSpan = AudioSpan(binding.takeId, startMs, endMs),
                    paragraphBreakAfter = draft.paragraphBreakAfter,
                    softAnnotations = if (exactPreserved) primaryOld.softAnnotations else emptyList(),
                    contextualPhrases = if (exactPreserved) primaryOld.contextualPhrases else emptyList(),
                ),
            )
        }
        return result
    }

    private fun reconcileTitleAudio(oldTitle: String, newTitle: String, oldTitleAudio: String): String {
        if (oldTitleAudio.isBlank()) return ""
        if (oldTitle == newTitle) return oldTitleAudio
        return if (PhoneticSequenceAligner.textSimilarity(oldTitle, newTitle) >= MIN_ALIGN_COVERAGE) oldTitleAudio else ""
    }

    private fun cleanBookTitle(rawTitle: String, fallback: String): String {
        val cleaned = rawTitle.replace("\r", " ").replace("\n", " ").trim()
            .trim('。', '，', '！', '？', '《', '》', '“', '”').take(24)
        return cleaned.ifEmpty { fallback.ifBlank { "未命名故事" } }
    }

    private fun sameClauseStructure(a: List<Clause>, b: List<Clause>): Boolean =
        a.size == b.size && a.indices.all { i ->
            a[i].text == b[i].text && a[i].paragraphBreakAfter == b[i].paragraphBreakAfter && a[i].audioSpan == b[i].audioSpan
        }

    private fun canBridgeAdjacent(oldClauses: List<Clause>, prevIdx: Int, nextIdx: Int, takeId: String): Boolean {
        if (nextIdx != prevIdx + 1) return false
        val prevSpan = oldClauses[prevIdx].audioSpan ?: return false
        val nextSpan = oldClauses[nextIdx].audioSpan ?: return false
        return prevSpan.takeId == takeId && nextSpan.takeId == takeId &&
            nextSpan.startMs >= prevSpan.startMs && (nextSpan.startMs - prevSpan.endMs) <= MAX_ADJACENT_MERGE_GAP_MS
    }

    private fun boundaryStartMs(
        oldIdx: Int,
        oldSpan: AudioSpan,
        newIdx: Int,
        claimers: List<Int>,
        bindings: Array<ClauseBinding?>,
    ): Long {
        if (claimers.size <= 1 || claimers.first() == newIdx) return oldSpan.startMs
        val prevNewIdx = claimers.getOrNull(claimers.indexOf(newIdx) - 1) ?: return oldSpan.startMs
        return midpointSplitMs(oldIdx, oldSpan, prevNewIdx, newIdx, bindings)
    }

    private fun boundaryEndMs(
        oldIdx: Int,
        oldSpan: AudioSpan,
        newIdx: Int,
        claimers: List<Int>,
        bindings: Array<ClauseBinding?>,
    ): Long {
        if (claimers.size <= 1 || claimers.last() == newIdx) return oldSpan.endMs
        val nextNewIdx = claimers.getOrNull(claimers.indexOf(newIdx) + 1) ?: return oldSpan.endMs
        return midpointSplitMs(oldIdx, oldSpan, newIdx, nextNewIdx, bindings)
    }

    private fun midpointSplitMs(
        oldIdx: Int,
        oldSpan: AudioSpan,
        leftNewIdx: Int,
        rightNewIdx: Int,
        bindings: Array<ClauseBinding?>,
    ): Long {
        val dur = (oldSpan.endMs - oldSpan.startMs).coerceAtLeast(0L)
        if (dur <= 1L) return oldSpan.startMs
        val leftMatches = bindings[leftNewIdx]?.matchesByOldClause?.get(oldIdx).orEmpty()
        val rightMatches = bindings[rightNewIdx]?.matchesByOldClause?.get(oldIdx).orEmpty()
        val total = (leftMatches.firstOrNull()?.oldClauseSpokenCount
            ?: rightMatches.firstOrNull()?.oldClauseSpokenCount ?: 1).coerceAtLeast(1)
        val leftEnd = (leftMatches.maxOfOrNull { it.oldIndexInClause } ?: 0) + 1
        val rightStart = rightMatches.minOfOrNull { it.oldIndexInClause } ?: leftEnd
        val splitUnit = ((leftEnd + rightStart) / 2.0).coerceIn(0.5, total - 0.5)
        return oldSpan.startMs + ((dur * splitUnit) / total.toDouble()).toLong().coerceIn(1L, dur - 1L)
    }

    private data class TargetClauseDraft(val text: String, val paragraphBreakAfter: Boolean)
    private data class UnitMatch(val oldClauseIdx: Int, val oldIndexInClause: Int, val oldClauseSpokenCount: Int, val audioSpan: AudioSpan, val weight: Double)
    private data class ClauseBinding(val takeId: String, val oldClauseIndices: List<Int>, val matchesByOldClause: Map<Int, List<UnitMatch>>)
}
