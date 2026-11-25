package com.luna.storynote.ai

import com.luna.storynote.agent.AgentTaskRole
import com.luna.storynote.agent.ToolBus
import com.luna.storynote.agent.ToolId
import com.luna.storynote.agent.ToolRequest
import com.luna.storynote.agent.ToolResult
import com.luna.storynote.model.AiGatewayConfig
import com.luna.storynote.model.BookBible
import com.luna.storynote.model.Clause
import java.io.File
import java.util.UUID

data class IllustrationResult(
    val success: Boolean,
    val pageIndex: Int,
    val illustrationId: String?,
    val promptUsed: String,
    val errorMessage: String? = null,
)

class IllustrationAgent(
    private val aiClient: AiGatewayClient,
    private val toolBus: ToolBus,
) {
    fun illustratePage(
        config: AiGatewayConfig,
        bookId: String,
        pageIndex: Int,
    ): IllustrationResult {
        if (!config.isConfigured) {
            return IllustrationResult(false, pageIndex, null, "", "AI未配置")
        }

        val pageRes = toolBus.dispatch(
            AgentTaskRole.ILLUSTRATION_COMPILER,
            ToolRequest(ToolId.READ_PAGE, mapOf("bookId" to bookId, "pageIndex" to pageIndex)),
        ) as? ToolResult.Success ?: return IllustrationResult(false, pageIndex, null, "", "读取页面失败")
        val book = pageRes.book ?: return IllustrationResult(false, pageIndex, null, "", "故事不存在")

        @Suppress("UNCHECKED_CAST")
        val pageClauses = (pageRes.data["clauses"] as? List<Clause>).orEmpty()
        val pageText = pageClauses.joinToString("") { it.text }.trim()

        val bibleRes = toolBus.dispatch(
            AgentTaskRole.ILLUSTRATION_COMPILER,
            ToolRequest(ToolId.READ_BOOK_BIBLE, mapOf("bookId" to bookId)),
        ) as? ToolResult.Success
        val bible = bibleRes?.bible ?: BookBible(bookId = bookId)

        if (!book.bookDir.isDirectory || !File(book.bookDir, "book.json").isFile) {
            return IllustrationResult(false, pageIndex, null, "", "故事不存在")
        }

        val prompt = compilePrompt(book.title, pageText, bible)
        val imageBytes = aiClient.generateImage(config, prompt, size = "1024x1024")
        if (imageBytes.size <= 64) {
            return IllustrationResult(false, pageIndex, null, prompt, "生成图片数据无效")
        }

        val recheckRes = toolBus.dispatch(
            AgentTaskRole.ILLUSTRATION_COMPILER,
            ToolRequest(ToolId.READ_PAGE, mapOf("bookId" to bookId, "pageIndex" to pageIndex)),
        ) as? ToolResult.Success
        if (recheckRes == null || !book.bookDir.isDirectory || !File(book.bookDir, "book.json").isFile) {
            return IllustrationResult(false, pageIndex, null, prompt, "故事不存在")
        }

        val illusDir = File(book.bookDir, "illustrations").apply { mkdirs() }
        val tempFile = File(illusDir, "tmp_ill_${UUID.randomUUID().toString().replace("-", "").take(10)}.png")
        try {
            tempFile.writeBytes(imageBytes)
            val attachRes = toolBus.dispatch(
                AgentTaskRole.ILLUSTRATION_COMPILER,
                ToolRequest(
                    ToolId.ATTACH_PAGE_ILLUSTRATION,
                    mapOf(
                        "bookId" to bookId,
                        "pageIndex" to pageIndex,
                        "imageFile" to tempFile,
                        "promptUsed" to prompt,
                    ),
                ),
            )
            return when (attachRes) {
                is ToolResult.Success -> {
                    val illId = attachRes.book?.activeIllustrationFor(pageIndex)?.id
                    IllustrationResult(true, pageIndex, illId, prompt)
                }
                is ToolResult.Denied -> IllustrationResult(false, pageIndex, null, prompt, attachRes.reason)
                is ToolResult.Error -> IllustrationResult(false, pageIndex, null, prompt, attachRes.message)
            }
        } finally {
            runCatching { tempFile.delete() }
            if (!File(book.bookDir, "book.json").isFile) {
                runCatching {
                    if (illusDir.listFiles()?.isEmpty() == true) illusDir.delete()
                    if (book.bookDir.listFiles()?.isEmpty() == true) book.bookDir.delete()
                }
            }
        }
    }

    fun compilePrompt(
        bookTitle: String,
        pageText: String,
        bible: BookBible,
    ): String {
        val parts = mutableListOf<String>()
        parts.add(STYLE_LOCK)
        if (bible.protagonistTraits.isNotEmpty()) {
            parts.add("Consistent main character traits (locked): ${bible.protagonistTraits.joinToString("，")}.")
        }
        if (bible.keyScenesAndProps.isNotEmpty()) {
            parts.add("Key story setting and props: ${bible.keyScenesAndProps.joinToString("，")}.")
        }
        val dynamicScene = pageText.ifBlank { bible.plotSummary.ifBlank { bookTitle } }
        parts.add("Current page scene and emotion to depict faithfully: $dynamicScene.")
        parts.add(STRICT_GUARDRAIL)
        return parts.joinToString(" ")
    }

    companion object {
        private const val STYLE_LOCK =
            "Warm hand-painted watercolor children's picture book illustration (温暖水彩绘本风格), " +
                "soft translucent gouache washes, delicate ink-brush contours, textured cream cotton paper grain, " +
                "and soft organic watercolor edges fading into warm cream paper."

        private const val STRICT_GUARDRAIL =
            "Strict guardrail: pure visual artwork with no text, no words, no letters, no Chinese characters, " +
                "no Hanzi, no Pinyin, no numbers, no speech bubbles, no watermark, no signature, no border frame (严禁任何文字、汉字、拼音、数字或水印)."
    }
}
