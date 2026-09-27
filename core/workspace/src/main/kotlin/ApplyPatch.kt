package com.freebuff.android.workspace

// Host-side engine for upstream's `apply_patch` (Codex-style) and `str_replace`
// tool params, as defined at commit 25f1d61 in
// common/src/tools/params/tool/{apply-patch,str-replace}.ts.
//
// Upstream apply_patch operation:  { type: create_file|update_file|delete_file, path, diff }
// Upstream apply_patch result:     { message, applied: [{file, action: add|update|delete}] }
//                              or  { errorMessage }
// Upstream str_replace input:      { path, replacements: [{oldString, newString, allowMultiple?}] }

sealed interface PatchAction {
    val file: String
    data class Create(override val file: String, val diff: String) : PatchAction
    data class Update(override val file: String, val diff: String) : PatchAction
    data class Delete(override val file: String) : PatchAction
}

data class AppliedFile(val file: String, val action: String)

sealed interface PatchResult {
    data class Ok(val message: String, val applied: List<AppliedFile>) : PatchResult
    data class Error(val errorMessage: String) : PatchResult
}

object ApplyPatch {

    /** Internal outcome of hunk application: new content, or a failure reason. */
    internal sealed interface HunkOutcome {
        data class Success(val content: String) : HunkOutcome
        data class Failure(val reason: String) : HunkOutcome
    }

    /** Apply one operation to a filesystem-like store (path -> content or null when absent). */
    fun apply(
        op: PatchAction,
        read: (String) -> String?,
        write: (String, String) -> Unit,
        delete: (String) -> Unit,
    ): PatchResult = when (op) {
        is PatchAction.Create -> {
            when {
                read(op.file) != null ->
                    PatchResult.Error("cannot create ${op.file}: file already exists")
                else -> {
                    val content = stripToAdditionsOnly(op.diff)
                    if (content.isEmpty()) {
                        PatchResult.Error("create_file diff for ${op.file} has no + lines")
                    } else {
                        write(op.file, content)
                        PatchResult.Ok("created ${op.file}", listOf(AppliedFile(op.file, "add")))
                    }
                }
            }
        }

        is PatchAction.Update -> {
            val current = read(op.file)
            if (current == null) {
                PatchResult.Error("cannot update ${op.file}: file not found")
            } else {
                when (val applied = applyHunks(op.file, current, op.diff)) {
                    is HunkOutcome.Failure -> PatchResult.Error(applied.reason)
                    is HunkOutcome.Success -> {
                        write(op.file, applied.content)
                        PatchResult.Ok(
                            "updated ${op.file}",
                            listOf(AppliedFile(op.file, "update")),
                        )
                    }
                }
            }
        }

        is PatchAction.Delete -> {
            if (read(op.file) == null) {
                PatchResult.Error("cannot delete ${op.file}: file not found")
            } else {
                delete(op.file)
                PatchResult.Ok("deleted ${op.file}", listOf(AppliedFile(op.file, "delete")))
            }
        }
    }

    /** A create diff carries only '+' lines (and optionally a leading body marker). */
    private fun stripToAdditionsOnly(diff: String): String {
        val lines = diff.split("\n").map { it.removePrefix("\r") }
        val kept = lines.filter { it.startsWith("+") }.map { it.substring(1) }
        return kept.joinToString("\n").trimStart('\n')
    }

    /**
     * Apply a unified diff with @@ hunks. Context lines must match exactly; a
     * mismatch is an explicit failure rather than a silent partial write
     * (Master Spec S30 "make failure states explicit").
     */
    internal fun applyHunks(path: String, original: String, diff: String): HunkOutcome {
        val old = original.split("\n").toMutableList()
        val hunks = parseHunks(diff)
        if (hunks.isEmpty()) return HunkOutcome.Failure("no hunks found in patch for $path")

        // Apply bottom-up so earlier hunk line numbers stay valid.
        val ordered = hunks.sortedByDescending { it.oldStart }
        for (h in ordered) {
            val at = h.oldStart - 1
            if (at < 0 || at > old.size) {
                return HunkOutcome.Failure("hunk offset out of range in $path: ${h.oldStart}")
            }
            val expected = h.lines.filter { it.startsWith(" ") || it.startsWith("-") }.map { it.substring(1) }
            val actual = old.subList(at, minOf(at + expected.size, old.size)).toList()
            if (actual != expected) {
                return HunkOutcome.Failure("context mismatch in $path at line ${h.oldStart}")
            }
            val removals = expected.size
            val additions = h.lines.filter { it.startsWith(" ") || it.startsWith("+") }.map { it.substring(1) }
            repeat(removals) { if (at < old.size) old.removeAt(at) }
            old.addAll(at, additions)
        }
        return HunkOutcome.Success(old.joinToString("\n"))
    }

    internal data class ParsedHunk(val oldStart: Int, val oldCount: Int, val newStart: Int, val newCount: Int, val lines: List<String>)

    internal fun parseHunks(diff: String): List<ParsedHunk> {
        val out = ArrayList<ParsedHunk>()
        var current: MutableList<String>? = null
        var hdr: IntArray? = null
        val header = Regex("""^@@\s+-(\d+)(?:,(\d+))?\s+\+(\d+)(?:,(\d+))?\s+@@""")
        for (raw in diff.split("\n")) {
            val line = raw.removePrefix("\r")
            val m = header.find(line)
            if (m != null) {
                if (current != null && hdr != null) out.add(build(hdr!!, current!!))
                hdr = intArrayOf(
                    m.groupValues[1].toInt(),
                    m.groupValues[2].ifEmpty { "1" }.toInt(),
                    m.groupValues[3].toInt(),
                    m.groupValues[4].ifEmpty { "1" }.toInt(),
                )
                current = ArrayList()
                continue
            }
            if (current == null) continue
            if (line.startsWith("--- ") || line.startsWith("+++ ") || line.startsWith("\\ ")) continue
            if (line.startsWith("+") || line.startsWith("-") || line.startsWith(" ")) current.add(line)
        }
        if (current != null && hdr != null) out.add(build(hdr!!, current!!))
        return out
    }

    private fun build(hdr: IntArray, lines: List<String>) =
        ParsedHunk(hdr[0], hdr[1], hdr[2], hdr[3], lines)
}

/** Faithful multi-replacement semantics: ordered pairs, empty newString deletes,
 *  ambiguous matches rejected unless allowMultiple. */
object StrReplaceOps {
    data class Replacement(val oldString: String, val newString: String, val allowMultiple: Boolean = false)

    sealed interface Outcome {
        data class Applied(val content: String, val count: Int) : Outcome
        data class NotFound(val needle: String) : Outcome
        data class Ambiguous(val needle: String, val count: Int) : Outcome
        data class EmptyNeedle(val index: Int) : Outcome
    }

    fun applyAll(original: String, replacements: List<Replacement>): Outcome {
        var text = original
        var total = 0
        replacements.forEachIndexed { index, r ->
            if (r.oldString.isEmpty()) return Outcome.EmptyNeedle(index)
            val count = countOccurrences(text, r.oldString)
            if (count == 0) return Outcome.NotFound(r.oldString)
            if (count > 1 && !r.allowMultiple) return Outcome.Ambiguous(r.oldString, count)
            text = text.replace(r.oldString, r.newString)
            total += count
        }
        return Outcome.Applied(text, total)
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var c = 0; var i = 0
        while (true) {
            val idx = haystack.indexOf(needle, i)
            if (idx < 0) break
            c++; i = idx + needle.length
        }
        return c
    }
}
