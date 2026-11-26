package com.luna.storynote

import com.luna.storynote.agent.ToolBus
import com.luna.storynote.ai.AiGatewayClient
import com.luna.storynote.ai.VoiceRevisionAgent
import com.luna.storynote.audio.WavAudioFiles
import com.luna.storynote.audio.WavFileStreamWriter
import com.luna.storynote.model.AiGatewayConfig
import com.luna.storynote.storage.BookExporter
import com.luna.storynote.storage.StoryBookRepository
import com.luna.storynote.update.AppUpdateConfig
import com.luna.storynote.update.AppUpdateManager
import com.luna.storynote.update.UpdateCheckOutcome
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory

class V11FeaturesTest {
    @Test
    fun exporterMergesAudioAndBuildsZipWithRubyHtml() {
        val tmp = createTempDirectory("v11-export-").toFile()
        val repo = StoryBookRepository(tmp)
        val book = repo.createBlankBook()

        val wav1 = repo.newStagingWav().also { f ->
            WavFileStreamWriter(f).apply {
                val pcm = ByteArray(32000) { i -> ((i * 19) % 120).toByte() }
                appendPcm(pcm, pcm.size)
                close()
            }
        }
        val wav2 = repo.newStagingWav().also { f ->
            WavFileStreamWriter(f).apply {
                val pcm = ByteArray(16000) { i -> ((i * 29) % 120).toByte() }
                appendPcm(pcm, pcm.size)
                close()
            }
        }
        val (_, c1) = repo.insertUtteranceWithClauses(book.id, null, "小恐龙走出森林，遇到小狐狸。", "take_1", wav1, 0L, 1000L, 1000L)
        val (updated, _) = repo.insertUtteranceWithClauses(book.id, c1.last().id, "他们一起看星星。", "take_2", wav2, 0L, 500L, 500L, forceParagraphBreak = true)

        val imgFile = File(tmp, "sample.png").apply {
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        }
        val withArt = repo.attachIllustration(updated.id, 0, imgFile, "watercolor forest")!!

        val outDir = File(tmp, "exports")
        val bundle = BookExporter.exportBookBundle(withArt, outDir)
        assertTrue(bundle.storyTextFile.isFile)
        assertTrue(bundle.rubyHtmlFile.isFile)
        assertNotNull(bundle.mergedAudioFile)
        val mergedAudio = bundle.mergedAudioFile!!
        assertTrue(mergedAudio.isFile)
        assertTrue(WavAudioFiles.wavDurationMs(mergedAudio) >= 1400L)
        assertTrue(bundle.zipBundleFile.isFile)

        val html = bundle.rubyHtmlFile.readText(Charsets.UTF_8)
        assertTrue(html.contains("<ruby>"))
        assertTrue(html.contains("data:image/png;base64,"))

        ZipFile(bundle.zipBundleFile).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue(names.any { it.endsWith(".md") })
            assertTrue(names.any { it.endsWith(".html") })
            assertTrue(names.any { it.endsWith(".wav") })
        }
    }

    @Test
    fun updateManagerComparesSemverAndDownloadsValidApk() {
        assertTrue(AppUpdateManager.compareSemver("1.1.0", "1.0.0") > 0)
        assertTrue(AppUpdateManager.compareSemver("v1.2.0", "1.1.9") > 0)
        assertEquals(0, AppUpdateManager.compareSemver("v1.1.0", "1.1.0"))
        assertTrue(AppUpdateManager.compareSemver("1.0.0", "1.1.0") < 0)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/manifest.json") { ex ->
            val payload = JSONObject()
                .put("version", "1.2.0")
                .put("apk_url", "http://127.0.0.1:${server.address.port}/StoryNote-v1.2.0.apk")
                .put("release_notes", "新增整本配图规划")
                .toString()
                .toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.use { it.write(payload) }
        }
        server.createContext("/StoryNote-v1.2.0.apk") { ex ->
            val apkBytes = ByteArray(140 * 1024)
            apkBytes[0] = 0x50
            apkBytes[1] = 0x4B
            apkBytes[2] = 0x03
            apkBytes[3] = 0x04
            ex.sendResponseHeaders(200, apkBytes.size.toLong())
            ex.responseBody.use { it.write(apkBytes) }
        }
        server.start()
        try {
            val mgr = AppUpdateManager()
            val cfg = AppUpdateConfig(customManifestUrl = "http://127.0.0.1:${server.address.port}/manifest.json")
            val result = mgr.checkForUpdate(cfg, currentVersion = "1.1.0")
            assertTrue(result is UpdateCheckOutcome.UpdateFound)
            val found = (result as UpdateCheckOutcome.UpdateFound).update
            assertEquals("1.2.0", found.versionName)

            val upToDate = mgr.checkForUpdate(cfg, currentVersion = "1.2.0")
            assertTrue(upToDate is UpdateCheckOutcome.UpToDate)

            val updatesDir = createTempDirectory("v11-apk-").toFile()
            var lastPct = 0f
            val downloaded = mgr.downloadApk(found, cfg, updatesDir) { pct -> lastPct = pct }
            assertTrue(downloaded.isFile)
            assertEquals(140L * 1024L, downloaded.length())
            assertEquals(1f, lastPct, 0.01f)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun voiceRevisionAgentAppliesMultiClauseReplacementAndRecordsSummary() {
        val tmp = createTempDirectory("v11-rev-").toFile()
        val repo = StoryBookRepository(tmp)
        val book = repo.createBlankBook()
        val (_, c1) = repo.insertUtteranceWithClauses(book.id, null, "小恐龙戴着蓝色帽子。", "", null, 0L, 0L, 0L)
        val (_, c2) = repo.insertUtteranceWithClauses(book.id, c1.first().id, "小狐狸也拿着蓝色气球。", "", null, 0L, 0L, 0L)
        val id1 = c1.first().id
        val id2 = c2.first().id

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            val calls = JSONArray()
                .put(
                    JSONObject().put("id", "tc1").put("type", "function").put(
                        "function",
                        JSONObject().put("name", "replace_span").put(
                            "arguments",
                            JSONObject().put("clause_id", id1).put("start_index", 5).put("end_index", 7)
                                .put("original_span_text", "蓝色").put("replacement", "粉色").toString(),
                        ),
                    ),
                )
                .put(
                    JSONObject().put("id", "tc2").put("type", "function").put(
                        "function",
                        JSONObject().put("name", "replace_span").put(
                            "arguments",
                            JSONObject().put("clause_id", id2).put("start_index", 6).put("end_index", 8)
                                .put("original_span_text", "蓝色").put("replacement", "粉色").toString(),
                        ),
                    ),
                )
            val resp = JSONObject().put(
                "choices",
                JSONArray().put(
                    JSONObject().put("finish_reason", "tool_calls").put(
                        "message",
                        JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", calls),
                    ),
                ),
            ).toString().toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.start()
        try {
            val agent = VoiceRevisionAgent(AiGatewayClient(), ToolBus(repo))
            val res = agent.reviseClause(
                config = AiGatewayConfig(
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    apiKey = "sk-test-v11",
                    textModel = "auto/deepseek-v4.1-flash",
                ),
                bookId = book.id,
                clauseId = id1,
                instruction = "把这两句里的蓝色都改成粉色",
            )
            assertTrue(res.errorMessage ?: "revision failed", res.applied)
            val after = repo.getBook(book.id)!!
            assertEquals("小恐龙戴着粉色帽子。", after.clauses[0].text)
            assertEquals("小狐狸也拿着粉色气球。", after.clauses[1].text)

            val meaningful = repo.timeMachine.listMeaningfulSnapshots(after)
            assertTrue(meaningful.isNotEmpty())
            assertTrue(meaningful.any { it.changeSummary.contains("蓝色") && it.changeSummary.contains("粉色") })
            assertNull(meaningful.firstOrNull { it.toolName == "annotate_soft_suggestion" })
        } finally {
            server.stop(0)
        }
    }
}
