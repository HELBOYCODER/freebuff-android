package com.freebuff.android.workspace

// Host-side implementation of the str_replace/edit semantics the agent relies on
// (upstream str_replace/write_file deliver a FileChange; the target edit text is
// matched exactly). Ambiguous or missing matches must fail rather than corrupt a
// file, matching the "make failure states explicit" rule (S30).
object StrReplace {
    sealed class Outcome {
        data class Applied(val content: String) : Outcome()
        data class NotFound(val needle: String) : Outcome()
        data class Ambiguous(val count: Int) : Outcome()
    }

    fun replace(original: String, oldText: String, newText: String, expectMultiple: Boolean = false): Outcome {
        if (oldText.isEmpty()) return Outcome.NotFound(oldText)
        val count = countOccurrences(original, oldText)
        if (count == 0) return Outcome.NotFound(oldText)
        if (count > 1 && !expectMultiple) return Outcome.Ambiguous(count)
        return Outcome.Applied(original.replace(oldText, newText))
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var c = 0; var i = 0
        while (true) { val idx = haystack.indexOf(needle, i); if (idx < 0) break; c++; i = idx + needle.length }
        return c
    }
}
