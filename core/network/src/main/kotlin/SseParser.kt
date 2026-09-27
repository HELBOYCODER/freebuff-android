package com.freebuff.android.network

/** Minimal, allocation-cheap Server-Sent-Events frame reader.
 *  Handles `data:` lines (including the OpenAI-style `data: [DONE]` sentinel),
 *  multi-line data joined with '\n', `event:` names, comment/keep-alive lines,
 *  and dispatch on the blank line that terminates a frame.
 *
 *  Upstream's chat/completions route streams SSE (see the "server has flushed
 *  SSE headers" note in web/src/app/api/v1/chat/completions), so the Android
 *  client must parse the same wire format. */
class SseParser {
    data class Event(val data: String, val event: String? = null)

    private val dataLines = StringBuilder()
    private var eventName: String? = null
    private var sawField = false

    /** Feed one decoded line (no trailing CR/LF). Returns an Event when a frame completes. */
    fun feedLine(rawLine: String): Event? {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) {
            if (!sawField) { dataLines.setLength(0); eventName = null; return null }
            val ev = Event(dataLines.toString(), eventName)
            dataLines.setLength(0); eventName = null; sawField = false
            return ev
        }
        if (line.startsWith(":")) return null // comment / keep-alive
        val idx = line.indexOf(':')
        val field = if (idx < 0) line else line.substring(0, idx)
        var value = if (idx < 0) "" else line.substring(idx + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "data" -> { if (dataLines.isNotEmpty()) dataLines.append('\n'); dataLines.append(value); sawField = true }
            "event" -> { eventName = value; sawField = true }
            "id", "retry" -> sawField = true
            else -> Unit // unknown field ignored per spec
        }
        return null
    }

    /** Convenience: parse an entire payload body into events. */
    fun parseAll(body: String): List<Event> {
        val out = ArrayList<Event>()
        body.lineSequence().forEach { line -> feedLine(line)?.let { ev -> out.add(ev) } }
        // flush a trailing frame with no final blank line
        if (sawField) { out.add(Event(dataLines.toString(), eventName)); dataLines.setLength(0); eventName = null; sawField = false }
        return out
    }

    companion object {
        const val DONE_SENTINEL = "[DONE]"
    }
}
