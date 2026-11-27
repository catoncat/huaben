package com.luna.storynote.storage

import com.luna.storynote.model.AudioSpan
import com.luna.storynote.model.Clause
import com.luna.storynote.model.StoryBook
import com.luna.storynote.model.TimeMachineSnapshot
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class ClauseHistoryEntry(
    val snapshotId: String,
    val timestampEpochMs: Long,
    val actionLabel: String,
    val clause: Clause,
    val wavFile: File?,
    val audioSpan: AudioSpan?,
    val changeSummary: String = "",
)

class TimeMachineStore(private val booksDir: File) {
    private val lock = Any()
    private val sequence = AtomicLong(0L)

    private fun historyDir(bookId: String): File =
        File(File(booksDir, bookId), "history").apply { mkdirs() }

    /**
     * Deterministic content signature ignoring ephemeral metadata (`softAnnotations`, `contextualPhrases`),
     * used to prevent recording duplicate/no-op snapshots when nothing in the child's story actually changed.
     */
    fun contentFingerprint(
        clauses: List<Clause>,
        activeIllustrationIds: Map<Int, String>,
    ): String = buildString {
        for (c in clauses) {
            append(c.id).append('|').append(c.text).append('|').append(c.paragraphBreakAfter).append('|')
            c.audioSpan?.let { s ->
                append(s.takeId).append(':').append(s.startMs).append('-').append(s.endMs)
            }
            append('\n')
        }
        append("#ILL:")
        activeIllustrationIds.toSortedMap().forEach { (k, v) ->
            if (v.isNotBlank()) append(k).append('=').append(v).append(';')
        }
    }

    fun hasMeaningfulDifference(book: StoryBook, snapshot: TimeMachineSnapshot): Boolean {
        val curFp = contentFingerprint(book.clauses, book.activeIllustrationIds)
        val snapFp = contentFingerprint(snapshot.clauses, snapshot.activeIllustrationIds)
        return curFp != snapFp
    }

    internal fun buildRestoredBook(book: StoryBook, snap: TimeMachineSnapshot): StoryBook {
        val mergedIllustrations = (book.illustrations + snap.illustrations).distinctBy { it.id }
        val restoredActiveIds = snap.activeIllustrationIds.toMutableMap()
        for (ill in mergedIllustrations) {
            if (!restoredActiveIds.containsKey(ill.pageIndex)) {
                restoredActiveIds[ill.pageIndex] = ""
            }
        }
        return book.copy(
            clauses = snap.clauses,
            takes = (book.takes + snap.takes).distinctBy { it.id },
            illustrations = mergedIllustrations,
            activeIllustrationIds = restoredActiveIds,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    internal fun buildClauseRestoredBook(book: StoryBook, snap: TimeMachineSnapshot, clauseId: String): StoryBook? {
        val targetClause = snap.clauses.firstOrNull { it.id == clauseId } ?: return null
        val neededTake = targetClause.audioSpan?.takeId?.let { tid -> snap.takes.firstOrNull { it.id == tid } }
        val nextTakes = if (neededTake != null && book.takes.none { it.id == neededTake.id }) book.takes + neededTake else book.takes
        val list = book.clauses.toMutableList()
        val curIdx = list.indexOfFirst { it.id == clauseId }
        if (curIdx >= 0) {
            list[curIdx] = targetClause
        } else {
            list.add(snap.clauses.indexOfFirst { it.id == clauseId }.coerceIn(0, list.size), targetClause)
        }
        return book.copy(clauses = list, takes = nextTakes, updatedAtMs = System.currentTimeMillis())
    }

    fun recordSnapshot(
        book: StoryBook,
        actionLabel: String,
        toolName: String,
        changeSummary: String = "",
        focusClauseId: String? = null,
    ): TimeMachineSnapshot = synchronized(lock) {
        val now = System.currentTimeMillis()
        val seq = sequence.incrementAndGet() % 1_000_000L
        val snapId = "snap_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val snapshot = TimeMachineSnapshot(
            id = snapId,
            bookId = book.id,
            timestampEpochMs = now,
            actionLabel = actionLabel,
            toolName = toolName,
            clauses = book.clauses,
            takes = book.takes,
            illustrations = book.illustrations,
            activeIllustrationIds = book.activeIllustrationIds,
            changeSummary = changeSummary,
            focusClauseId = focusClauseId,
        )
        val fileName = "${"%013d".format(now)}_${"%06d".format(seq)}_$snapId.json"
        val target = File(historyDir(book.id), fileName)
        BookJsonCodec.atomicWriteText(target, BookJsonCodec.encodeSnapshot(snapshot).toString(2) + "\n")
        snapshot
    }

    fun listSnapshots(bookId: String): List<TimeMachineSnapshot> = synchronized(lock) {
        val dir = File(File(booksDir, bookId), "history")
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        files.sortedByDescending { it.name }
            .mapNotNull { f ->
                try {
                    BookJsonCodec.decodeSnapshot(JSONObject(f.readText(Charsets.UTF_8)))
                } catch (_: Exception) {
                    null
                }
            }
    }

    /**
     * User-facing meaningful history list: hides noisy background metadata snapshots (`轻批注`, `切换插画版本`)
     * and collapses consecutive snapshots that have identical story content.
     */
    fun listMeaningfulSnapshots(book: StoryBook): List<TimeMachineSnapshot> {
        val raw = listSnapshots(book.id)
        val out = mutableListOf<TimeMachineSnapshot>()
        val currentFp = contentFingerprint(book.clauses, book.activeIllustrationIds)
        var lastFp: String? = null

        for (snap in raw) {
            if (snap.toolName in HIDDEN_NOISE_TOOLS) continue
            val fp = contentFingerprint(snap.clauses, snap.activeIllustrationIds)
            // Skip redundant no-op snapshot identical to previous row or identical to live state with no change summary
            if (fp == lastFp) continue
            if (out.isEmpty() && fp == currentFp && snap.toolName == "restore_history_snapshot") continue
            lastFp = fp
            out.add(snap)
        }
        return out
    }

    fun getSnapshot(bookId: String, snapshotId: String): TimeMachineSnapshot? =
        listSnapshots(bookId).firstOrNull { it.id == snapshotId }

    fun findClauseHistory(book: StoryBook, clauseId: String): List<ClauseHistoryEntry> {
        val snapshots = listSnapshots(book.id)
        val currentClause = book.clauses.firstOrNull { it.id == clauseId }
        val currentSig = currentClause?.let { it.text to it.audioSpan }
        val out = mutableListOf<ClauseHistoryEntry>()
        val seenSigs = HashSet<Pair<String, AudioSpan?>>()

        for (snap in snapshots) {
            if (snap.toolName in HIDDEN_NOISE_TOOLS) continue
            val clause = snap.clauses.firstOrNull { it.id == clauseId } ?: continue
            val sig = clause.text to clause.audioSpan
            // Only show genuinely different previous versions of this clause (never mirror live identical state)
            if (sig == currentSig) continue
            if (!seenSigs.add(sig)) continue

            val span = clause.audioSpan
            val take = span?.let { s -> snap.takes.firstOrNull { it.id == s.takeId } ?: book.takeById(s.takeId) }
            val wavFile = take?.let { File(book.bookDir, it.wavFileName) }?.takeIf { it.isFile }
            out.add(
                ClauseHistoryEntry(
                    snapshotId = snap.id,
                    timestampEpochMs = snap.timestampEpochMs,
                    actionLabel = snap.actionLabel,
                    clause = clause,
                    wavFile = wavFile,
                    audioSpan = span,
                    changeSummary = snap.changeSummary,
                ),
            )
        }
        return out
    }

    fun collectProtectedAssetPaths(book: StoryBook): Set<String> {
        val paths = LinkedHashSet<String>()
        if (book.titleAudioFileName.isNotBlank()) paths.add(book.titleAudioFileName)
        book.takes.forEach { if (it.wavFileName.isNotBlank()) paths.add(it.wavFileName) }
        book.illustrations.forEach { if (it.imageFileName.isNotBlank()) paths.add(it.imageFileName) }
        for (snap in listSnapshots(book.id)) {
            snap.takes.forEach { if (it.wavFileName.isNotBlank()) paths.add(it.wavFileName) }
            snap.illustrations.forEach { if (it.imageFileName.isNotBlank()) paths.add(it.imageFileName) }
        }
        return paths
    }

    companion object {
        private val HIDDEN_NOISE_TOOLS = setOf(
            "annotate_soft_suggestion",
            "select_page_illustration",
        )
    }
}
