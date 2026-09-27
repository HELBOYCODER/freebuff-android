package com.freebuff.android.workspace

import com.freebuff.android.model.ClientToolName
import com.freebuff.android.protocol.ClientToolCall
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceFileSystemTest {
    private lateinit var dir: java.nio.file.Path
    private lateinit var fs: LocalWorkspaceFileSystem

    @BeforeTest fun setUp() {
        dir = Files.createTempDirectory("fb-ws")
        fs = LocalWorkspaceFileSystem(dir)
    }

    @AfterTest fun tearDown() { dir.toFile().deleteRecursively() }

    @Test fun `write then read round-trips and creates parent dirs`() {
        assertTrue(fs.writeText("src/app/Main.kt", "fun main() {}").isSuccess)
        assertEquals("fun main() {}", fs.readText("src/app/Main.kt").getOrNull())
        assertTrue(fs.exists("src/app/Main.kt"))
    }

    @Test fun `list returns entries with type and size`() {
        fs.writeText("a.txt", "12345")
        fs.writeText("sub/b.txt", "x")
        val entries = fs.list("")
        assertTrue(entries.any { it.name == "a.txt" && !it.isDirectory && it.sizeBytes == 5L })
        assertTrue(entries.any { it.name == "sub" && it.isDirectory })
    }

    @Test fun `path traversal is refused by the abstraction`() {
        assertTrue(fs.safeRelative("../../etc/passwd").isFailure)
        assertTrue(fs.readText("../../etc/hosts").isFailure)
        assertTrue(fs.writeText("../escaped", "x").isFailure)
        assertFalse(fs.exists("../escaped"))
    }

    @Test fun `benign in-root dotdot segment still resolves`() {
        // Guards against an over-strict guard that would reject legitimate paths.
        assertEquals("src/A.kt", fs.safeRelative("src/../src/A.kt").getOrNull())
    }

    @Test fun `absolute paths are refused`() {
        assertTrue(fs.safeRelative("/etc/passwd").isFailure)
    }

    @Test fun `delete removes file`() {
        fs.writeText("gone.txt", "x")
        assertTrue(fs.delete("gone.txt").isSuccess)
        assertFalse(fs.exists("gone.txt"))
    }
}

class HostToolExecutorTest {
    private lateinit var dir: java.nio.file.Path
    private lateinit var fs: LocalWorkspaceFileSystem
    private lateinit var exec: HostToolExecutor

    @BeforeTest fun setUp() {
        dir = Files.createTempDirectory("fb-host")
        fs = LocalWorkspaceFileSystem(dir)
        exec = HostToolExecutor(fs)
    }

    @AfterTest fun tearDown() { dir.toFile().deleteRecursively() }

    private fun call(tool: ClientToolName, input: Map<String, Any?>) =
        exec.execute(ClientToolCall(tool, input))

    @Test fun `write_file creates content and returns a review diff`() {
        val out = call(ClientToolName.WRITE_FILE, mapOf("type" to "file", "path" to "n.txt", "content" to "hello\nworld"))
        assertTrue(out is HostToolExecutor.Outcome.Edited, "got $out")
        out as HostToolExecutor.Outcome.Edited
        assertEquals("hello\nworld", fs.readText("n.txt").getOrNull())
        assertTrue(out.diff.contains("+hello"), out.diff)
        assertEquals("", out.before)
    }

    @Test fun `str_replace filechange with patch type applies hunks`() {
        fs.writeText("f.txt", "one\ntwo\nthree")
        val patch = "--- a/f.txt\n+++ b/f.txt\n@@ -1,3 +1,3 @@\n one\n-two\n+2\n three"
        val out = call(ClientToolName.STR_REPLACE, mapOf("type" to "patch", "path" to "f.txt", "content" to patch))
        assertTrue(out is HostToolExecutor.Outcome.Edited, "got $out")
        assertEquals("one\n2\nthree", fs.readText("f.txt").getOrNull())
        assertTrue(out.diff.contains("-two") && out.diff.contains("+2"), out.diff)
    }

    @Test fun `apply_patch supports create update and delete actions`() {
        val created = call(ClientToolName.APPLY_PATCH, mapOf(
            "operation" to mapOf("type" to "create_file", "path" to "c.txt", "diff" to "+made by agent"),
        ))
        assertTrue(created is HostToolExecutor.Outcome.PatchApplied)
        assertEquals("add", (created as HostToolExecutor.Outcome.PatchApplied).applied.single().action)
        assertEquals("made by agent", fs.readText("c.txt").getOrNull())

        val deleted = call(ClientToolName.APPLY_PATCH, mapOf(
            "operation" to mapOf("type" to "delete_file", "path" to "c.txt"),
        ))
        assertTrue(deleted is HostToolExecutor.Outcome.PatchApplied)
        assertEquals("delete", (deleted as HostToolExecutor.Outcome.PatchApplied).applied.single().action)
        assertFalse(fs.exists("c.txt"))
    }

    @Test fun `apply_patch with bad context is rejected and leaves file intact`() {
        fs.writeText("k.txt", "alpha\nbeta")
        val out = call(ClientToolName.APPLY_PATCH, mapOf(
            "operation" to mapOf("type" to "update_file", "path" to "k.txt", "diff" to "@@ -1,2 +1,2 @@\n-nope\n-beta\n+BETA"),
        ))
        assertTrue(out is HostToolExecutor.Outcome.Rejected, "expected rejection, got $out")
        assertEquals("alpha\nbeta", fs.readText("k.txt").getOrNull())
    }

    @Test fun `sensitive env files are blocked without approval (S16)`() {
        val out = call(ClientToolName.WRITE_FILE, mapOf("type" to "file", "path" to ".env", "content" to "SECRET=1"))
        assertTrue(out is HostToolExecutor.Outcome.Rejected)
        assertFalse(fs.exists(".env"))

        val withheld = exec.readFilesForAgent(listOf("prod/.env"))
        assertTrue(withheld.values.all { it == null })
    }

    @Test fun `read_files is not a client tool upstream, host answers read-files-response`() {
        // Invariant from clientToolCallSchema: if this ever changes upstream, this
        // test fails and the compat matrix must be updated (S26).
        assertFalse(ClientToolName.entries.any { it.wire == "read_files" })
        fs.writeText("a.txt", "content-a")
        assertEquals(mapOf("a.txt" to "content-a"), exec.readFilesForAgent(listOf("a.txt")))
    }

    @Test fun `approval flag permits sensitive access`() {
        val allowed = HostToolExecutor(fs, sensitiveAccessAllowed = true)
        val out = allowed.execute(ClientToolCall(ClientToolName.WRITE_FILE, mapOf("type" to "file", "path" to ".env", "content" to "A=1")))
        assertTrue(out is HostToolExecutor.Outcome.Edited)
        assertTrue(fs.exists(".env"))
    }

    @Test fun `list_directory returns tree entries`() {
        fs.writeText("src/A.kt", "a")
        fs.writeText("src/B.kt", "b")
        val out = call(ClientToolName.LIST_DIRECTORY, mapOf("path" to "src"))
        assertTrue(out is HostToolExecutor.Outcome.Directory)
        assertEquals(listOf("A.kt", "B.kt"), (out as HostToolExecutor.Outcome.Directory).entries.map { it.name })
    }

    @Test fun `code_search finds matching lines with path and line number`() {
        fs.writeText("src/Main.kt", "val greeting = \"hi\"\nval other = 1")
        val out = call(ClientToolName.CODE_SEARCH, mapOf("query" to "greeting"))
        assertTrue(out is HostToolExecutor.Outcome.Matches, "got $out")
        val hit = (out as HostToolExecutor.Outcome.Matches).hits.single()
        assertEquals("src/Main.kt", hit.path)
        assertEquals(1, hit.line)
    }

    @Test fun `code_search skips sensitive files`() {
        fs.writeText("secret/.env", "TOKEN=abc123")
        val out = call(ClientToolName.CODE_SEARCH, mapOf("query" to "TOKEN"))
        assertTrue((out as HostToolExecutor.Outcome.Matches).hits.isEmpty())
    }

    @Test fun `glob matches by pattern`() {
        fs.writeText("src/A.kt", "a")
        fs.writeText("src/deep/B.kt", "b")
        fs.writeText("readme.md", "r")
        val out = call(ClientToolName.GLOB, mapOf("pattern" to "*.kt"))
        assertTrue(out is HostToolExecutor.Outcome.Paths)
        assertEquals(setOf("src/A.kt", "src/deep/B.kt"), (out as HostToolExecutor.Outcome.Paths).paths.toSet())
    }

    @Test fun `unsupported host tool is rejected loudly, not faked`() {
        val out = call(ClientToolName.BROWSER_LOGS, emptyMap())
        assertTrue(out is HostToolExecutor.Outcome.Rejected)
        assertTrue((out as HostToolExecutor.Outcome.Rejected).reason.contains("not executable"))
    }
}
