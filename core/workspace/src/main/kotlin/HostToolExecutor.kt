package com.freebuff.android.workspace

import com.freebuff.android.model.ClientToolName
import com.freebuff.android.model.FileChange
import com.freebuff.android.protocol.ClientToolCall
import com.freebuff.android.security.EnvRedaction

/** Executes the host-side client tool calls against a [WorkspaceFileSystem].
 *  This is the piece that makes "the agent really edits a file on the device"
 *  true rather than simulated (Master Spec S30: no fake tool execution).
 *
 *  Every path crosses PathSafety inside the filesystem, and sensitive env/key
 *  files are refused unless the caller explicitly grants access (S16). */
class HostToolExecutor(
    private val fs: WorkspaceFileSystem,
    private val maxSearchHits: Int = 200,
    private val maxVisitedFiles: Int = 5_000,
    private val sensitiveAccessAllowed: Boolean = false,
) {
    sealed interface Outcome {
        data class Directory(val path: String, val entries: List<TreeEntry>) : Outcome
        data class Edited(val path: String, val diff: String, val before: String, val after: String) : Outcome
        data class PatchApplied(val message: String, val applied: List<AppliedFile>) : Outcome
        data class Matches(val query: String, val hits: List<SearchHit>) : Outcome
        data class Paths(val pattern: String, val paths: List<String>) : Outcome
        data class Rejected(val tool: String, val reason: String) : Outcome
    }

    data class SearchHit(val path: String, val line: Int, val text: String)

    fun execute(call: ClientToolCall): Outcome {
        val input = call.input
        return when (call.toolName) {
            ClientToolName.LIST_DIRECTORY -> {
                val dir = (input["path"] as? String).orEmpty()
                if (blocked(dir, allowDir = true)) return Outcome.Rejected("list_directory", "sensitive path: $dir")
                Outcome.Directory(dir, fs.list(dir))
            }

            ClientToolName.WRITE_FILE, ClientToolName.STR_REPLACE, ClientToolName.CREATE_PLAN ->
                applyFileChange(call.toolName.wire, input)

            ClientToolName.APPLY_PATCH -> applyPatch(input)

            // NOTE: `read_files` is intentionally absent — upstream does NOT dispatch it
            // through clientToolCallSchema. The host answers a `read-files-response`
            // ClientAction instead; see [readFilesForAgent].

            ClientToolName.GLOB -> {
                val pattern = (input["pattern"] as? String) ?: "*"
                Outcome.Paths(pattern, glob(pattern))
            }

            ClientToolName.CODE_SEARCH -> {
                val query = (input["query"] as? String).orEmpty()
                if (query.isEmpty()) Outcome.Rejected("code_search", "empty query")
                else Outcome.Matches(query, search(query))
            }

            else -> Outcome.Rejected(call.toolName.wire, "tool not executable on this host yet")
        }
    }

    private fun applyFileChange(tool: String, input: Map<String, Any?>): Outcome {
        val change = FileChange.parse(
            input["type"] as? String,
            input["path"] as? String,
            input["content"] as? String,
        ) ?: return Outcome.Rejected(tool, "invalid FileChangeSchema")
        if (blocked(change.path)) return Outcome.Rejected(tool, "sensitive file requires explicit approval: ${change.path}")

        val before = fs.readText(change.path).getOrNull() ?: ""
        val after = when (change.type) {
            com.freebuff.android.model.FileChangeType.FILE -> change.content
            com.freebuff.android.model.FileChangeType.PATCH -> {
                val op = PatchAction.Update(change.path, change.content)
                when (val r = ApplyPatch.apply(op, { fs.readText(it).getOrNull() }, { p, c -> fs.writeText(p, c) }, { fs.delete(it) })) {
                    is PatchResult.Error -> return Outcome.Rejected(tool, r.errorMessage)
                    is PatchResult.Ok -> {
                        // The applier already wrote; re-read for an accurate diff view.
                        return Outcome.Edited(
                            path = change.path,
                            diff = UnifiedDiff.format("a/${change.path}", "b/${change.path}", before.split("\n"), fs.readText(change.path).getOrNull().orEmpty().split("\n")),
                            before = before,
                            after = fs.readText(change.path).getOrNull().orEmpty(),
                        )
                    }
                }
            }
        }
        fs.writeText(change.path, after).onFailure {
            return Outcome.Rejected(tool, "write failed: ${it.message?.let(EnvRedaction::redactText)}")
        }
        return Outcome.Edited(
            path = change.path,
            diff = UnifiedDiff.format("a/${change.path}", "b/${change.path}", before.split("\n"), after.split("\n")),
            before = before,
            after = after,
        )
    }

    private fun applyPatch(input: Map<String, Any?>): Outcome {
        @Suppress("UNCHECKED_CAST")
        val op = input["operation"] as? Map<String, Any?>
            ?: return Outcome.Rejected("apply_patch", "missing operation")
        val type = op["type"] as? String
        val path = op["path"] as? String ?: return Outcome.Rejected("apply_patch", "missing path")
        val diff = op["diff"] as? String
        if (blocked(path)) return Outcome.Rejected("apply_patch", "sensitive file requires explicit approval: $path")
        val action = when {
            type == "create_file" && diff != null -> PatchAction.Create(path, diff)
            type == "update_file" && diff != null -> PatchAction.Update(path, diff)
            type == "delete_file" -> PatchAction.Delete(path)
            else -> return Outcome.Rejected("apply_patch", "unknown operation shape: $type")
        }
        return when (val r = ApplyPatch.apply(action, { fs.readText(it).getOrNull() }, { p, c -> fs.writeText(p, c) }, { fs.delete(it) })) {
            is PatchResult.Ok -> Outcome.PatchApplied(r.message, r.applied)
            is PatchResult.Error -> Outcome.Rejected("apply_patch", r.errorMessage)
        }
    }

    /** Answers upstream's `read-files-response` action: contents for the requested
     *  paths, with sensitive files withheld (null) unless access was granted (S16). */
    fun readFilesForAgent(paths: List<String>): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        for (p in paths) out[p] = if (blocked(p)) null else fs.readText(p).getOrNull()
        return out
    }

    private fun blocked(path: String, allowDir: Boolean = false): Boolean {
        if (sensitiveAccessAllowed) return false
        if (path.isBlank()) return false
        val probe = if (allowDir) "$path/placeholder" else path
        return IgnoreRules.isSensitive(probe)
    }

    /** Depth-bounded recursive walk honoring ignore rules + caps (S22). */
    private fun walk(dir: String, depth: Int, acc: MutableList<TreeEntry>) {
        if (depth > 12 || acc.size >= maxVisitedFiles) return
        for (e in fs.list(dir)) {
            if (IgnoreRules.matches(IgnoreRules.DEFAULT_DIRS.toList(), e.path, e.isDirectory)) continue
            acc.add(e)
            if (e.isDirectory) walk(e.path, depth + 1, acc)
            if (acc.size >= maxVisitedFiles) return
        }
    }

    fun glob(pattern: String): List<String> {
        val all = ArrayList<TreeEntry>()
        walk("", 0, all)
        return all.filter { !it.isDirectory && IgnoreRules.globMatch(pattern, it.name) }
            .map { it.path }
    }

    fun search(query: String): List<SearchHit> {
        val all = ArrayList<TreeEntry>()
        walk("", 0, all)
        val hits = ArrayList<SearchHit>()
        outer@ for (e in all.filter { !it.isDirectory && !IgnoreRules.isSensitive(it.path) }) {
            val text = fs.readText(e.path).getOrNull() ?: continue
            var lineNo = 0
            for (line in text.lineSequence()) {
                lineNo++
                if (line.contains(query, ignoreCase = true)) {
                    hits.add(SearchHit(e.path, lineNo, line.trim().take(200)))
                    if (hits.size >= maxSearchHits) break@outer
                }
            }
        }
        return hits
    }
}
