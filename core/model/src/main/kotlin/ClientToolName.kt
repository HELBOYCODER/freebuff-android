package com.freebuff.android.model

/** The 16 tools the Freebuff host (Android) must execute — derived verbatim from
 *  upstream `clientToolCallSchema` (common/src/tools/list.ts @25f1d61). Never
 *  silently drop one; §26 requires failing loudly on an unknown tool. */
enum class ClientToolName(val wire: String) {
    APPLY_PATCH("apply_patch"),
    ASK_USER("ask_user"),
    BROWSER_LOGS("browser_logs"),
    CODE_SEARCH("code_search"),
    CREATE_PLAN("create_plan"),
    GLOB("glob"),
    LIST_DIRECTORY("list_directory"),
    RUN_FILE_CHANGE_HOOKS("run_file_change_hooks"),
    READ_URL("read_url"),
    RUN_TERMINAL_COMMAND("run_terminal_command"),
    STR_REPLACE("str_replace"),
    WRITE_FILE("write_file"),
    COMPOSIO_MANAGE_CONNECTIONS("composio_manage_connections"),
    COMPOSIO_MULTI_EXECUTE_TOOL("composio_multi_execute_tool"),
    COMPOSIO_SEARCH_TOOLS("composio_search_tools"),
    COMPOSIO_GET_TOOL_SCHEMAS("composio_get_tool_schemas");

    companion object {
        val byWire: Map<String, ClientToolName> = entries.associateBy { it.wire }
        fun fromWire(name: String): ClientToolName? = byWire[name]
    }
}

/** Upstream `run_terminal_command` timeout clamp (params/tool/run-terminal-command.ts):
 *  undefined -> schema default (represented here as null), -1 -> indefinite,
 *  !finite -> MAX, <=0 -> 30, else min(value, MAX=600). */
object TerminalTimeout {
    const val MAX_SECONDS = 600
    const val DEFAULT_SECONDS = 30
    fun clamp(seconds: Double?): Int? {
        if (seconds == null) return null
        if (seconds == -1.0) return -1
        if (!seconds.isFinite()) return MAX_SECONDS
        if (seconds <= 0.0) return DEFAULT_SECONDS
        return minOf(seconds.toInt(), MAX_SECONDS)
    }
}
