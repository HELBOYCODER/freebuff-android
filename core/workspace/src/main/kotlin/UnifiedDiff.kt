package com.freebuff.android.workspace

// Unified diff over lines, used both to render the diff-review UI and to produce
// patches the applier can round-trip. Line-based LCS; deliberately no external dep
// so the behavior is fully unit-testable on the JVM.
object UnifiedDiff {
    data class Hunk(
        val oldStart: Int, // 1-based
        val oldCount: Int,
        val newStart: Int,
        val newCount: Int,
        val lines: List<String>, // prefixed with ' ', '+', '-'
    )

    sealed interface Op {
        data class Keep(val line: String) : Op
        data class Delete(val line: String) : Op
        data class Insert(val line: String) : Op
    }

    fun ops(old: List<String>, new: List<String>): List<Op> {
        val n = old.size; val m = new.size
        // LCS table (small files; large-file guard lives in the caller).
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (old[i] == new[j]) lcs[i + 1][j + 1] + 1
            else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = ArrayList<Op>()
        var i = 0; var j = 0
        while (i < n && j < m) {
            when {
                old[i] == new[j] -> { out.add(Op.Keep(old[i])); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { out.add(Op.Delete(old[i])); i++ }
                else -> { out.add(Op.Insert(new[j])); j++ }
            }
        }
        while (i < n) { out.add(Op.Delete(old[i])); i++ }
        while (j < m) { out.add(Op.Insert(new[j])); j++ }
        return out
    }

    fun apply(oldText: String, newText: String): String {
        val out = StringBuilder()
        for (op in ops(oldText.split("\n"), newText.split("\n"))) {
            when (op) {
                is Op.Keep -> out.append(' ').append(op.line).append('\n')
                is Op.Delete -> out.append('-').append(op.line).append('\n')
                is Op.Insert -> out.append('+').append(op.line).append('\n')
            }
        }
        return out.toString().trimEnd('\n')
    }

    /** Group ops into hunks with [context] lines of surrounding context. */
    fun hunks(old: List<String>, new: List<String>, context: Int = 3): List<Hunk> {
        val opsList = ops(old, new)
        val changed = opsList.indices.filter { opsList[it] !is Op.Keep }
        if (changed.isEmpty()) return emptyList()

        // Contiguous regions: merge changed ops whose gap is <= 2*context.
        val regions = ArrayList<IntRange>()
        var start = changed.first()
        var prev = changed.first()
        for (idx in changed.drop(1)) {
            if (idx - prev > context * 2) {
                regions.add(start..prev); start = idx
            }
            prev = idx
        }
        regions.add(start..prev)

        // Prefix counts of old/new lines consumed before each op index.
        val oldBefore = IntArray(opsList.size + 1)
        val newBefore = IntArray(opsList.size + 1)
        for (i in opsList.indices) {
            val op = opsList[i]
            oldBefore[i + 1] = oldBefore[i] + if (op is Op.Keep || op is Op.Delete) 1 else 0
            newBefore[i + 1] = newBefore[i] + if (op is Op.Keep || op is Op.Insert) 1 else 0
        }

        return regions.map { region ->
            val from = maxOf(0, region.first - context)
            val to = minOf(opsList.size - 1, region.last + context)
            val lines = ArrayList<String>()
            var oCount = 0
            var nCount = 0
            for (i in from..to) {
                when (val op = opsList[i]) {
                    is Op.Keep -> { lines.add(" ${op.line}"); oCount++; nCount++ }
                    is Op.Delete -> { lines.add("-${op.line}"); oCount++ }
                    is Op.Insert -> { lines.add("+${op.line}"); nCount++ }
                }
            }
            val oldStart = if (oCount == 0) oldBefore[from] + 1 else oldBefore[from] + 1
            val newStart = if (nCount == 0) newBefore[from] + 1 else newBefore[from] + 1
            Hunk(oldStart, oCount, newStart, nCount, lines)
        }
    }

    fun format(pathA: String, pathB: String, old: List<String>, new: List<String>, context: Int = 3): String {
        val hs = hunks(old, new, context)
        if (hs.isEmpty()) return ""
        val sb = StringBuilder("--- $pathA\n+++ $pathB\n")
        for (h in hs) {
            sb.append("@@ -${h.oldStart},${h.oldCount} +${h.newStart},${h.newCount} @@\n")
            h.lines.forEach { sb.append(it).append('\n') }
        }
        return sb.toString().trimEnd('\n')
    }
}
