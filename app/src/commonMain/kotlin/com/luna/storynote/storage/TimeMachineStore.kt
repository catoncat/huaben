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
)

class TimeMachineStore(private val booksDir: File) {
    private val lock = Any()
    private val sequence = AtomicLong(0L)

    private fun historyDir(bookId: String): File =
        File(File(booksDir, bookId), "history").apply { mkdirs() }

    fun recordSnapshot(
        book: StoryBook,
        actionLabel: String,
        toolName: String,
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

    fun getSnapshot(bookId: String, snapshotId: String): TimeMachineSnapshot? =
        listSnapshots(bookId).firstOrNull { it.id == snapshotId }

    fun findClauseHistory(book: StoryBook, clauseId: String): List<ClauseHistoryEntry> {
        val snapshots = listSnapshots(book.id)
        val out = mutableListOf<ClauseHistoryEntry>()
        var lastSignature: Pair<String, AudioSpan?>? = null

        for (snap in snapshots) {
            val clause = snap.clauses.firstOrNull { it.id == clauseId } ?: continue
            val sig = clause.text to clause.audioSpan
            if (sig == lastSignature) continue
            lastSignature = sig

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
}
