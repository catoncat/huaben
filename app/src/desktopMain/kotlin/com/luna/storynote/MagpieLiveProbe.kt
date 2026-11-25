package com.luna.storynote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.luna.storynote.agent.AgentTaskRole
import com.luna.storynote.agent.ToolBus
import com.luna.storynote.agent.ToolId
import com.luna.storynote.agent.ToolRequest
import com.luna.storynote.ai.AiGatewayClient
import com.luna.storynote.ai.IllustrationAgent
import com.luna.storynote.ai.SilentCopyEditorAgent
import com.luna.storynote.ai.VoiceRevisionAgent
import com.luna.storynote.ai.VoiceSparkAgent
import com.luna.storynote.literacy.HanziStrokeRepository
import com.luna.storynote.model.StudioDestination
import com.luna.storynote.model.StudioUiState
import com.luna.storynote.storage.StoryBookRepository
import com.luna.storynote.ui.StoryStudioRoot
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.io.path.createTempDirectory

internal fun runMagpieLiveVerification(outDir: File) {
    outDir.mkdirs()
    val tmp = createTempDirectory("storynote-magpie-probe-").toFile()
    try {
        val env = DesktopPlatformEnvironment(storageRoot = tmp)
        HanziStrokeRepository.ensureLoaded { env.openStrokeAssetStream() }
        val cfg = env.loadDefaultAiConfig()
        println("[MAGPIE_PROBE] Config summary: ${cfg.redactedSummary()}")
        require(cfg.isConfigured) { "AI gateway is not configured in local environment" }

        val aiClient = AiGatewayClient()
        val conn = aiClient.testConnection(cfg)
        println("[MAGPIE_PROBE] /v1/models ok=${conn.ok} count=${conn.models.size} latencyMs=${conn.latencyMs} msg=${AiGatewayClient.redactSecrets(conn.message, cfg.apiKey, cfg.imageApiKey)}")
        require(conn.ok) { "Gateway /v1/models failed: ${conn.message}" }

        val repo = StoryBookRepository(env.storageRoot)
        val toolBus = ToolBus(repo)
        val book = repo.createBlankBook()
        repo.updateBookTitleAndCover(book.id, newTitle = "小恐龙的大森林")
        val appRes = toolBus.dispatch(
            AgentTaskRole.USER_DIRECT,
            ToolRequest(
                ToolId.APPEND_CLAUSE,
                mapOf("bookId" to book.id, "text" to "小恐龙慢慢地走出了房间，他往前走啊走，走啊走，终于来到了大森林里"),
            ),
        )
        require(appRes.isSuccess) { "Failed seeding clause" }
        val clauses = repo.getBook(book.id)!!.clauses

        // 1. Live SilentCopyEditorAgent on forest story
        val copyEditor = SilentCopyEditorAgent(aiClient, toolBus)
        val copyRes = copyEditor.reviewBook(cfg, book.id, clauses.map { it.id })
        val afterCopyBook = repo.getBook(book.id)!!
        val afterBible = repo.bookBibles.read(book.id)
        require(afterCopyBook.clauses.map { it.text } == clauses.map { it.text }) { "SilentCopyEditor mutated Clause.text!" }
        println("[MAGPIE_PROBE] SilentCopyEditor annotatedClauses=${copyRes.annotatedClauses} bibleUpdated=${copyRes.bibleUpdated} traits=${afterBible.protagonistTraits}")

        if (afterBible.protagonistTraits.isEmpty()) {
            toolBus.dispatch(
                AgentTaskRole.SILENT_COPY_EDITOR,
                ToolRequest(
                    ToolId.UPDATE_BOOK_BIBLE,
                    mapOf(
                        "bookId" to book.id,
                        "protagonistTraits" to listOf("圆滚滚好奇的小恐龙", "亮晶晶的大眼睛和开心的微笑"),
                        "keyScenesAndProps" to listOf("郁郁葱葱的绿色大森林", "金色斑驳的阳光"),
                        "plotSummary" to "小恐龙走出房间，开心地走啊走，终于来到了生机勃勃的大森林里",
                    ),
                ),
            )
        }

        // 2. Live VoiceRevisionAgent on a dedicated scratch book so forest story remains pure
        val revBook = repo.createBlankBook()
        toolBus.dispatch(
            AgentTaskRole.USER_DIRECT,
            ToolRequest(ToolId.APPEND_CLAUSE, mapOf("bookId" to revBook.id, "text" to "我座在棉花糖火山旁看红色星星。")),
        )
        val revClause = repo.getBook(revBook.id)!!.clauses.first()
        val voiceReviser = VoiceRevisionAgent(aiClient, toolBus)
        val revRes = voiceReviser.reviseClause(cfg, revBook.id, revClause.id, "把红色星星改成金色星星")
        println("[MAGPIE_PROBE] VoiceRevision applied=${revRes.applied} updatedText=${revRes.updatedText}")
        require(revRes.applied && revRes.updatedText?.contains("金色") == true) {
            "VoiceRevision failed: ${revRes.errorMessage}"
        }

        // 3. Live VoiceSparkAgent (must NOT mutate book.json)
        val bookJsonBeforeSpark = File(repo.getBook(book.id)!!.bookDir, "book.json").readText()
        val silentEnv = object : PlatformEnvironment by env {
            override fun speakStandardWord(word: String) {
                println("[MAGPIE_PROBE] VoiceSpark TTS: $word")
            }
            override fun stopSpeaking() = Unit
        }
        val sparkAgent = VoiceSparkAgent(aiClient, toolBus, silentEnv)
        val sparkRes = sparkAgent.spark(cfg, book.id)
        val bookJsonAfterSpark = File(repo.getBook(book.id)!!.bookDir, "book.json").readText()
        require(bookJsonBeforeSpark == bookJsonAfterSpark) { "VoiceSpark mutated book.json!" }
        require(!sparkRes.spokenQuestion.isNullOrBlank()) { "VoiceSpark produced empty question" }
        println("[MAGPIE_PROBE] VoiceSpark question=${sparkRes.spokenQuestion}")

        // 4. Live IllustrationAgent (codex/gpt-image-2)
        val illAgent = IllustrationAgent(aiClient, toolBus)
        val illRes = illAgent.illustratePage(cfg, book.id, 0)
        println("[MAGPIE_PROBE] Illustration success=${illRes.success} id=${illRes.illustrationId} prompt=${illRes.promptUsed}")
        require(illRes.success) { "IllustrationAgent failed: ${illRes.errorMessage}" }

        val illustratedBook = repo.getBook(book.id)!!
        val activeIll = illustratedBook.activeIllustrationFor(0)!!
        val illFile = illustratedBook.illustrationFile(activeIll)!!
        val decoded = decodeFileToImageBitmap(illFile)
        require(decoded != null && decoded.width > 0 && decoded.height > 0) {
            "decodeFileToImageBitmap failed for ${illFile.absolutePath}"
        }
        val page0Out = File(outDir, "magpie_live_page0.png")
        illFile.copyTo(page0Out, overwrite = true)

        // 5. Render composed spread through StoryStudioRoot
        val controller = BookStudioController(env)
        try {
            val spreadState = StudioUiState(
                destination = StudioDestination.BOOK,
                books = repo.listBooks(),
                activeBookId = illustratedBook.id,
                currentPageIndex = 0,
                aiConfig = cfg,
                bookBible = repo.bookBibles.read(illustratedBook.id),
            )
            val spreadOut = File(outDir, "magpie_live_spread.png")
            val scene = ImageComposeScene(1800, 1120, Density(2f))
            try {
                scene.setContent { MaterialTheme { StoryStudioRoot(spreadState, controller) } }
                var img = scene.render(0L)
                for (step in 1..12) {
                    img = scene.render(step * 160_000_000L)
                }
                val bytes = img.encodeToData(EncodedImageFormat.PNG)?.bytes
                    ?: throw IllegalStateException("Spread PNG encode failed")
                spreadOut.writeBytes(bytes)
            } finally {
                scene.close()
            }
            println("[MAGPIE_PROBE] Saved ${page0Out.absolutePath} (${decoded.width}x${decoded.height}) and ${spreadOut.absolutePath}")
        } finally {
            controller.dispose()
        }
    } finally {
        tmp.deleteRecursively()
    }
}
