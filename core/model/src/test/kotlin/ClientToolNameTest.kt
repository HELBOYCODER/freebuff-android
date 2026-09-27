package com.freebuff.android.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClientToolNameTest {
    @Test fun `sixteen host tools from clientToolCallSchema`() {
        assertEquals(16, ClientToolName.entries.size)
        assertEquals(ClientToolName.RUN_TERMINAL_COMMAND, ClientToolName.fromWire("run_terminal_command"))
        assertNull(ClientToolName.fromWire("brand_new_tool"))
    }

    @Test fun `timeout clamp mirrors upstream`() {
        assertNull(TerminalTimeout.clamp(null))
        assertEquals(-1, TerminalTimeout.clamp(-1.0))
        assertEquals(30, TerminalTimeout.clamp(0.0))
        assertEquals(30, TerminalTimeout.clamp(-5.0))
        assertEquals(50, TerminalTimeout.clamp(50.0))
        assertEquals(600, TerminalTimeout.clamp(99999.0))
        assertEquals(600, TerminalTimeout.clamp(Double.NaN))
    }

    @Test fun `file change parse accepts patch and file rejects bad`() {
        assertEquals(FileChangeType.PATCH, FileChange.parse("patch", "a/b.ts", "x")?.type)
        assertEquals(FileChangeType.FILE, FileChange.parse("file", "a.ts", "")?.type)
        assertNull(FileChange.parse("nope", "a", ""))
        assertNull(FileChange.parse("file", "", ""))
        assertNull(FileChange.parse("file", "a", null))
    }
}
