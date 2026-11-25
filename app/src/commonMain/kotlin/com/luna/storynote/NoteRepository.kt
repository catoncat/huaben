package com.luna.storynote

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class StoryNote(
    val id: String,
    val storyNumber: Int,
    val coverColorIndex: Int,
    val title: String,
    val paragraphs: List<String>,
    val durationSec: Double,
    val rollovers: Int,
    val asrError: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val audioFile: File,
    val markdownFile: File,
) {
    val fullText: String
        get() = paragraphs.joinToString("\n\n")

    val charCount: Int
        get() = paragraphs.sumOf { p -> p.count { StoryBookFormatter.isCjk(it) } }
}

class NoteRepository(rootStorageDir: File) {
    private val lock = Any()
    private val notesDir = File(rootStorageDir, "notes").apply { mkdirs() }
    private val stagingDir = File(rootStorageDir, "staging").apply { mkdirs() }

    fun newRecordingId(): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val shortHex = UUID.randomUUID().toString().replace("-", "").take(6)
        return "$ts-$shortHex"
    }

    fun stagingWavFile(recId: String): File =
        File(File(stagingDir, recId).apply { mkdirs() }, "audio.wav")

    fun discardStaging(recId: String) {
        File(stagingDir, recId).deleteRecursively()
    }

    fun finalizeNewNote(
        recId: String,
        durationSec: Double,
        rawRecognizedText: String,
        rollovers: Int,
        asrError: String,
    ): StoryNote = synchronized(lock) {
        val srcFolder = File(stagingDir, recId)
        val srcWav = File(srcFolder, "audio.wav")
        if (!srcWav.isFile) throw IllegalStateException("未找到录音文件")

        val nextNum = nextStoryNumberUnlocked()
        val paragraphs = StoryBookFormatter.formatIntoParagraphs(rawRecognizedText)
        val title = StoryBookFormatter.deriveStoryTitle(paragraphs, nextNum)
        val now = System.currentTimeMillis()

        val targetFolder = File(notesDir, recId).apply { mkdirs() }
        val targetWav = File(targetFolder, "audio.wav")
        if (!srcWav.renameTo(targetWav)) {
            srcWav.copyTo(targetWav, overwrite = true)
        }
        srcFolder.deleteRecursively()

        val note = StoryNote(
            id = recId,
            storyNumber = nextNum,
            coverColorIndex = (nextNum - 1) % 6,
            title = title,
            paragraphs = paragraphs,
            durationSec = durationSec,
            rollovers = rollovers,
            asrError = if (paragraphs.isEmpty()) asrError else "",
            createdAtMs = now,
            updatedAtMs = now,
            audioFile = targetWav,
            markdownFile = File(targetFolder, "note.md"),
        )
        persistNoteUnlocked(targetFolder, note)
        note
    }

    /** Appends newly recorded audio & paragraphs to an existing story ("接着往下讲"). */
    fun appendToExistingNote(
        noteId: String,
        stagingRecId: String,
        appendedRawText: String,
        extraRollovers: Int,
        asrError: String,
    ): StoryNote = synchronized(lock) {
        val folder = File(notesDir, noteId)
        val existing = readNoteUnlocked(folder) ?: throw IllegalStateException("找不到原故事")
        val srcFolder = File(stagingDir, stagingRecId)
        val srcWav = File(srcFolder, "audio.wav")
        if (!srcWav.isFile) throw IllegalStateException("未找到续讲录音文件")

        val totalDur = WavFileStreamWriter.appendWavFiles(existing.audioFile, srcWav, pauseMs = 550)
        srcFolder.deleteRecursively()

        val extraParagraphs = StoryBookFormatter.formatIntoParagraphs(appendedRawText)
        val mergedParagraphs = existing.paragraphs + extraParagraphs
        val updatedTitle = if (existing.title.startsWith("第") && mergedParagraphs.isNotEmpty()) {
            StoryBookFormatter.deriveStoryTitle(mergedParagraphs, existing.storyNumber)
        } else {
            existing.title
        }

        val updated = existing.copy(
            title = updatedTitle,
            paragraphs = mergedParagraphs,
            durationSec = totalDur,
            rollovers = existing.rollovers + extraRollovers,
            asrError = if (extraParagraphs.isEmpty() && asrError.isNotEmpty()) asrError else "",
            updatedAtMs = System.currentTimeMillis(),
        )
        persistNoteUnlocked(folder, updated)
        updated
    }

    fun listNotes(): List<StoryNote> = synchronized(lock) {
        val children = notesDir.listFiles() ?: return emptyList()
        children
            .filter { it.isDirectory }
            .mapNotNull { readNoteUnlocked(it) }
            .sortedWith(compareByDescending<StoryNote> { it.createdAtMs }.thenByDescending { it.storyNumber })
    }

    fun getNote(noteId: String): StoryNote? = synchronized(lock) {
        readNoteUnlocked(File(notesDir, noteId))
    }

    fun updateNote(
        noteId: String,
        newTitle: String? = null,
        newRawText: String? = null,
        newCoverColorIndex: Int? = null,
        newRollovers: Int? = null,
        newAsrError: String? = null,
    ): StoryNote? = synchronized(lock) {
        val folder = File(notesDir, noteId)
        val existing = readNoteUnlocked(folder) ?: return null
        val updatedParagraphs = if (newRawText != null) {
            StoryBookFormatter.formatIntoParagraphs(newRawText)
        } else {
            existing.paragraphs
        }
        val updatedTitle = when {
            !newTitle.isNullOrBlank() -> newTitle.trim().take(28)
            newRawText != null && existing.title.startsWith("第") ->
                StoryBookFormatter.deriveStoryTitle(updatedParagraphs, existing.storyNumber)
            else -> existing.title
        }
        val updated = existing.copy(
            title = updatedTitle,
            paragraphs = updatedParagraphs,
            coverColorIndex = newCoverColorIndex ?: existing.coverColorIndex,
            rollovers = newRollovers ?: existing.rollovers,
            asrError = newAsrError ?: existing.asrError,
            updatedAtMs = System.currentTimeMillis(),
        )
        persistNoteUnlocked(folder, updated)
        updated
    }

    fun deleteNote(noteId: String): Boolean = synchronized(lock) {
        val folder = File(notesDir, noteId)
        if (!folder.isDirectory) return false
        folder.deleteRecursively()
    }

    private fun nextStoryNumberUnlocked(): Int {
        val children = notesDir.listFiles() ?: return 1
        var maxNum = 0
        for (dir in children) {
            if (!dir.isDirectory) continue
            val note = readNoteUnlocked(dir) ?: continue
            if (note.storyNumber > maxNum) maxNum = note.storyNumber
        }
        return maxNum + 1
    }

    private fun persistNoteUnlocked(folder: File, note: StoryNote) {
        val paraArr = JSONArray()
        for (p in note.paragraphs) paraArr.put(p)
        val meta = JSONObject()
            .put("id", note.id)
            .put("story_number", note.storyNumber)
            .put("cover_color_index", note.coverColorIndex)
            .put("title", note.title)
            .put("text", note.fullText)
            .put("paragraphs", paraArr)
            .put("duration_sec", note.durationSec)
            .put("rollovers", note.rollovers)
            .put("asr_error", note.asrError)
            .put("created_at_ms", note.createdAtMs)
            .put("updated_at_ms", note.updatedAtMs)
        atomicWriteText(File(folder, "meta.json"), meta.toString(2) + "\n")
        atomicWriteText(File(folder, "note.md"), renderMarkdown(note))
    }

    private fun readNoteUnlocked(folder: File): StoryNote? {
        val metaFile = File(folder, "meta.json")
        val wavFile = File(folder, "audio.wav")
        if (!metaFile.isFile || !wavFile.isFile) return null
        return try {
            val obj = JSONObject(metaFile.readText(Charsets.UTF_8))
            val id = obj.optString("id", folder.name)
            val num = obj.optInt("story_number", 1)
            val paragraphs = mutableListOf<String>()
            val arr = obj.optJSONArray("paragraphs")
            if (arr != null && arr.length() > 0) {
                for (i in 0 until arr.length()) {
                    val p = arr.optString(i, "").trim()
                    if (p.isNotEmpty()) paragraphs.add(p)
                }
            } else {
                val legacyText = obj.optString("text", "")
                paragraphs.addAll(StoryBookFormatter.formatIntoParagraphs(legacyText))
            }
            StoryNote(
                id = id,
                storyNumber = num,
                coverColorIndex = obj.optInt("cover_color_index", (num - 1).coerceAtLeast(0) % 6),
                title = obj.optString("title", "第${num}篇故事"),
                paragraphs = paragraphs,
                durationSec = obj.optDouble("duration_sec", WavFileStreamWriter.wavDurationSec(wavFile)),
                rollovers = obj.optInt("rollovers", 0),
                asrError = obj.optString("asr_error", ""),
                createdAtMs = obj.optLong("created_at_ms", folder.lastModified()),
                updatedAtMs = obj.optLong("updated_at_ms", folder.lastModified()),
                audioFile = wavFile,
                markdownFile = File(folder, "note.md"),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun renderMarkdown(note: StoryNote): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(note.createdAtMs))
        val body = if (note.paragraphs.isNotEmpty()) {
            note.paragraphs.joinToString("\n\n")
        } else {
            "_（暂无识别文字，可在故事页点击「重新识别」生成）_"
        }
        return buildString {
            appendLine("---")
            appendLine("id: ${note.id}")
            appendLine("story_number: ${note.storyNumber}")
            appendLine("title: ${JSONObject.quote(note.title)}")
            appendLine("created_at: $iso")
            appendLine("duration_sec: ${"%.2f".format(Locale.US, note.durationSec)}")
            appendLine("audio_file: audio.wav")
            appendLine("---")
            appendLine()
            appendLine("# ${note.title}")
            appendLine()
            appendLine(body)
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
