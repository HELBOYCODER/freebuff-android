package com.freebuff.android.protocol

import com.freebuff.android.model.ClientToolName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientToolCallValidatorTest {
    private fun call(tool: String, input: Map<String, Any?>) =
        ClientToolCallValidator.validate(mapOf("toolName" to tool, "input" to input))

    @Test fun `valid write_file passes`() {
        val r = call("write_file", mapOf("type" to "file", "path" to "a.txt", "content" to "hi"))
        assertEquals(ClientToolName.WRITE_FILE, r.getOrNull()?.toolName)
    }

    @Test fun `unknown tool fails loudly`() {
        val r = call("some_new_upstream_tool", emptyMap())
        assertTrue(r.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("unsupported upstream tool"))
    }

    @Test fun `write_file with bad filechange rejected`() {
        assertTrue(call("write_file", mapOf("type" to "file", "path" to "")).isFailure)
    }

    @Test fun `run_terminal_command requires command and mode`() {
        assertTrue(call("run_terminal_command", mapOf("command" to "ls", "mode" to "assistant")).isSuccess)
        assertTrue(call("run_terminal_command", mapOf("command" to "ls")).isFailure)
        assertTrue(call("run_terminal_command", mapOf("command" to "", "mode" to "user")).isFailure)
        assertTrue(call("run_terminal_command", mapOf("command" to "x", "mode" to "user", "process_type" to "BACKGROUND")).isSuccess)
        assertTrue(call("run_terminal_command", mapOf("command" to "x", "mode" to "user", "process_type" to "WRONG")).isFailure)
    }

    @Test fun `non-filechange host tool passes with minimal input`() {
        assertTrue(call("list_directory", mapOf("path" to ".")).isSuccess)
    }
}
