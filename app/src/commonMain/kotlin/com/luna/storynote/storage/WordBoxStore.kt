package com.luna.storynote.storage

import com.luna.storynote.model.Clause
import com.luna.storynote.model.CollectedWordCard
import com.luna.storynote.model.StoryBook
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class WordBoxStore(private val rootStorageDir: File) {
    private val lock = Any()
    private val wordBoxFile = File(rootStorageDir, "word_box.json")

    fun listCollectedWords(): List<CollectedWordCard> = synchronized(lock) {
        readUnlocked().sortedByDescending { it.collectedAtMs }
    }

    fun toggleCollectWord(
        word: String,
        pinyins: List<String>,
        book: StoryBook,
        clause: Clause,
    ): Pair<List<CollectedWordCard>, Boolean> = synchronized(lock) {
        val current = readUnlocked().toMutableList()
        val existingIdx = current.indexOfFirst { it.word == word }
        val added = if (existingIdx >= 0) {
            current.removeAt(existingIdx)
            false
        } else {
            current.add(0, buildCard(word, pinyins, book, clause))
            true
        }
        writeUnlocked(current)
        Pair(current, added)
    }

    fun addCard(
        word: String,
        pinyins: List<String>,
        book: StoryBook,
        clause: Clause,
    ): Pair<List<CollectedWordCard>, Boolean> = synchronized(lock) {
        val current = readUnlocked().toMutableList()
        if (current.any { it.word == word }) {
            return Pair(current, false)
        }
        current.add(0, buildCard(word, pinyins, book, clause))
        writeUnlocked(current)
        Pair(current, true)
    }

    private fun buildCard(
        word: String,
        pinyins: List<String>,
        book: StoryBook,
        clause: Clause,
    ): CollectedWordCard {
        val resolved = book.resolveAudio(clause)
        val audioPath = resolved?.first?.absolutePath.orEmpty()
        val startMs = resolved?.second?.startMs ?: 0L
        val endMs = resolved?.second?.endMs ?: 0L
        return CollectedWordCard(
            word = word,
            pinyins = pinyins,
            bookId = book.id,
            clauseId = clause.id,
            clauseText = clause.text,
            takeAudioPath = audioPath,
            takeStartMs = startMs,
            takeEndMs = endMs,
            collectedAtMs = System.currentTimeMillis(),
        )
    }

    private fun readUnlocked(): List<CollectedWordCard> {
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
                            clauseId = o.optString("clause_id", "").ifEmpty { o.optString("sentence_id", "") },
                            clauseText = o.optString("clause_text", "").ifEmpty { o.optString("sentence_text", "") },
                            takeAudioPath = o.optString("take_audio_path", "").ifEmpty { o.optString("sentence_audio_path", "") },
                            takeStartMs = o.optLong("take_start_ms", 0L),
                            takeEndMs = o.optLong("take_end_ms", 0L),
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

    private fun writeUnlocked(items: List<CollectedWordCard>) {
        val arr = JSONArray()
        for (c in items) {
            val pyArr = JSONArray()
            for (p in c.pinyins) pyArr.put(p)
            arr.put(
                JSONObject()
                    .put("word", c.word)
                    .put("pinyins", pyArr)
                    .put("book_id", c.bookId)
                    .put("clause_id", c.clauseId)
                    .put("clause_text", c.clauseText)
                    .put("take_audio_path", c.takeAudioPath)
                    .put("take_start_ms", c.takeStartMs)
                    .put("take_end_ms", c.takeEndMs)
                    .put("sentence_id", c.clauseId)
                    .put("sentence_text", c.clauseText)
                    .put("sentence_audio_path", c.takeAudioPath)
                    .put("collected_at_ms", c.collectedAtMs),
            )
        }
        BookJsonCodec.atomicWriteText(wordBoxFile, arr.toString(2) + "\n")
    }
}
