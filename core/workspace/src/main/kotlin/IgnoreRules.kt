package com.freebuff.android.workspace

// Ignore semantics for project browsing. Upstream ships a `.codebuffignore` file
// (see repo root of CodebuffAI/freebuff) and uses `ignore`-style patterns; the
// Android host honors the same file plus a conservative default set so a huge
// repository never blocks the UI (Master Spec S10 / S22).

object IgnoreRules {
    val DEFAULT_DIRS = setOf(
        ".git", ".hg", ".svn", "node_modules", "build", "dist", "out", "target",
        ".gradle", ".idea", ".next", ".nuxt", "coverage", "__pycache__", ".venv",
        "venv", ".pytest_cache", "DerivedData", ".cxx",
    )
    val DEFAULT_FILES = setOf(".DS_Store", "Thumbs.db")

    /** Sensitive files that must never be handed to the agent silently (S16). */
    val SENSITIVE_BASENAMES = setOf(".env", ".env.local", ".env.production", "credentials.json", "secrets.json")
    val SENSITIVE_SUFFIXES = listOf(".pem", ".key", ".p12", ".jks", ".keystore")

    fun parseIgnoreFile(content: String): List<String> = content
        .split("\n")
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }

    fun isSensitive(path: String): Boolean {
        val base = path.substringAfterLast('/')
        if (SENSITIVE_BASENAMES.contains(base)) return true
        if (base.startsWith(".env.")) return true
        return SENSITIVE_SUFFIXES.any { base.endsWith(it) }
    }

    /** A pattern is a dir name ("node_modules"), a glob ("*.log"), or a path prefix. */
    fun matches(patterns: List<String>, path: String, isDir: Boolean): Boolean {
        val segments = path.split('/').filter { it.isNotEmpty() }
        val base = segments.lastOrNull() ?: return false
        for (p in patterns) {
            val pat = if (p.endsWith("/")) p.removeSuffix("/") else p
            if (pat.startsWith("**")) {
                val tail = pat.removePrefix("**/")
                if (segments.any { globMatch(tail, it) }) return true
                continue
            }
            if (pat.contains('/')) {
                if (path.startsWith(pat.removeSuffix("/"))) return true
                continue
            }
            if (globMatch(pat, base)) return true
        }
        return false
    }

    /** Minimal glob: supports *, ?, and ** segments. */
    fun globMatch(pattern: String, name: String): Boolean {
        if (pattern == "**") return true
        val regex = buildString {
            append('^')
            var i = 0
            while (i < pattern.length) {
                when (val c = pattern[i]) {
                    '*' -> {
                        if (i + 1 < pattern.length && pattern[i + 1] == '*') { append(".*"); i += 2 }
                        else { append("[^/]*"); i++ }
                        continue
                    }
                    '?' -> append("[^/]")
                    '.', '(', ')', '+', '|', '^', '$', '{', '}', '[', ']', '\\' -> append('\\').append(c)
                    else -> append(c)
                }
                i++
            }
            append('$')
        }
        return Regex(regex).matches(name)
    }
}

/** Immutable tree node; children are loaded lazily/streamed by the caller. */
data class TreeEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0,
    val children: List<TreeEntry> = emptyList(),
)

class FileTreeBuilder(
    private val maxEntries: Int = 5_000,
    private val maxDepth: Int = 12,
    private val hiddenVisible: Boolean = false,
) {
    var truncated: Boolean = false; private set
    private var visited = 0

    /** [list] returns direct children of [path]; ignore patterns apply at every level. */
    fun build(path: String, depth: Int, list: (String) -> List<TreeEntry>, ignore: List<String>): List<TreeEntry> {
        if (depth > maxDepth || visited >= maxEntries) { truncated = true; return emptyList() }
        val children = list(path).sortedWith(compareByDescending<TreeEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        val out = ArrayList<TreeEntry>()
        for (c in children) {
            if (!hiddenVisible && c.name.startsWith(".") && c.name != ".") { continue }
            if (IgnoreRules.matches(ignore, c.path, c.isDirectory)) continue
            if (IgnoreRules.matches(IgnoreRules.DEFAULT_DIRS.toList() + IgnoreRules.DEFAULT_FILES.toList(), c.path, c.isDirectory)) continue
            if (visited >= maxEntries) { truncated = true; break }
            visited++
            if (c.isDirectory) {
                out.add(c.copy(children = build(c.path, depth + 1, list, ignore)))
            } else {
                out.add(c)
            }
        }
        return out
    }
}

/** Dirty-state tracking for the editor (S9: dirty indicator, autosave/recovery). */
class EditSession(val path: String, val loadedContent: String) {
    var content: String = loadedContent; private set

    fun edit(newContent: String) { content = newContent }
    fun isDirty(): Boolean = content != loadedContent
    fun revert() { content = loadedContent }
    fun saved() { /* caller updates loadedContent after persisting */ }
    fun withSavedContent() { EditSession(path, content) }

    /** Line diff against what is on disk, for the review surface. */
    fun diff(): String = UnifiedDiff.format(
        "a/$path", "b/$path",
        loadedContent.split("\n"), content.split("\n"),
    )
}
