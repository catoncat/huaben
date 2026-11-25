package com.luna.storynote.ai

import com.luna.storynote.PlatformEnvironment
import com.luna.storynote.agent.AgentTaskRole
import com.luna.storynote.agent.ToolBus
import com.luna.storynote.agent.ToolId
import com.luna.storynote.agent.ToolRequest
import com.luna.storynote.agent.ToolResult
import com.luna.storynote.model.AiGatewayConfig
import com.luna.storynote.model.BookBible
import com.luna.storynote.model.StoryBook

data class VoiceSparkResult(
    val spokenQuestion: String?,
    val errorMessage: String? = null,
)

class VoiceSparkAgent(
    private val aiClient: AiGatewayClient,
    private val toolBus: ToolBus,
    private val env: PlatformEnvironment,
) {
    fun spark(
        config: AiGatewayConfig,
        bookId: String,
    ): VoiceSparkResult {
        if (!config.isConfigured) {
            return VoiceSparkResult(null, "AI未配置")
        }

        val bookRes = toolBus.dispatch(
            AgentTaskRole.CORNER_VOICE_SPARK,
            ToolRequest(ToolId.READ_BOOK_STATE, mapOf("bookId" to bookId)),
        ) as? ToolResult.Success ?: return VoiceSparkResult(null, "读取故事失败")
        val book = bookRes.book ?: return VoiceSparkResult(null, "故事不存在")

        val bibleRes = toolBus.dispatch(
            AgentTaskRole.CORNER_VOICE_SPARK,
            ToolRequest(ToolId.READ_BOOK_BIBLE, mapOf("bookId" to bookId)),
        ) as? ToolResult.Success
        val bible = bibleRes?.bible ?: BookBible(bookId = bookId)

        val messages = buildMessages(book, bible)
        val resp = aiClient.chatWithTools(
            config = config,
            messages = messages,
            tools = emptyList(),
            temperature = 0.7,
            maxTokens = 96,
        )

        val question = sanitizeQuestion(resp.content.orEmpty())
            ?: return VoiceSparkResult(null, "未生成提问")

        env.stopSpeaking()
        env.speakStandardWord(question)
        return VoiceSparkResult(spokenQuestion = question)
    }

    private fun buildMessages(book: StoryBook, bible: BookBible): List<ChatMessage> {
        val storySoFar = book.clauses.joinToString("") { it.text }.trim()
        val sys = """
            你是儿童写故事时的轻声启发小伙伴。
            请结合已经写好的故事内容与设定集，只输出 1 句简短、温暖、开放式的中文小问题（以“？”结尾，15到28个字），
            引导孩子接着往下想象；绝不要替孩子续写故事正文，也不要输出任何解释、引号、编号或多余句子。
        """.trimIndent()

        val user = buildString {
            appendLine("绘本标题: ${book.title}")
            if (bible.protagonistTraits.isNotEmpty()) {
                appendLine("主角设定: ${bible.protagonistTraits.joinToString("、")}")
            }
            if (bible.keyScenesAndProps.isNotEmpty()) {
                appendLine("关键场景道具: ${bible.keyScenesAndProps.joinToString("、")}")
            }
            if (bible.plotSummary.isNotBlank()) {
                appendLine("已有摘要: ${bible.plotSummary}")
            }
            appendLine("已写出的故事: ${storySoFar.ifEmpty { "（还没开始写正文）" }}")
        }

        return listOf(
            ChatMessage(role = "system", content = sys),
            ChatMessage(role = "user", content = user),
        )
    }

    internal fun sanitizeQuestion(raw: String): String? {
        val withoutFences = raw
            .replace("\r\n", "\n")
            .replace(Regex("```[a-zA-Z0-9_-]*"), "")
        val firstLine = withoutFences
            .lineSequence()
            .map { line ->
                line
                    .replace(Regex("""^\s*(?:\d+[.、)）]|[-*•])\s*"""), "")
                    .replace(Regex("[`*_#>\"]"), "")
                    .replace("“", "")
                    .replace("”", "")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }
            .firstOrNull { it.isNotEmpty() }
            ?: return null
        val qIdx = firstLine.indexOfFirst { it == '？' || it == '?' }
        val single = if (qIdx >= 0) {
            firstLine.substring(0, qIdx + 1).trim()
        } else {
            val trimmed = firstLine.trimEnd('。', '！', '.', '!')
            if (trimmed.isEmpty()) "" else "$trimmed？"
        }
        return single.takeIf { it.isNotBlank() && it != "？" && it != "?" }
    }
}
