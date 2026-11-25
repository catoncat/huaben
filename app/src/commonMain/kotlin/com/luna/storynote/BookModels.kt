package com.luna.storynote

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class SentenceClip(
    val id: String,
    val text: String,
    val audioFileName: String, // relative to book folder, e.g. "clips/s_1a2b3c.wav", or "" if none
    val durationSec: Double,
    val paragraphBreakAfter: Boolean = false,
)

data class StoryParagraphGroup(
    val sentences: List<SentenceClip>,
)

data class BookPage(
    val pageIndex: Int, // 0-based
    val paragraphs: List<StoryParagraphGroup>,
) {
    val sentenceIds: List<String>
        get() = paragraphs.flatMap { g -> g.sentences.map { it.id } }
}

data class StoryBook(
    val id: String,
    val bookNumber: Int,
    val title: String,
    val titleAudioFileName: String,
    val coverColorIndex: Int,
    val emblemIndex: Int,
    val sentences: List<SentenceClip>,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val bookDir: File,
) {
    val totalDurationSec: Double
        get() = sentences.sumOf { it.durationSec }

    val totalChars: Int
        get() = sentences.sumOf { s -> s.text.count { StoryBookFormatter.isCjk(it) } }

    fun audioFileFor(clip: SentenceClip): File? {
        if (clip.audioFileName.isBlank()) return null
        val f = File(bookDir, clip.audioFileName)
        return if (f.isFile) f else null
    }

    fun titleAudioFile(): File? {
        if (titleAudioFileName.isBlank()) return null
        val f = File(bookDir, titleAudioFileName)
        return if (f.isFile) f else null
    }

    /**
     * Deterministically groups `sentences` into breathable Grade-1 short paragraphs,
     * then paginates them into real book pages (Page 1 has title headroom -> ~68 chars max;
     * subsequent pages -> ~102 chars / 3 short paragraphs max).
     */
    fun paginate(): List<BookPage> {
        if (sentences.isEmpty()) {
            return listOf(BookPage(pageIndex = 0, paragraphs = emptyList()))
        }

        // 1. Group sentences into short breathable paragraphs ("一小段一小段")
        val paragraphs = mutableListOf<StoryParagraphGroup>()
        val curPara = mutableListOf<SentenceClip>()
        var curParaChars = 0

        for (i in sentences.indices) {
            val s = sentences[i]
            val startsTransition = PARAGRAPH_TRANSITIONS.any { s.text.trimStart('“', ' ').startsWith(it) }
            if (curPara.isNotEmpty() && startsTransition && curParaChars >= 16) {
                paragraphs.add(StoryParagraphGroup(curPara.toList()))
                curPara.clear()
                curParaChars = 0
            }

            curPara.add(s)
            curParaChars += s.text.length

            val shouldBreakPara = s.paragraphBreakAfter ||
                curParaChars >= 40 ||
                curPara.size >= 2 && curParaChars >= 26
            if (shouldBreakPara) {
                paragraphs.add(StoryParagraphGroup(curPara.toList()))
                curPara.clear()
                curParaChars = 0
            }
        }
        if (curPara.isNotEmpty()) {
            paragraphs.add(StoryParagraphGroup(curPara.toList()))
        }

        // 2. Pack paragraphs onto physical book pages
        val pages = mutableListOf<BookPage>()
        val curPageParas = mutableListOf<StoryParagraphGroup>()
        var curPageChars = 0

        for (para in paragraphs) {
            val paraChars = para.sentences.sumOf { it.text.length }
            val isFirstPage = pages.isEmpty()
            val maxChars = if (isFirstPage) 80 else 84
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

    fun pageIndexOfSentence(sentenceId: String?): Int {
        if (sentenceId == null) return (paginate().size - 1).coerceAtLeast(0)
        val pages = paginate()
        val idx = pages.indexOfFirst { p -> p.sentenceIds.contains(sentenceId) }
        return if (idx >= 0) idx else (pages.size - 1).coerceAtLeast(0)
    }

    companion object {
        private val PARAGRAPH_TRANSITIONS = listOf(
            "从前", "有一天", "突然", "忽然", "这时候", "第二天", "后来", "过了一会儿", "最后", "终于", "原来",
        )
    }
}

data class CollectedWordCard(
    val word: String,
    val pinyins: List<String>,
    val bookId: String,
    val sentenceId: String,
    val sentenceText: String,
    val sentenceAudioPath: String,
    val collectedAtMs: Long,
)

class StoryBookRepository(private val rootStorageDir: File) {
    private val lock = Any()
    private val booksDir = File(rootStorageDir, "books").apply { mkdirs() }
    private val stagingDir = File(rootStorageDir, "staging").apply { mkdirs() }
    private val wordBoxFile = File(rootStorageDir, "word_box.json")

    fun newId(prefix: String = ""): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val hex = UUID.randomUUID().toString().replace("-", "").take(6)
        return if (prefix.isEmpty()) "$ts-$hex" else "${prefix}_${ts}_$hex"
    }

    fun newStagingWav(): File =
        File(stagingDir, "tmp_${UUID.randomUUID().toString().replace("-", "").take(10)}.wav")

    fun createBlankBook(): StoryBook = synchronized(lock) {
        val num = nextBookNumberUnlocked()
        val id = newId()
        val folder = File(booksDir, id).apply {
            mkdirs()
            File(this, "clips").mkdirs()
        }
        val now = System.currentTimeMillis()
        val book = StoryBook(
            id = id,
            bookNumber = num,
            title = "未命名故事",
            titleAudioFileName = "",
            coverColorIndex = (num - 1) % 6,
            emblemIndex = (num - 1) % 6,
            sentences = emptyList(),
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
        readBookUnlocked(File(booksDir, bookId))
    }

    /**
     * Inserts a new recorded sentence right after `afterSentenceId` (or at the end if null;
     * pass `afterSentenceId = "__START__"` to insert at index 0).
     */
    fun insertSentenceClip(
        bookId: String,
        afterSentenceId: String?,
        rawText: String,
        tempWavFile: File?,
        durationSec: Double,
        forceParagraphBreak: Boolean = false,
    ): Pair<StoryBook, SentenceClip> = synchronized(lock) {
        val folder = File(booksDir, bookId)
        val book = readBookUnlocked(folder) ?: throw IllegalStateException("找不到故事书")
        val clipId = "s_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        var relAudio = ""
        if (tempWavFile != null && tempWavFile.isFile && tempWavFile.length() > 44L) {
            val clipsDir = File(folder, "clips").apply { mkdirs() }
            val dest = File(clipsDir, "$clipId.wav")
            if (!tempWavFile.renameTo(dest)) {
                tempWavFile.copyTo(dest, overwrite = true)
                tempWavFile.delete()
            }
            relAudio = "clips/$clipId.wav"
        }

        val cleanText = StoryBookFormatter.normalizeChinesePunct(rawText)
        val clip = SentenceClip(
            id = clipId,
            text = cleanText,
            audioFileName = relAudio,
            durationSec = durationSec,
            paragraphBreakAfter = forceParagraphBreak,
        )

        val list = book.sentences.toMutableList()
        val insertPos = when (afterSentenceId) {
            "__START__" -> 0
            null -> list.size
            else -> {
                val idx = list.indexOfFirst { it.id == afterSentenceId }
                if (idx >= 0) idx + 1 else list.size
            }
        }
        list.add(insertPos, clip)

        val autoTitle = if (book.title == "未命名故事" && cleanText.isNotBlank()) {
            StoryBookFormatter.deriveStoryTitle(listOf(cleanText), book.bookNumber)
        } else {
            book.title
        }

        val updated = book.copy(
            title = autoTitle,
            sentences = list,
            updatedAtMs = System.currentTimeMillis(),
        )
        saveBookUnlocked(updated)
        Pair(updated, clip)
    }

    /** Replaces an existing sentence's text and audio clip (when child re-speaks that sentence). */
    fun replaceSentenceClip(
        bookId: String,
        sentenceId: String,
        newRawText: String,
        tempWavFile: File?,
        durationSec: Double,
    ): StoryBook? = synchronized(lock) {
        val folder = File(booksDir, bookId)
        val book = readBookUnlocked(folder) ?: return null
        val idx = book.sentences.indexOfFirst { it.id == sentenceId }
        if (idx < 0) return book

        val oldClip = book.sentences[idx]
        var relAudio = oldClip.audioFileName
        var effDur = oldClip.durationSec

        if (tempWavFile != null && tempWavFile.isFile && tempWavFile.length() > 44L) {
            book.audioFileFor(oldClip)?.delete()
            val newAudioId = "s_${UUID.randomUUID().toString().replace("-", "").take(8)}"
            val dest = File(File(folder, "clips").apply { mkdirs() }, "$newAudioId.wav")
            if (!tempWavFile.renameTo(dest)) {
                tempWavFile.copyTo(dest, overwrite = true)
                tempWavFile.delete()
            }
            relAudio = "clips/$newAudioId.wav"
            effDur = durationSec
        }

        val cleanText = StoryBookFormatter.normalizeChinesePunct(newRawText)
        val replaced = oldClip.copy(
            text = cleanText,
            audioFileName = relAudio,
            durationSec = effDur,
        )
        val list = book.sentences.toMutableList().apply { set(idx, replaced) }
        val updated = book.copy(sentences = list, updatedAtMs = System.currentTimeMillis())
        saveBookUnlocked(updated)
        updated
    }

    /** Updates a word range `[startIdx, endIdx)` inside a single sentence (homophone fix or word re-speak). */
    fun replaceRangeInSentence(
        bookId: String,
        sentenceId: String,
        startIdx: Int,
        endIdx: Int,
        replacementWord: String,
    ): StoryBook? = synchronized(lock) {
        val folder = File(booksDir, bookId)
        val book = readBookUnlocked(folder) ?: return null
        val idx = book.sentences.indexOfFirst { it.id == sentenceId }
        if (idx < 0) return book
        val old = book.sentences[idx]
        if (startIdx < 0 || endIdx > old.text.length || startIdx >= endIdx) return book
        val cleanWord = replacementWord.trim().trim('。', '，', '！', '？', '.', ',', '!', '?')
        if (cleanWord.isEmpty()) return book

        val newSentenceText = old.text.substring(0, startIdx) + cleanWord + old.text.substring(endIdx)
        val list = book.sentences.toMutableList().apply {
            set(idx, old.copy(text = newSentenceText))
        }
        val updated = book.copy(sentences = list, updatedAtMs = System.currentTimeMillis())
        saveBookUnlocked(updated)
        updated
    }

    fun removeSentence(bookId: String, sentenceId: String): Pair<StoryBook, SentenceClip?>? = synchronized(lock) {
        val folder = File(booksDir, bookId)
        val book = readBookUnlocked(folder) ?: return null
        val idx = book.sentences.indexOfFirst { it.id == sentenceId }
        if (idx < 0) return Pair(book, null)
        val removed = book.sentences[idx]
        val list = book.sentences.toMutableList().apply { removeAt(idx) }
        val updated = book.copy(sentences = list, updatedAtMs = System.currentTimeMillis())
        saveBookUnlocked(updated)
        Pair(updated, removed)
    }

    fun updateBookTitleAndCover(
        bookId: String,
        newTitle: String? = null,
        titleTempWav: File? = null,
        coverColorIndex: Int? = null,
        emblemIndex: Int? = null,
    ): StoryBook? = synchronized(lock) {
        val folder = File(booksDir, bookId)
        val book = readBookUnlocked(folder) ?: return null
        var titleRel = book.titleAudioFileName
        if (titleTempWav != null && titleTempWav.isFile && titleTempWav.length() > 44L) {
            val dest = File(folder, "title.wav")
            if (!titleTempWav.renameTo(dest)) {
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
        val folder = File(booksDir, bookId)
        if (!folder.isDirectory) return false
        folder.deleteRecursively()
    }

    // --- Word Treasure Box (`我的字词盒`) ---

    fun listCollectedWords(): List<CollectedWordCard> = synchronized(lock) {
        readWordBoxUnlocked().sortedByDescending { it.collectedAtMs }
    }

    fun toggleCollectWord(
        word: String,
        pinyins: List<String>,
        book: StoryBook,
        sentence: SentenceClip,
    ): Pair<List<CollectedWordCard>, Boolean> = synchronized(lock) {
        val current = readWordBoxUnlocked().toMutableList()
        val existingIdx = current.indexOfFirst { it.word == word }
        val added = if (existingIdx >= 0) {
            current.removeAt(existingIdx)
            false
        } else {
            val audioPath = book.audioFileFor(sentence)?.absolutePath.orEmpty()
            current.add(
                0,
                CollectedWordCard(
                    word = word,
                    pinyins = pinyins,
                    bookId = book.id,
                    sentenceId = sentence.id,
                    sentenceText = sentence.text,
                    sentenceAudioPath = audioPath,
                    collectedAtMs = System.currentTimeMillis(),
                ),
            )
            true
        }
        writeWordBoxUnlocked(current)
        Pair(current, added)
    }

    private fun readWordBoxUnlocked(): List<CollectedWordCard> {
        if (!wordBoxFile.isFile) return emptyList()
        return try {
            val arr = JSONArray(wordBoxFile.readText(Charsets.UTF_8))
            val out = ArrayList<CollectedWordCard>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pyArr = o.optJSONArray("pinyins")
                val pys = ArrayList<String>()
                if (pyArr != null) {
                    for (j in 0 until pyArr.length()) pys.add(pyArr.optString(j, ""))
                }
                val word = o.optString("word", "").trim()
                if (word.isNotEmpty()) {
                    out.add(
                        CollectedWordCard(
                            word = word,
                            pinyins = pys,
                            bookId = o.optString("book_id", ""),
                            sentenceId = o.optString("sentence_id", ""),
                            sentenceText = o.optString("sentence_text", ""),
                            sentenceAudioPath = o.optString("sentence_audio_path", ""),
                            collectedAtMs = o.optLong("collected_at_ms", 0L),
                        ),
                    )
                }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeWordBoxUnlocked(items: List<CollectedWordCard>) {
        val arr = JSONArray()
        for (c in items) {
            val pyArr = JSONArray()
            for (p in c.pinyins) pyArr.put(p)
            arr.put(
                JSONObject()
                    .put("word", c.word)
                    .put("pinyins", pyArr)
                    .put("book_id", c.bookId)
                    .put("sentence_id", c.sentenceId)
                    .put("sentence_text", c.sentenceText)
                    .put("sentence_audio_path", c.sentenceAudioPath)
                    .put("collected_at_ms", c.collectedAtMs),
            )
        }
        atomicWriteText(wordBoxFile, arr.toString(2) + "\n")
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
        val sentArr = JSONArray()
        for (s in book.sentences) {
            sentArr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("text", s.text)
                    .put("audio_file", s.audioFileName)
                    .put("duration_sec", s.durationSec)
                    .put("paragraph_break_after", s.paragraphBreakAfter),
            )
        }
        val meta = JSONObject()
            .put("version", 3)
            .put("id", book.id)
            .put("book_number", book.bookNumber)
            .put("title", book.title)
            .put("title_audio_file", book.titleAudioFileName)
            .put("cover_color_index", book.coverColorIndex)
            .put("emblem_index", book.emblemIndex)
            .put("sentences", sentArr)
            .put("created_at_ms", book.createdAtMs)
            .put("updated_at_ms", book.updatedAtMs)
        atomicWriteText(File(book.bookDir, "book.json"), meta.toString(2) + "\n")
        atomicWriteText(File(book.bookDir, "story.md"), renderBookMarkdown(book))
    }

    private fun readBookUnlocked(folder: File): StoryBook? {
        val file = File(folder, "book.json")
        if (!file.isFile) return null
        return try {
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            val id = obj.optString("id", folder.name)
            val num = obj.optInt("book_number", 1)
            val sents = mutableListOf<SentenceClip>()
            val arr = obj.optJSONArray("sentences")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val so = arr.optJSONObject(i) ?: continue
                    val txt = so.optString("text", "").trim()
                    if (txt.isEmpty()) continue
                    sents.add(
                        SentenceClip(
                            id = so.optString("id", "s_$i"),
                            text = txt,
                            audioFileName = so.optString("audio_file", ""),
                            durationSec = so.optDouble("duration_sec", 0.0),
                            paragraphBreakAfter = so.optBoolean("paragraph_break_after", false),
                        ),
                    )
                }
            }
            StoryBook(
                id = id,
                bookNumber = num,
                title = obj.optString("title", "未命名故事"),
                titleAudioFileName = obj.optString("title_audio_file", ""),
                coverColorIndex = obj.optInt("cover_color_index", 0),
                emblemIndex = obj.optInt("emblem_index", 0),
                sentences = sents,
                createdAtMs = obj.optLong("created_at_ms", folder.lastModified()),
                updatedAtMs = obj.optLong("updated_at_ms", folder.lastModified()),
                bookDir = folder,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun renderBookMarkdown(book: StoryBook): String {
        val pages = book.paginate()
        return buildString {
            appendLine("# ${book.title}")
            appendLine()
            for (page in pages) {
                for (para in page.paragraphs) {
                    appendLine(para.sentences.joinToString("") { it.text })
                    appendLine()
                }
            }
        }
    }

    private fun atomicWriteText(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
