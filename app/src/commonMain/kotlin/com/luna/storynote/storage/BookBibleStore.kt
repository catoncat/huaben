package com.luna.storynote.storage

import com.luna.storynote.model.BookBible
import org.json.JSONObject
import java.io.File

class BookBibleStore(private val booksDir: File) {
    private val lock = Any()

    private fun bibleFile(bookId: String): File =
        File(File(booksDir, bookId), "book_bible.json")

    fun read(bookId: String): BookBible = synchronized(lock) {
        val f = bibleFile(bookId)
        if (!f.isFile) return BookBible(bookId = bookId)
        return try {
            BookJsonCodec.decodeBookBible(JSONObject(f.readText(Charsets.UTF_8)), bookId)
        } catch (_: Exception) {
            BookBible(bookId = bookId)
        }
    }

    fun write(bible: BookBible): BookBible = synchronized(lock) {
        val stamped = bible.copy(
            updatedAtEpochMs = if (bible.updatedAtEpochMs > 0L) bible.updatedAtEpochMs else System.currentTimeMillis(),
        )
        BookJsonCodec.atomicWriteText(
            bibleFile(stamped.bookId),
            BookJsonCodec.encodeBookBible(stamped).toString(2) + "\n",
        )
        stamped
    }

    fun mergeUpdate(
        bookId: String,
        addTraits: List<String> = emptyList(),
        addScenesAndProps: List<String> = emptyList(),
        plotSummary: String? = null,
    ): BookBible = synchronized(lock) {
        val current = read(bookId)
        val mergedTraits = (current.protagonistTraits + addTraits)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val mergedScenes = (current.keyScenesAndProps + addScenesAndProps)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val updatedPlot = plotSummary?.trim()?.takeIf { it.isNotEmpty() } ?: current.plotSummary
        val next = BookBible(
            bookId = bookId,
            protagonistTraits = mergedTraits,
            keyScenesAndProps = mergedScenes,
            plotSummary = updatedPlot,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        BookJsonCodec.atomicWriteText(
            bibleFile(bookId),
            BookJsonCodec.encodeBookBible(next).toString(2) + "\n",
        )
        next
    }
}
