package com.freebuff.android.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnifiedDiffTest {
    @Test fun `keeps inserts and deletes are labelled`() {
        val ops = UnifiedDiff.ops(listOf("a", "b", "c"), listOf("a", "X", "c"))
        assertEquals(4, ops.size)
        assertTrue(ops[0] is UnifiedDiff.Op.Keep)
        assertTrue(ops[1] is UnifiedDiff.Op.Delete)
        assertTrue(ops[2] is UnifiedDiff.Op.Insert)
        assertTrue(ops[3] is UnifiedDiff.Op.Keep)
    }

    @Test fun `format emits unified headers and hunk lines`() {
        val text = UnifiedDiff.format(
            "a/f.txt", "b/f.txt",
            listOf("one", "two", "three"),
            listOf("one", "TWO", "three"),
        )
        assertTrue(text.startsWith("--- a/f.txt\n+++ b/f.txt\n"), text)
        assertTrue(text.contains("@@ -"), text)
        assertTrue(text.contains("-two"), text)
        assertTrue(text.contains("+TWO"), text)
    }

    @Test fun `identical input produces no hunks`() {
        assertEquals("", UnifiedDiff.format("a", "b", listOf("x"), listOf("x")))
    }

    // Regression: hunk grouping produced wrong line offsets, so a generated patch
    // could not be applied back. Round-tripping catches offset math errors directly.
    @Test fun `generated patch round-trips through the applier for a middle edit`() {
        val old = (1..20).map { "line $it" }
        val new = old.toMutableList().also { it[9] = "LINE TEN CHANGED" }
        val patch = UnifiedDiff.format("a/f", "b/f", old, new)
        val fs = mutableMapOf("f" to old.joinToString("\n"))
        val r = ApplyPatch.apply(
            PatchAction.Update("f", patch),
            read = { fs[it] }, write = { k, v -> fs[k] = v }, delete = { fs.remove(it) },
        )
        assertTrue(r is PatchResult.Ok, "applier rejected its own patch: $r\n$patch")
        assertEquals(new.joinToString("\n"), fs["f"])
    }

    @Test fun `generated multi-hunk patch round-trips`() {
        val old = (1..40).map { "l$it" }
        val new = old.toMutableList().also {
            it[2] = "CHANGED-3"; it.removeAt(20); it.add("APPENDED")
        }
        val patch = UnifiedDiff.format("a/f", "b/f", old, new, context = 3)
        val fs = mutableMapOf("f" to old.joinToString("\n"))
        val r = ApplyPatch.apply(
            PatchAction.Update("f", patch),
            read = { fs[it] }, write = { k, v -> fs[k] = v }, delete = { fs.remove(it) },
        )
        assertTrue(r is PatchResult.Ok, "multi-hunk patch failed to apply: $r\n$patch")
        assertEquals(new.joinToString("\n"), fs["f"])
    }
}

class ApplyPatchTest {
    private fun store(initial: Map<String, String>) = initial.toMutableMap()

    @Test fun `create_file writes additions only`() {
        val fs = store(emptyMap())
        val r = ApplyPatch.apply(
            PatchAction.Create("n.txt", "+hello\n+world"),
            read = { fs[it] }, write = { k, v -> fs[k] = v }, delete = { fs.remove(it) },
        )
        assertTrue(r is PatchResult.Ok)
        assertEquals(listOf(AppliedFile("n.txt", "add")), (r as PatchResult.Ok).applied)
        assertEquals("hello\nworld", fs["n.txt"])
    }

    @Test fun `create_file over existing file fails explicitly`() {
        val fs = store(mapOf("x.txt" to "here"))
        val r = ApplyPatch.apply(
            PatchAction.Create("x.txt", "+new"),
            read = { fs[it] }, write = { k, v -> fs[k] = v }, delete = { fs.remove(it) },
        )
        assertTrue(r is PatchResult.Error)
        assertTrue((r as PatchResult.Error).errorMessage.contains("already exists"))
    }

    @Test fun `update_file applies a hunk by context`() {
        val fs = store(mapOf("f.txt" to "one\ntwo\nthree"))
        val diff = "--- a/f.txt\n+++ b/f.txt\n@@ -1,3 +1,3 @@\n one\n-two\n+TWO\n three"
        val r = ApplyPatch.apply(
            PatchAction.Update("f.txt", diff),
            read = { fs[it] }, write = { k, v -> fs[k] = v }, delete = { fs.remove(it) },
        )
        assertEquals("one\nTWO\nthree", fs["f.txt"], "result was $r")
        assertEquals(listOf(AppliedFile("f.txt", "update")), (r as PatchResult.Ok).applied)
    }

    @Test fun `update_file with mismatched context fails and does not write`() {
        val fs = store(mapOf("f.txt" to "alpha\nbeta"))
        val diff = "@@ -1,2 +1,2 @@\n wrong\n-beta\n+BETA"
        val r = ApplyPatch.apply(
            PatchAction.Update("f.txt", diff),
            read = { fs[it] }, write = { k, v -> fs[k] = v }, delete = { fs.remove(it) },
        )
        assertTrue(r is PatchResult.Error, "expected error, got $r")
        assertTrue((r as PatchResult.Error).errorMessage.contains("context mismatch"))
        assertEquals("alpha\nbeta", fs["f.txt"], "file must not be partially written")
    }

    @Test fun `update_file on missing file errors`() {
        val fs = store(emptyMap())
        val r = ApplyPatch.apply(
            PatchAction.Update("gone.txt", "@@ -1,1 +1,1 @@\n-a\n+b"),
            read = { fs[it] }, write = { k, v -> fs[k] = v }, delete = { fs.remove(it) },
        )
        assertTrue(r is PatchResult.Error)
    }

    @Test fun `delete_file removes and reports delete action`() {
        val fs = store(mapOf("d.txt" to "x"))
        val r = ApplyPatch.apply(
            PatchAction.Delete("d.txt"),
            read = { fs[it] }, write = { k, v -> fs[k] = v }, delete = { fs.remove(it) },
        )
        assertFalse(fs.containsKey("d.txt"))
        assertEquals(listOf(AppliedFile("d.txt", "delete")), (r as PatchResult.Ok).applied)
    }

    @Test fun `parseHunks reads multiple hunks with optional counts omitted`() {
        val diff = "@@ -1,3 +1,3 @@\n a\n-b\n+B\n@@ -10 +10 @@\n c\n+C"
        val hs = ApplyPatch.parseHunks(diff)
        assertEquals(2, hs.size)
        assertEquals(1, hs[0].oldStart); assertEquals(3, hs[0].oldCount)
        assertEquals(10, hs[1].oldStart); assertEquals(1, hs[1].oldCount)
    }
}

class StrReplaceOpsTest {
    @Test fun `ordered replacements apply in sequence`() {
        val r = StrReplaceOps.applyAll("a=1\nb=2", listOf(
            StrReplaceOps.Replacement("1", "10"),
            StrReplaceOps.Replacement("2", "20"),
        ))
        assertTrue(r is StrReplaceOps.Outcome.Applied)
        assertEquals("a=10\nb=20", (r as StrReplaceOps.Outcome.Applied).content)
        assertEquals(2, r.count)
    }

    @Test fun `empty newString deletes the needle (upstream semantics)`() {
        val r = StrReplaceOps.applyAll("keep drop keep", listOf(
            StrReplaceOps.Replacement("drop ", "", allowMultiple = false),
        ))
        assertEquals("keep keep", (r as StrReplaceOps.Outcome.Applied).content)
    }

    @Test fun `missing needle fails`() {
        assertTrue(StrReplaceOps.applyAll("abc", listOf(StrReplaceOps.Replacement("z", "y")))
            is StrReplaceOps.Outcome.NotFound)
    }

    @Test fun `ambiguous needle fails without allowMultiple and passes with it`() {
        val s = "foo foo"
        assertTrue(StrReplaceOps.applyAll(s, listOf(StrReplaceOps.Replacement("foo", "bar")))
            is StrReplaceOps.Outcome.Ambiguous)
        val ok = StrReplaceOps.applyAll(s, listOf(StrReplaceOps.Replacement("foo", "bar", allowMultiple = true)))
        assertEquals("bar bar", (ok as StrReplaceOps.Outcome.Applied).content)
        assertEquals(2, ok.count)
    }

    @Test fun `empty oldString is rejected explicitly`() {
        assertTrue(StrReplaceOps.applyAll("x", listOf(StrReplaceOps.Replacement("", "y")))
            is StrReplaceOps.Outcome.EmptyNeedle)
    }
}

class IgnoreRulesTest {
    @Test fun `codebuffignore-style patterns parse with comments`() {
        val pats = IgnoreRules.parseIgnoreFile("node_modules\n# comment\n*.log\nbuild/")
        assertEquals(listOf("node_modules", "*.log", "build/"), pats)
    }

    @Test fun `directory and glob patterns match paths`() {
        val pats = listOf("node_modules", "*.log", "dist")
        assertTrue(IgnoreRules.matches(pats, "src/node_modules", true))
        assertTrue(IgnoreRules.matches(pats, "app/debug.log", false))
        assertTrue(IgnoreRules.matches(pats, "dist", true))
        assertFalse(IgnoreRules.matches(pats, "src/main.kt", false))
    }

    @Test fun `double star glob matches nested names`() {
        assertTrue(IgnoreRules.matches(listOf("**/generated"), "a/b/generated/File.kt", false))
    }

    @Test fun `sensitive env and key files are detected (S16)`() {
        assertTrue(IgnoreRules.isSensitive("proj/.env"))
        assertTrue(IgnoreRules.isSensitive("proj/config/.env.local"))
        assertTrue(IgnoreRules.isSensitive("certs/server.pem"))
        assertTrue(IgnoreRules.isSensitive("app/release.jks"))
        assertFalse(IgnoreRules.isSensitive("src/App.kt"))
    }
}

class FileTreeBuilderTest {
    private val flat = mapOf(
        "" to listOf(
            TreeEntry("src", "src", true),
            TreeEntry("node_modules", "node_modules", true),
            TreeEntry(".git", ".git", true),
            TreeEntry("readme.md", "readme.md", false, 10),
        ),
        "src" to listOf(TreeEntry("src/A.kt", "A.kt", false, 5)),
    )

    @Test fun `default ignore set prunes node_modules and git`() {
        val b = FileTreeBuilder()
        val tree = b.build("", 0, { flat[it] ?: emptyList() }, emptyList())
        assertEquals(listOf("src", "readme.md"), tree.map { it.name })
    }

    @Test fun `directories sort before files`() {
        val b = FileTreeBuilder()
        val tree = b.build("", 0, { flat[it] ?: emptyList() }, emptyList())
        assertTrue(tree.first().isDirectory)
    }

    @Test fun `nested children are built`() {
        val b = FileTreeBuilder()
        val tree = b.build("", 0, { flat[it] ?: emptyList() }, emptyList())
        assertEquals(listOf("A.kt"), tree.first().children.map { it.name })
    }

    @Test fun `entry cap marks truncation instead of hanging (S22)`() {
        val many = (1..50).map { TreeEntry("f$it", "f$it", false) }
        val b = FileTreeBuilder(maxEntries = 5)
        val tree = b.build("", 0, { if (true) many else emptyList() }, emptyList())
        assertEquals(5, tree.size)
        assertTrue(b.truncated)
    }
}

class EditSessionTest {
    @Test fun `dirty state tracks edits and revert`() {
        val s = EditSession("a.txt", "one\ntwo")
        assertFalse(s.isDirty())
        s.edit("one\nTWO")
        assertTrue(s.isDirty())
        s.revert()
        assertFalse(s.isDirty())
    }

    @Test fun `diff renders pending change for review surface`() {
        val s = EditSession("a.txt", "one\ntwo")
        s.edit("one\n2")
        val d = s.diff()
        assertTrue(d.contains("-two"), d)
        assertTrue(d.contains("+2"), d)
    }
}
