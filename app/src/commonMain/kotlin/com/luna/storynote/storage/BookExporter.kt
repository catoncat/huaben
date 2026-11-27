package com.luna.storynote.storage

import com.luna.storynote.audio.FrontierProtocol
import com.luna.storynote.audio.WavAudioFiles
import com.luna.storynote.audio.WavFileStreamWriter
import com.luna.storynote.literacy.PinyinAnnotator
import com.luna.storynote.model.StoryBook
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

enum class BookExportMode {
    FULL_BUNDLE,
    AUDIO_ONLY,
    TEXT_ONLY,
}

data class BookExportResult(
    val outputDir: File,
    val storyTextFile: File,
    val rubyHtmlFile: File,
    val mergedAudioFile: File?,
    val zipBundleFile: File,
    val audioDurationSec: Double,
)

object BookExporter {
    fun sanitizeFileName(raw: String, fallback: String = "未命名故事"): String {
        val cleaned = raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_', '.', ' ')
            .take(36)
        return cleaned.ifEmpty { fallback }
    }

    /**
     * Merges the active book's title recording (if any) and all currently kept clauses in reading order
     * into a single clean 16kHz 16-bit mono WAV audiobook, excluding deleted/overwritten takes.
     * Adjacent clauses from the same `AudioTake` with contiguous timestamps splice seamlessly with 0 gap.
     */
    fun exportMergedAudio(book: StoryBook, destWav: File): Pair<File, Double>? {
        destWav.parentFile?.mkdirs()
        val tmpWav = File(destWav.parentFile, ".${destWav.name}.tmp")
        if (tmpWav.exists()) tmpWav.delete()

        val writer = WavFileStreamWriter(tmpWav, FrontierProtocol.SAMPLE_RATE)
        var hasAnyAudio = false
        var prevTakeId: String? = null
        var prevEndMs = -1L

        try {
            val titleFile = book.titleAudioFile()
            if (titleFile != null && !WavAudioFiles.isRangeNearSilent(titleFile, 0L, -1L)) {
                val slice = WavAudioFiles.readPcmRange(titleFile, 0L, -1L)
                if (slice != null && slice.pcmBytes.isNotEmpty()) {
                    writer.appendPcm(slice.pcmBytes, slice.pcmBytes.size)
                    hasAnyAudio = true
                }
            }

            val pages = book.paginate()
            for (page in pages) {
                for ((paraIdx, para) in page.paragraphs.withIndex()) {
                    var isFirstInPara = true
                    for (clause in para.clauses) {
                        val resolved = book.resolveAudio(clause) ?: continue
                        val (wav, span) = resolved
                        if (WavAudioFiles.isRangeNearSilent(wav, span.startMs, span.endMs)) continue
                        val slice = WavAudioFiles.readPcmRange(wav, span.startMs, span.endMs) ?: continue
                        if (slice.pcmBytes.isEmpty()) continue

                        if (hasAnyAudio) {
                            val contiguousSameTake = prevTakeId != null &&
                                prevTakeId == span.takeId &&
                                abs(span.startMs - prevEndMs) <= 100L
                            if (!contiguousSameTake) {
                                val pauseMs = when {
                                    prevTakeId == null -> 440 // Pause after spoken book title
                                    isFirstInPara && paraIdx > 0 -> 500
                                    isFirstInPara -> 420
                                    else -> 280
                                }
                                writer.appendSilenceMs(pauseMs)
                            }
                        }

                        writer.appendPcm(slice.pcmBytes, slice.pcmBytes.size)
                        hasAnyAudio = true
                        prevTakeId = span.takeId
                        prevEndMs = span.endMs
                        isFirstInPara = false
                    }
                }
            }
        } finally {
            writer.close()
        }

        if (!hasAnyAudio || tmpWav.length() <= 44L) {
            tmpWav.delete()
            return null
        }
        if (!tmpWav.renameTo(destWav)) {
            tmpWav.copyTo(destWav, overwrite = true)
            tmpWav.delete()
        }
        val dur = WavFileStreamWriter.wavDurationSec(destWav)
        return destWav to dur
    }

    /**
     * Renders a self-contained print-ready HTML storybook with standard `<ruby>` Pinyin annotation
     * and embedded Base64 watercolor illustrations so parents can read or print straight from any browser.
     */
    fun renderBookRubyHtml(book: StoryBook): String {
        val pages = book.paginate()
        val escapedTitle = escapeHtml(book.title)
        val titleRuby = renderRubySpans(book.title)
        return buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">")
            appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
            appendLine("<title>$escapedTitle - 话本本</title>")
            appendLine("<style>")
            appendLine("body{margin:0;padding:28px 16px;background:#EFE9DC;color:#1F1A14;font-family:'Songti SC','Noto Serif CJK SC','STSong',serif;}")
            appendLine(".page{max-width:760px;margin:0 auto 28px;padding:42px 44px 32px;background:#FAF7F0;border:1px solid #DDD4C3;border-radius:16px;box-shadow:0 8px 24px rgba(31,26,20,.06);page-break-after:always;}")
            appendLine("h1{text-align:center;font-size:30px;margin:0 0 24px;padding-bottom:16px;border-bottom:1px solid #E5DCCB;line-height:2.1;}")
            appendLine(".illus{text-align:center;margin:0 0 24px;}.illus img{max-width:100%;max-height:360px;border-radius:12px;}")
            appendLine("p{font-size:24px;line-height:2.55;text-indent:2em;margin:0 0 18px;letter-spacing:1.5px;}")
            appendLine("ruby{ruby-position:over;ruby-align:center;}rt{font-family:-apple-system,BlinkMacSystemFont,'Helvetica Neue',sans-serif;font-size:12px;color:#7B6E5D;letter-spacing:0;}")
            appendLine(".folio{text-align:center;color:#9C8F7C;font-size:13px;margin-top:24px;}")
            appendLine("@media print{body{background:#fff;padding:0;}.page{box-shadow:none;border:none;margin:0;border-radius:0;}}")
            appendLine("</style></head><body>")
            for (page in pages) {
                appendLine("<section class=\"page\">")
                val illu = book.activeIllustrationFor(page.pageIndex)
                val illuFile = illu?.let { book.illustrationFile(it) }
                if (illuFile != null && illuFile.isFile) {
                    val mime = if (illuFile.extension.equals("jpg", true) || illuFile.extension.equals("jpeg", true)) "image/jpeg" else "image/png"
                    val b64 = Base64.getEncoder().encodeToString(illuFile.readBytes())
                    appendLine("<div class=\"illus\"><img src=\"data:$mime;base64,$b64\" alt=\"第${page.pageIndex + 1}页插图\"/></div>")
                }
                if (page.pageIndex == 0) {
                    appendLine("<h1>$titleRuby</h1>")
                }
                for (para in page.paragraphs) {
                    val paraText = para.clauses.joinToString("") { it.text }
                    appendLine("<p>${renderRubySpans(paraText)}</p>")
                }
                appendLine("<div class=\"folio\">— ${page.pageIndex + 1} —</div>")
                appendLine("</section>")
            }
            appendLine("</body></html>")
        }
    }

    fun exportBookBundle(book: StoryBook, outputDir: File): BookExportResult {
        outputDir.mkdirs()
        val safeTitle = sanitizeFileName(book.title)

        val mdFile = File(outputDir, "$safeTitle.md")
        BookJsonCodec.atomicWriteText(mdFile, BookJsonCodec.renderBookMarkdown(book))

        val htmlFile = File(outputDir, "${safeTitle}_带拼音绘本.html")
        BookJsonCodec.atomicWriteText(htmlFile, renderBookRubyHtml(book))

        val audioTarget = File(outputDir, "${safeTitle}_完整录音.wav")
        val merged = exportMergedAudio(book, audioTarget)
        val mergedAudioFile = merged?.first
        val audioDurationSec = merged?.second ?: 0.0

        val zipFile = File(outputDir, "${safeTitle}_故事包.zip")
        val tmpZip = File(outputDir, ".${zipFile.name}.tmp")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(tmpZip))).use { zos ->
            addFileToZip(zos, mdFile, "$safeTitle/${mdFile.name}")
            addFileToZip(zos, htmlFile, "$safeTitle/${htmlFile.name}")
            if (mergedAudioFile != null && mergedAudioFile.isFile) {
                addFileToZip(zos, mergedAudioFile, "$safeTitle/${mergedAudioFile.name}")
            }
            for (page in book.paginate()) {
                val ill = book.activeIllustrationFor(page.pageIndex) ?: continue
                val f = book.illustrationFile(ill) ?: continue
                if (f.isFile) {
                    val ext = f.extension.ifBlank { "png" }
                    addFileToZip(zos, f, "$safeTitle/插画/第${page.pageIndex + 1}页.$ext")
                }
            }
        }
        if (!tmpZip.renameTo(zipFile)) {
            tmpZip.copyTo(zipFile, overwrite = true)
            tmpZip.delete()
        }

        return BookExportResult(
            outputDir = outputDir,
            storyTextFile = mdFile,
            rubyHtmlFile = htmlFile,
            mergedAudioFile = mergedAudioFile,
            zipBundleFile = zipFile,
            audioDurationSec = audioDurationSec,
        )
    }

    private fun addFileToZip(zos: ZipOutputStream, file: File, entryName: String) {
        if (!file.isFile) return
        zos.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { input -> input.copyTo(zos, 8192) }
        zos.closeEntry()
    }

    private fun renderRubySpans(text: String): String {
        val cells = PinyinAnnotator.annotateToCells(text)
        return buildString(text.length * 16) {
            for (cell in cells) {
                val escapedChar = escapeHtml(cell.charText)
                if (cell.isHanzi && cell.pinyin.isNotBlank()) {
                    append("<ruby>").append(escapedChar).append("<rt>").append(escapeHtml(cell.pinyin)).append("</rt></ruby>")
                } else {
                    append(escapedChar)
                }
            }
        }
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
