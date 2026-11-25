package com.luna.storynote.agent

enum class AgentTaskRole {
    USER_DIRECT,
    SILENT_COPY_EDITOR,
    VOICE_REVISION,
    ILLUSTRATION_COMPILER,
    CORNER_VOICE_SPARK,
}

enum class ToolId(val toolName: String) {
    READ_BOOK_STATE("read_book_state"),
    READ_PAGE("read_page"),
    READ_BOOK_BIBLE("read_book_bible"),
    APPEND_CLAUSE("append_clause"),
    INSERT_CLAUSE_AFTER("insert_clause_after"),
    REPLACE_SPAN("replace_span"),
    RERECORD_CLAUSE_AUDIO("rerecord_clause_audio"),
    ERASE_CLAUSE("erase_clause"),
    TOGGLE_PARAGRAPH_BREAK("toggle_paragraph_break"),
    RESTORE_HISTORY_SNAPSHOT("restore_history_snapshot"),
    ANNOTATE_SOFT_SUGGESTION("annotate_soft_suggestion"),
    UPDATE_BOOK_BIBLE("update_book_bible"),
    ATTACH_PAGE_ILLUSTRATION("attach_page_illustration"),
    SELECT_PAGE_ILLUSTRATION("select_page_illustration"),
    ADD_WORD_BOX_CARD("add_word_box_card"),
}

object ToolWhitelists {
    private val roleAllowed: Map<AgentTaskRole, Set<ToolId>> = mapOf(
        AgentTaskRole.USER_DIRECT to ToolId.entries.toSet(),
        AgentTaskRole.SILENT_COPY_EDITOR to setOf(
            ToolId.READ_BOOK_STATE,
            ToolId.READ_PAGE,
            ToolId.READ_BOOK_BIBLE,
            ToolId.ANNOTATE_SOFT_SUGGESTION,
            ToolId.UPDATE_BOOK_BIBLE,
        ),
        AgentTaskRole.VOICE_REVISION to setOf(
            ToolId.READ_BOOK_STATE,
            ToolId.READ_PAGE,
            ToolId.READ_BOOK_BIBLE,
            ToolId.REPLACE_SPAN,
        ),
        AgentTaskRole.ILLUSTRATION_COMPILER to setOf(
            ToolId.READ_PAGE,
            ToolId.READ_BOOK_BIBLE,
            ToolId.ATTACH_PAGE_ILLUSTRATION,
        ),
        AgentTaskRole.CORNER_VOICE_SPARK to setOf(
            ToolId.READ_BOOK_STATE,
            ToolId.READ_BOOK_BIBLE,
        ),
    )

    fun allowedTools(role: AgentTaskRole): Set<ToolId> = roleAllowed[role].orEmpty()

    fun isAllowed(role: AgentTaskRole, toolId: ToolId): Boolean = toolId in allowedTools(role)
}
