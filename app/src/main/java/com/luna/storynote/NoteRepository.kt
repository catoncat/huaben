package com.luna.storynote

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

val STORY_STICKERS = listOf("🦖", "🚀", "🏰", "🐳", "🦊", "🌈", "🦄", "🐙", "🐉", "🎈", "🦁", "🍄")

data class StoryNote(
    val id: String,
    val storyNumber: Int,
    val sticker: String,
    val title: String,
    val text: String,
    val durationSec: Double,
    val rollovers: Int,
    val asrError: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val audioFile: File,
    val markdownFile: File,
)

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

    fun finalizeNote(
        recId: String,
        durationSec: Double,
        text: String,
        rollovers: Int,
        asrError: String,
    ): StoryNote = synchronized(lock) {
        val srcFolder = File(stagingDir, recId)
        val srcWav = File(srcFolder, "audio.wav")
        if (!srcWav.isFile) {
            throw IllegalStateException("未找到录音音频文件")
        }

        val nextNum = nextStoryNumberUnlocked()
        val sticker = STORY_STICKERS[(nextNum - 1) % STORY_STICKERS.size]
        val cleanText = text.trim()
        val title = deriveTitle(cleanText, nextNum)
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
            sticker = sticker,
            title = title,
            text = cleanText,
            durationSec = durationSec,
            rollovers = rollovers,
            asrError = if (cleanText.isEmpty()) asrError else "",
            createdAtMs = now,
            updatedAtMs = now,
            audioFile = targetWav,
            markdownFile = File(targetFolder, "note.md"),
        )
        persistNoteUnlocked(targetFolder, note)
        note
    }

    fun listNotes(): List<StoryNote> = synchronized(lock) {
        val children = notesDir.listFiles() ?: return emptyList()
        children
            .filter { it.isDirectory }
            .mapNotNull { readNoteUnlocked(it) }
            .sortedWith(compareByDescending<StoryNote> { it.createdAtMs }.thenByDescending { it.storyNumber })
    }

    fun updateNote(
        noteId: String,
        newTitle: String? = null,
        newText: String? = null,
        newSticker: String? = null,
        newRollovers: Int? = null,
        newAsrError: String? = null,
    ): StoryNote? = synchronized(lock) {
        val folder = File(notesDir, noteId)
        val existing = readNoteUnlocked(folder) ?: return null
        val updatedText = newText?.trim() ?: existing.text
        val autoUpdatedTitle = when {
            !newTitle.isNullOrBlank() -> newTitle.trim().take(40)
            newText != null && existing.title.startsWith("第 ") -> deriveTitle(updatedText, existing.storyNumber)
            else -> existing.title
        }
        val updated = existing.copy(
            title = autoUpdatedTitle,
            text = updatedText,
            sticker = newSticker?.trim()?.takeIf { it.isNotEmpty() } ?: existing.sticker,
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

    private fun deriveTitle(text: String, storyNum: Int): String {
        if (text.isBlank()) return "第 $storyNum 篇故事"
        val firstClause = text
            .split(Regex("[。！？!?\\n]"), limit = 2)
            .firstOrNull()
            .orEmpty()
            .trim(' ', '，', ',', '、', '：', ':', '；', ';')
        if (firstClause.isEmpty()) return "第 $storyNum 篇故事"
        return if (firstClause.length > 16) "${firstClause.take(16)}…" else firstClause
    }

    private fun persistNoteUnlocked(folder: File, note: StoryNote) {
        val meta = JSONObject()
            .put("id", note.id)
            .put("story_number", note.storyNumber)
            .put("sticker", note.sticker)
            .put("title", note.title)
            .put("text", note.text)
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
            StoryNote(
                id = id,
                storyNumber = num,
                sticker = obj.optString("sticker", "🦖"),
                title = obj.optString("title", "第 $num 篇故事"),
                text = obj.optString("text", ""),
                durationSec = obj.optDouble("duration_sec", 0.0),
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
        val body = note.text.ifBlank { "_（暂无识别文字，可在 App 内点击「重新识别」生成）_" }
        return buildString {
            appendLine("---")
            appendLine("id: ${note.id}")
            appendLine("story_number: ${note.storyNumber}")
            appendLine("sticker: \"${note.sticker}\"")
            appendLine("title: ${JSONObject.quote(note.title)}")
            appendLine("created_at: $iso")
            appendLine("duration_sec: ${"%.2f".format(Locale.US, note.durationSec)}")
            appendLine("audio_file: audio.wav")
            appendLine("---")
            appendLine()
            appendLine("# ${note.sticker} ${note.title}")
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
