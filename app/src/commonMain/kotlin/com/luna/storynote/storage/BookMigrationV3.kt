package com.luna.storynote.storage

import com.luna.storynote.audio.WavAudioFiles
import com.luna.storynote.literacy.StoryBookFormatter
import com.luna.storynote.model.AudioTake
import com.luna.storynote.model.Clause
import com.luna.storynote.model.StoryBook
import org.json.JSONObject
import java.io.File

internal object BookMigrationV3 {
    fun migrateV3ToV4(
        folder: File,
        obj: JSONObject,
        id: String,
        num: Int,
        createdAt: Long,
        updatedAt: Long,
    ): StoryBook {
        val clauses = mutableListOf<Clause>()
        val takes = mutableListOf<AudioTake>()
        val arr = obj.optJSONArray("sentences")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val so = arr.optJSONObject(i) ?: continue
                val txt = so.optString("text", "").trim()
                if (txt.isEmpty()) continue
                val sid = so.optString("id", "s_$i")
                val relAudio = so.optString("audio_file", "").trim()
                val durSec = so.optDouble("duration_sec", 0.0)
                val paraBreak = so.optBoolean("paragraph_break_after", false)

                val audioFile = if (relAudio.isNotBlank()) File(folder, relAudio) else null
                val hasWav = audioFile != null && audioFile.isFile && audioFile.length() > 44L
                val durMs = if (audioFile != null && audioFile.isFile && audioFile.length() > 44L) {
                    WavAudioFiles.wavDurationMs(audioFile).takeIf { it > 0L }
                        ?: (durSec * 1000.0).toLong().coerceAtLeast(0L)
                } else {
                    0L
                }
                val takeId = if (hasWav) "take_legacy_$sid" else ""
                if (hasWav) {
                    takes.add(
                        AudioTake(
                            id = takeId,
                            wavFileName = relAudio,
                            durationMs = durMs,
                            createdAtEpochMs = createdAt,
                        ),
                    )
                }

                val splitClauses = StoryBookFormatter.buildClausesForTake(
                    rawText = txt,
                    takeId = takeId,
                    uttStartMs = 0L,
                    uttEndMs = durMs,
                    clauseIdGenerator = { subIdx -> if (subIdx == 0) sid else "${sid}_c$subIdx" },
                    finalParagraphBreak = paraBreak,
                )
                clauses.addAll(splitClauses)
            }
        }
        return StoryBook(
            id = id,
            bookNumber = num,
            title = obj.optString("title", "未命名故事"),
            titleAudioFileName = obj.optString("title_audio_file", ""),
            coverColorIndex = obj.optInt("cover_color_index", 0),
            emblemIndex = obj.optInt("emblem_index", 0),
            clauses = clauses,
            takes = takes,
            createdAtMs = createdAt,
            updatedAtMs = updatedAt,
            bookDir = folder,
        )
    }
}
