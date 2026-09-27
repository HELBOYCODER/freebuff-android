package com.freebuff.android.protocol

import com.freebuff.android.model.ClientToolName
import com.freebuff.android.model.FileChange

data class ClientToolCall(val toolName: ClientToolName, val input: Map<String, Any?>)

// Validates the exact host contract Android must accept, mirroring the upstream
// clientToolCallSchema discriminated union (list.ts) and protocol-ref tests.
// Unknown tools fail loudly (S26); they are never silently ignored.
object ClientToolCallValidator {
    private val FILE_CHANGE_TOOLS = setOf(
        ClientToolName.CREATE_PLAN,
        ClientToolName.STR_REPLACE,
        ClientToolName.WRITE_FILE,
    )

    fun validate(raw: Map<String, Any?>): Result<ClientToolCall> {
        val name = raw["toolName"] as? String
            ?: return Result.failure(IllegalArgumentException("missing toolName"))
        val tool = ClientToolName.fromWire(name)
            ?: return Result.failure(UnsupportedOperationException("unsupported upstream tool: " + name))
        @Suppress("UNCHECKED_CAST")
        val input = raw["input"] as? Map<String, Any?>
            ?: return Result.failure(IllegalArgumentException(tool.wire + ": input must be an object"))
        val errors = when {
            FILE_CHANGE_TOOLS.contains(tool) -> validateFileChange(input)
            tool == ClientToolName.RUN_TERMINAL_COMMAND -> validateTerminal(input)
            else -> emptyList()
        }
        return if (errors.isEmpty()) Result.success(ClientToolCall(tool, input))
        else Result.failure(IllegalArgumentException(errors.joinToString("; ")))
    }

    private fun validateFileChange(input: Map<String, Any?>): List<String> {
        val type = input["type"] as? String
        val path = input["path"] as? String
        val content = input["content"] as? String
        val fc = FileChange.parse(type, path, content) ?: return listOf("invalid FileChangeSchema")
        return if (fc.path.isBlank()) listOf("path must be non-empty") else emptyList()
    }

    private fun validateTerminal(input: Map<String, Any?>): List<String> {
        val errs = mutableListOf<String>()
        val command = input["command"] as? String
        if (command.isNullOrEmpty()) errs.add("command cannot be empty")
        val mode = input["mode"] as? String
        if (mode != "assistant" && mode != "user") errs.add("client dispatch requires mode assistant|user")
        val pt = input["process_type"]
        if (pt != null && pt != "SYNC" && pt != "BACKGROUND") errs.add("process_type must be SYNC or BACKGROUND")
        return errs
    }
}
