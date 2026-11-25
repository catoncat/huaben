package com.luna.storynote.storage

import com.luna.storynote.audio.WavAudioFiles
import com.luna.storynote.literacy.StoryBookFormatter
import com.luna.storynote.model.AudioTake
import com.luna.storynote.model.BookIllustration
import com.luna.storynote.model.Clause
import com.luna.storynote.model.ClausePhraseSpan
import com.luna.storynote.model.CollectedWordCard
import com.luna.storynote.model.SoftAnnotation
import com.luna.storynote.model.StoryBook
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

typealias BookRepository = StoryBookRepository

class StoryBookRepository(private val rootStorageDir: File) {
    private val lock = Any()
    private val booksDir = File(rootStorageDir, "books").apply { mkdirs() }
    private val stagingDir = File(rootStorageDir, "staging").apply { mkdirs() }
    private val wordBox = WordBoxStore(rootStorageDir)

    val timeMachine = TimeMachineStore(booksDir)
    val bookBibles = BookBibleStore(booksDir)

    fun newId(prefix: String = ""): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val hex = UUID.randomUUID().toString().replace("-", "").take(6)
        return if (prefix.isEmpty()) "$ts-$hex" else "${prefix}_${ts}_$hex"
    }

    fun newStagingWav(): File =
        File(stagingDir, "tmp_${UUID.randomUUID().toString().replace("-", "").take(10)}.wav")

    internal fun isValidBookId(bookId: String): Boolean {
        if (bookId.isBlank() || bookId == "." || bookId == "..") return false
        if (bookId.contains('/') || bookId.contains('\\') || bookId.contains("..") || bookId.indexOf('\u0000') >= 0) {
            return false
        }
        val root = booksDir.canonicalFile.toPath()
        val candidate = File(booksDir, bookId).canonicalFile.toPath()
        return candidate.startsWith(root) && candidate != root
    }

    internal fun isInStagingDir(file: File): Boolean {
        val root = stagingDir.canonicalFile.toPath()
        val candidate = file.canonicalFile.toPath()
        return candidate.startsWith(root) && candidate != root
    }

    private fun resolveBookFolder(bookId: String): File? =
        if (isValidBookId(bookId)) File(booksDir, bookId) else null

    fun newTakeWavFile(bookId: String, takeId: String): File {
        val folder = resolveBookFolder(bookId) ?: throw IllegalArgumentException("Invalid bookId: $bookId")
        val takesDir = File(folder, "takes").apply { mkdirs() }
        return File(takesDir, "$takeId.wav")
    }

    fun createBlankBook(): StoryBook = synchronized(lock) {
        val num = nextBookNumberUnlocked()
        val id = newId()
        val folder = File(booksDir, id).apply {
            mkdirs()
            File(this, "takes").mkdirs()
        }
        val now = System.currentTimeMillis()
        val book = StoryBook(
            id = id,
            bookNumber = num,
            title = "未命名故事",
            titleAudioFileName = "",
            coverColorIndex = (num - 1) % 6,
            emblemIndex = (num - 1) % 6,
            clauses = emptyList(),
            takes = emptyList(),
            createdAtMs = now,
            updatedAtMs = now,
            bookDir = folder,
        )
        saveBookUnlocked(book)
        book
    }

    fun listBooks(): List<StoryBook> = synchronized(lock) {
        val dirs = booksDir.listFiles() ?: return emptyList()
        dirs.filter { it.isDirectory }
            .mapNotNull { readBookUnlocked(it) }
            .sortedWith(compareByDescending<StoryBook> { it.updatedAtMs }.thenByDescending { it.bookNumber })
    }

    fun getBook(bookId: String): StoryBook? = synchronized(lock) {
        val folder = resolveBookFolder(bookId) ?: return null
        readBookUnlocked(folder)
    }

    fun insertUtteranceWithClauses(
        bookId: String,
        afterClauseId: String?,
        rawText: String,
        takeId: String,
        takeWavFile: File?,
        uttStartMs: Long,
        uttEndMs: Long,
        takeDurationMs: Long,
        forceParagraphBreak: Boolean = false,
        actionLabel: String = "写下新句子",
        toolName: String = "append_clause",
    ): Pair<StoryBook, List<Clause>> = synchronized(lock) {
        val folder = resolveBookFolder(bookId) ?: throw IllegalStateException("找不到故事书")
        val book = readBookUnlocked(folder) ?: throw IllegalStateException("找不到故事书")
        timeMachine.recordSnapshot(book, actionLabel, toolName)

        val effTakeId = takeId.ifBlank { "take_${UUID.randomUUID().toString().replace("-", "").take(8)}" }
        val updatedTakes = registerTakeUnlocked(folder, book.takes, effTakeId, takeWavFile, takeDurationMs)
        val hasAudio = updatedTakes.any { it.id == effTakeId }

        val cleanText = StoryBookFormatter.normalizeChinesePunct(rawText)
        val insertedClauses = StoryBookFormatter.buildClausesForTake(
            rawText = cleanText,
            takeId = if (hasAudio) effTakeId else "",
            uttStartMs = uttStartMs,
            uttEndMs = uttEndMs,
            clauseIdGenerator = { "c_${UUID.randomUUID().toString().replace("-", "").take(8)}" },
            finalParagraphBreak = forceParagraphBreak,
        )

        val list = book.clauses.toMutableList()
        val insertPos = when (afterClauseId) {
            "__START__" -> 0
            null -> list.size
            else -> {
                val idx = list.indexOfFirst { it.id == afterClauseId }
                if (idx >= 0) idx + 1 else list.size
            }
        }
        list.addAll(insertPos, insertedClauses)

        val autoTitle = if (book.title == "未命名故事" && cleanText.isNotBlank()) {
            StoryBookFormatter.deriveStoryTitle(listOf(cleanText), book.bookNumber)
        } else {
            book.title
        }

        val updated = book.copy(
            title = autoTitle,
            clauses = list,
            takes = updatedTakes,
            updatedAtMs = System.currentTimeMillis(),
        )
        saveBookUnlocked(updated)
        Pair(updated, insertedClauses)
    }

    fun insertSentenceClip(
        bookId: String,
        afterSentenceId: String?,
        rawText: String,
        tempWavFile: File?,
        durationSec: Double,
        forceParagraphBreak: Boolean = false,
    ): Pair<StoryBook, Clause> {
        val durMs = resolveWavDurMs(tempWavFile, durationSec)
        val takeId = "take_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val (book, clauses) = insertUtteranceWithClauses(
            bookId = bookId,
            afterClauseId = afterSentenceId,
            rawText = rawText,
            takeId = takeId,
            takeWavFile = tempWavFile,
            uttStartMs = 0L,
            uttEndMs = durMs,
            takeDurationMs = durMs,
            forceParagraphBreak = forceParagraphBreak,
        )
        return Pair(book, clauses.lastOrNull() ?: Clause(id = "c_empty", text = rawText))
    }

    fun replaceClauseWithTake(
        bookId: String,
        clauseId: String,
        newRawText: String,
        takeId: String,
        takeWavFile: File?,
        uttStartMs: Long,
        uttEndMs: Long,
        takeDurationMs: Long,
    ): StoryBook? = synchronized(lock) {
        val folder = resolveBookFolder(bookId) ?: return null
        val book = readBookUnlocked(folder) ?: return null
        val idx = book.clauses.indexOfFirst { it.id == clauseId }
        if (idx < 0) return book

        timeMachine.recordSnapshot(book, "重录句子", "rerecord_clause_audio")
        val oldClause = book.clauses[idx]
        val effTakeId = takeId.ifBlank { "take_${UUID.randomUUID().toString().replace("-", "").take(8)}" }
        val updatedTakes = registerTakeUnlocked(folder, book.takes, effTakeId, takeWavFile, takeDurationMs)
        val hasNewAudio = updatedTakes.any { it.id == effTakeId }

        val cleanText = StoryBookFormatter.normalizeChinesePunct(newRawText)
        val newClauses = if (hasNewAudio) {
            StoryBookFormatter.buildClausesForTake(
                rawText = cleanText,
                takeId = effTakeId,
                uttStartMs = uttStartMs,
                uttEndMs = uttEndMs,
                clauseIdGenerator = { subIdx -> if (subIdx == 0) clauseId else "c_${UUID.randomUUID().toString().replace("-", "").take(8)}" },
                finalParagraphBreak = oldClause.paragraphBreakAfter,
            )
        } else {
            val splitTexts = StoryBookFormatter.splitIntoClauses(cleanText).ifEmpty { listOf(cleanText) }
            splitTexts.mapIndexed { subIdx, txt ->
                Clause(
                    id = if (subIdx == 0) clauseId else "c_${UUID.randomUUID().toString().replace("-", "").take(8)}",
                    text = txt,
                    audioSpan = if (subIdx == 0) oldClause.audioSpan else null,
                    paragraphBreakAfter = if (subIdx == splitTexts.lastIndex) oldClause.paragraphBreakAfter else false,
                )
            }
        }

        val list = book.clauses.toMutableList()
        list.removeAt(idx)
        list.addAll(idx, newClauses)
        val updated = book.copy(clauses = list, takes = updatedTakes, updatedAtMs = System.currentTimeMillis())
        saveBookUnlocked(updated)
        updated
    }

    fun replaceSentenceClip(
        bookId: String,
        sentenceId: String,
        newRawText: String,
        tempWavFile: File?,
        durationSec: Double,
    ): StoryBook? {
        val hasFile = tempWavFile != null && tempWavFile.isFile && tempWavFile.length() > 44L
        val durMs = if (hasFile) resolveWavDurMs(tempWavFile, durationSec) else 0L
        val takeId = if (hasFile) "take_${UUID.randomUUID().toString().replace("-", "").take(8)}" else ""
        return replaceClauseWithTake(bookId, sentenceId, newRawText, takeId, tempWavFile, 0L, durMs, durMs)
    }

    fun replaceRangeInClause(
        bookId: String,
        clauseId: String,
        startIdx: Int,
        endIdx: Int,
        replacementWord: String,
        actionLabel: String = "修改词语",
        toolName: String = "replace_span",
    ): StoryBook? = synchronized(lock) {
        val folder = resolveBookFolder(bookId) ?: return null
        val book = readBookUnlocked(folder) ?: return null
        val idx = book.clauses.indexOfFirst { it.id == clauseId }
        if (idx < 0) return book
        val old = book.clauses[idx]
        if (startIdx < 0 || endIdx > old.text.length || startIdx >= endIdx) return book
        val cleanWord = replacementWord.trim().trim('。', '，', '！', '？', '.', ',', '!', '?')
        if (cleanWord.isEmpty()) return book

        timeMachine.recordSnapshot(book, actionLabel, toolName)
        val newText = old.text.substring(0, startIdx) + cleanWord + old.text.substring(endIdx)
        val delta = cleanWord.length - (endIdx - startIdx)
        val adjustedAnns = old.softAnnotations.mapNotNull { ann ->
            val annEnd = ann.charIndex + ann.length
            when {
                annEnd <= startIdx -> ann
                ann.charIndex >= endIdx -> ann.copy(charIndex = (ann.charIndex + delta).coerceAtLeast(0))
                else -> null
            }
        }
        val adjustedPhrases = BookJsonCodec.adjustPhraseSpansAfterReplace(old.contextualPhrases, startIdx, endIdx, delta)
        val list = book.clauses.toMutableList().apply {
            set(idx, old.copy(text = newText, softAnnotations = adjustedAnns, contextualPhrases = adjustedPhrases))
        }
        val updated = book.copy(clauses = list, updatedAtMs = System.currentTimeMillis())
        saveBookUnlocked(updated)
        updated
    }

    fun replaceRangeInSentence(
        bookId: String,
        sentenceId: String,
        startIdx: Int,
        endIdx: Int,
        replacementWord: String,
    ): StoryBook? = replaceRangeInClause(bookId, sentenceId, startIdx, endIdx, replacementWord)

    fun removeClause(bookId: String, clauseId: String): Pair<StoryBook, Clause?>? = synchronized(lock) {
        val folder = resolveBookFolder(bookId) ?: return null
        val book = readBookUnlocked(folder) ?: return null
        val idx = book.clauses.indexOfFirst { it.id == clauseId }
        if (idx < 0) return Pair(book, null)

        timeMachine.recordSnapshot(book, "擦除句子", "erase_clause")
        val removed = book.clauses[idx]
        val list = book.clauses.toMutableList().apply { removeAt(idx) }
        val updated = book.copy(clauses = list, updatedAtMs = System.currentTimeMillis())
        saveBookUnlocked(updated)
        Pair(updated, removed)
    }

    fun removeSentence(bookId: String, sentenceId: String): Pair<StoryBook, Clause?>? =
        removeClause(bookId, sentenceId)

    fun toggleParagraphBreak(bookId: String, clauseId: String): StoryBook? = synchronized(lock) {
        val folder = resolveBookFolder(bookId) ?: return null
        val book = readBookUnlocked(folder) ?: return null
        val idx = book.clauses.indexOfFirst { it.id == clauseId }
        if (idx < 0) return book
        timeMachine.recordSnapshot(book, "调整分段", "toggle_paragraph_break")
        val list = book.clauses.toMutableList()
        list[idx] = list[idx].copy(paragraphBreakAfter = !list[idx].paragraphBreakAfter)
        val updated = book.copy(clauses = list, updatedAtMs = System.currentTimeMillis())
        saveBookUnlocked(updated)
        updated
    }

    fun annotateClauseSoftSuggestions(
        bookId: String,
        clauseId: String,
        annotations: List<SoftAnnotation>,
        contextualPhrases: List<ClausePhraseSpan>? = null,
    ): StoryBook? = synchronized(lock) {
        val folder = resolveBookFolder(bookId) ?: return null
        val book = readBookUnlocked(folder) ?: return null
        val idx = book.clauses.indexOfFirst { it.id == clauseId }
        if (idx < 0) return book
        val old = book.clauses[idx]
        val nextPhrases = contextualPhrases ?: old.contextualPhrases
        if (old.softAnnotations != annotations || old.contextualPhrases != nextPhrases) {
            timeMachine.recordSnapshot(book, "轻批注", "annotate_soft_suggestion")
        }
        val list = book.clauses.toMutableList()
        list[idx] = old.copy(softAnnotations = annotations, contextualPhrases = nextPhrases)
        val updated = book.copy(clauses = list, updatedAtMs = System.currentTimeMillis())
        saveBookUnlocked(updated)
        updated
    }

    fun restoreSnapshot(bookId: String, snapshotId: String): StoryBook? = synchronized(lock) {
        val folder = resolveBookFolder(bookId) ?: return null
        val book = readBookUnlocked(folder) ?: return null
        val snap = timeMachine.getSnapshot(bookId, snapshotId) ?: return null
        timeMachine.recordSnapshot(book, "恢复版本前", "restore_history_snapshot")
        val mergedIllustrations = (book.illustrations + snap.illustrations).distinctBy { it.id }
        val restoredActiveIds = snap.activeIllustrationIds.toMutableMap()
        for (ill in mergedIllustrations) {
            if (!restoredActiveIds.containsKey(ill.pageIndex)) {
                restoredActiveIds[ill.pageIndex] = ""
            }
        }
        val updated = book.copy(
            clauses = snap.clauses,
            takes = (book.takes + snap.takes).distinctBy { it.id },
            illustrations = mergedIllustrations,
            activeIllustrationIds = restoredActiveIds,
            updatedAtMs = System.currentTimeMillis(),
        )
        saveBookUnlocked(updated)
        updated
    }

    fun restoreClauseFromSnapshot(bookId: String, snapshotId: String, clauseId: String): StoryBook? = synchronized(lock) {
        val folder = resolveBookFolder(bookId) ?: return null
        val book = readBookUnlocked(folder) ?: return null
        val snap = timeMachine.getSnapshot(bookId, snapshotId) ?: return null
        val targetClause = snap.clauses.firstOrNull { it.id == clauseId } ?: return null

        timeMachine.recordSnapshot(book, "恢复单句前", "restore_history_snapshot")
        val neededTake = targetClause.audioSpan?.takeId?.let { tid -> snap.takes.firstOrNull { it.id == tid } }
        val nextTakes = if (neededTake != null && book.takes.none { it.id == neededTake.id }) {
            book.takes + neededTake
        } else {
            book.takes
        }
        val list = book.clauses.toMutableList()
        val curIdx = list.indexOfFirst { it.id == clauseId }
        if (curIdx >= 0) {
            list[curIdx] = targetClause
        } else {
            list.add(snap.clauses.indexOfFirst { it.id == clauseId }.coerceIn(0, list.size), targetClause)
        }
        val updated = book.copy(clauses = list, takes = nextTakes, updatedAtMs = System.currentTimeMillis())
        saveBookUnlocked(updated)
        updated
    }

    fun attachIllustration(bookId: String, pageIndex: Int, imageFile: File, promptUsed: String): StoryBook? = synchronized(lock) {
        val folder = resolveBookFolder(bookId) ?: return null
        val book = readBookUnlocked(folder) ?: return null
        timeMachine.recordSnapshot(book, "生成插画", "attach_page_illustration")
        val illId = "ill_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val relPath = "illustrations/$illId.png"
        val dest = File(folder, relPath).apply { parentFile?.mkdirs() }
        if (imageFile.absolutePath != dest.absolutePath) imageFile.copyTo(dest, overwrite = true)
        val item = BookIllustration(illId, pageIndex, relPath, promptUsed, System.currentTimeMillis())
        val nextMap = book.activeIllustrationIds.toMutableMap().apply { put(pageIndex, illId) }
        val updated = book.copy(
            illustrations = book.illustrations + item,
            activeIllustrationIds = nextMap,
            updatedAtMs = System.currentTimeMillis(),
        )
        saveBookUnlocked(updated)
        updated
    }

    fun selectIllustration(bookId: String, pageIndex: Int, illustrationId: String): StoryBook? = synchronized(lock) {
        val folder = resolveBookFolder(bookId) ?: return null
        val book = readBookUnlocked(folder) ?: return null
        if (book.illustrations.none { it.id == illustrationId && it.pageIndex == pageIndex }) return book
        timeMachine.recordSnapshot(book, "切换插画版本", "select_page_illustration")
        val nextMap = book.activeIllustrationIds.toMutableMap().apply { put(pageIndex, illustrationId) }
        val updated = book.copy(activeIllustrationIds = nextMap, updatedAtMs = System.currentTimeMillis())
        saveBookUnlocked(updated)
        updated
    }

    fun updateBookTitleAndCover(
        bookId: String,
        newTitle: String? = null,
        titleTempWav: File? = null,
        coverColorIndex: Int? = null,
        emblemIndex: Int? = null,
    ): StoryBook? = synchronized(lock) {
        val folder = resolveBookFolder(bookId) ?: return null
        val book = readBookUnlocked(folder) ?: return null
        var titleRel = book.titleAudioFileName
        if (titleTempWav != null && titleTempWav.isFile && titleTempWav.length() > 44L) {
            val dest = File(folder, "title.wav")
            if (titleTempWav.absolutePath != dest.absolutePath && !titleTempWav.renameTo(dest)) {
                titleTempWav.copyTo(dest, overwrite = true)
                titleTempWav.delete()
            }
            titleRel = "title.wav"
        }
        val cleanTitle = newTitle?.trim()?.trim('。', '，', '！', '？', '《', '》', '“', '”')?.take(18)
        val updated = book.copy(
            title = if (!cleanTitle.isNullOrEmpty()) cleanTitle else book.title,
            titleAudioFileName = titleRel,
            coverColorIndex = coverColorIndex ?: book.coverColorIndex,
            emblemIndex = emblemIndex ?: book.emblemIndex,
            updatedAtMs = System.currentTimeMillis(),
        )
        saveBookUnlocked(updated)
        updated
    }

    fun deleteBook(bookId: String): Boolean = synchronized(lock) {
        val folder = resolveBookFolder(bookId) ?: return false
        folder.isDirectory && folder.deleteRecursively()
    }

    fun listCollectedWords(): List<CollectedWordCard> = wordBox.listCollectedWords()

    fun toggleCollectWord(
        word: String,
        pinyins: List<String>,
        book: StoryBook,
        clause: Clause,
    ): Pair<List<CollectedWordCard>, Boolean> = wordBox.toggleCollectWord(word, pinyins, book, clause)

    fun addWordBoxCard(
        word: String,
        pinyins: List<String>,
        book: StoryBook,
        clause: Clause,
    ): Pair<List<CollectedWordCard>, Boolean> = wordBox.addCard(word, pinyins, book, clause)

    private fun resolveWavDurMs(wavFile: File?, fallbackSec: Double): Long =
        if (wavFile != null && wavFile.isFile && wavFile.length() > 44L) {
            WavAudioFiles.wavDurationMs(wavFile).takeIf { it > 0L } ?: (fallbackSec * 1000.0).toLong().coerceAtLeast(0L)
        } else {
            (fallbackSec * 1000.0).toLong().coerceAtLeast(0L)
        }

    private fun registerTakeUnlocked(
        bookFolder: File,
        existingTakes: List<AudioTake>,
        takeId: String,
        wavFile: File?,
        durationMs: Long,
    ): List<AudioTake> {
        if (wavFile == null || !wavFile.isFile || wavFile.length() <= 44L) return existingTakes
        val takesDir = File(bookFolder, "takes").apply { mkdirs() }
        val dest = File(takesDir, "$takeId.wav")
        if (wavFile.absolutePath != dest.absolutePath && !wavFile.renameTo(dest)) {
            wavFile.copyTo(dest, overwrite = true)
        }
        val measuredDur = WavAudioFiles.wavDurationMs(dest).takeIf { it > 0L } ?: durationMs.coerceAtLeast(0L)
        val rel = "takes/$takeId.wav"
        val existingIdx = existingTakes.indexOfFirst { it.id == takeId }
        val newTake = AudioTake(
            id = takeId,
            wavFileName = rel,
            durationMs = measuredDur,
            createdAtEpochMs = existingTakes.getOrNull(existingIdx)?.createdAtEpochMs ?: System.currentTimeMillis(),
        )
        return if (existingIdx >= 0) existingTakes.toMutableList().apply { set(existingIdx, newTake) } else existingTakes + newTake
    }

    private fun nextBookNumberUnlocked(): Int {
        val dirs = booksDir.listFiles() ?: return 1
        var maxNum = 0
        for (d in dirs) {
            if (!d.isDirectory) continue
            val b = readBookUnlocked(d) ?: continue
            if (b.bookNumber > maxNum) maxNum = b.bookNumber
        }
        return maxNum + 1
    }

    private fun saveBookUnlocked(book: StoryBook) {
        book.bookDir.mkdirs()
        BookJsonCodec.atomicWriteText(File(book.bookDir, "book.json"), BookJsonCodec.encodeBookV4(book).toString(2) + "\n")
        BookJsonCodec.atomicWriteText(File(book.bookDir, "story.md"), BookJsonCodec.renderBookMarkdown(book))
    }

    private fun readBookUnlocked(folder: File): StoryBook? {
        val file = File(folder, "book.json")
        if (!file.isFile) return null
        return try {
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            val version = obj.optInt("version", 3)
            val id = obj.optString("id", folder.name)
            val num = obj.optInt("book_number", 1)
            val createdAt = obj.optLong("created_at_ms", folder.lastModified())
            val updatedAt = obj.optLong("updated_at_ms", folder.lastModified())

            if (version < 4 || (!obj.has("clauses") && obj.has("sentences"))) {
                val migrated = BookMigrationV3.migrateV3ToV4(folder, obj, id, num, createdAt, updatedAt)
                saveBookUnlocked(migrated)
                return migrated
            }

            val cArr = obj.optJSONArray("clauses")
            val clauses = if (cArr != null) {
                (0 until cArr.length()).mapNotNull { i -> cArr.optJSONObject(i)?.let { BookJsonCodec.decodeClause(it, i) } }
            } else {
                emptyList()
            }
            val tArr = obj.optJSONArray("takes")
            val takes = if (tArr != null) {
                (0 until tArr.length()).mapNotNull { i -> tArr.optJSONObject(i)?.let(BookJsonCodec::decodeTake) }
            } else {
                emptyList()
            }
            val iArr = obj.optJSONArray("illustrations")
            val ills = if (iArr != null) {
                (0 until iArr.length()).mapNotNull { i -> iArr.optJSONObject(i)?.let(BookJsonCodec::decodeIllustration) }
            } else {
                emptyList()
            }
            StoryBook(
                id = id,
                bookNumber = num,
                title = obj.optString("title", "未命名故事"),
                titleAudioFileName = obj.optString("title_audio_file", ""),
                coverColorIndex = obj.optInt("cover_color_index", 0),
                emblemIndex = obj.optInt("emblem_index", 0),
                clauses = clauses,
                takes = takes,
                illustrations = ills,
                activeIllustrationIds = BookJsonCodec.decodeActiveIllustrations(obj.optJSONObject("active_illustrations")),
                createdAtMs = createdAt,
                updatedAtMs = updatedAt,
                bookDir = folder,
            )
        } catch (_: Exception) {
            null
        }
    }
}
