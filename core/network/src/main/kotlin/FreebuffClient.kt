package com.freebuff.android.network

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

/** Real Freebuff/Codebuff backend client — speaks the same wire contract as upstream's
 *  CLI (`cli/src/utils/freebuff-session-api.ts`, `sdk` chat/completions) at commit 25f1d61.
 *
 *  Security (Master Spec S14/S16): the bearer token is never placed in an exception
 *  message, log line, or `toString()`. */
class FreebuffClient(
    private val baseUrl: String = FreebuffProtocol.DEFAULT_BASE_URL,
    private val apiKey: String? = null,
    private val instanceId: String? = null,
    private val model: String? = null,
    private val walletSpendLimit: String? = null,
    private val client: OkHttpClient = defaultClient(),
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    override fun toString(): String =
        "FreebuffClient(baseUrl=$baseUrl, apiKey=${if (apiKey == null) "none" else "REDACTED"})"

    fun hasCredentials(): Boolean = !apiKey.isNullOrBlank()

    private fun newCall(path: String): Request.Builder {
        val b = Request.Builder()
            .url(FreebuffProtocol.urlFor(baseUrl, path))
            .header("Accept", "application/json")
        apiKey?.let { b.header("Authorization", "Bearer $it") }
        instanceId?.let { b.header(FreebuffProtocol.INSTANCE_HEADER, it) }
        model?.let { b.header(FreebuffProtocol.MODEL_HEADER, it) }
        walletSpendLimit?.let { b.header(FreebuffProtocol.WALLET_SPEND_LIMIT_HEADER, it) }
        return b
    }

    /** GET /api/v1/freebuff/session -> the backend's own view of the session. */
    fun sessionStatus(): String = getJson(FreebuffProtocol.SESSION_PATH).path("status").asText("unknown")

    /** GET /api/v1/me -> account identity, used as the live connection probe. */
    fun whoami(): JsonNode = getJson(FreebuffProtocol.ME_PATH)

    fun admitSession(body: Map<String, Any?> = emptyMap()): JsonNode {
        val json = mapper.writeValueAsString(body)
        return sendJson("POST", FreebuffProtocol.SESSION_ADMISSION_PATH, json)
    }

    fun releaseSession(): Int =
        execute("DELETE", FreebuffProtocol.SESSION_PATH, null).use { it.code }

    private fun getJson(path: String): JsonNode =
        execute("GET", path, null).use { parse(it) }

    private fun sendJson(method: String, path: String, body: String): JsonNode =
        execute(method, path, body).use { parse(it) }

    private fun execute(method: String, path: String, body: String?): Response {
        val req = newCall(path).method(
            method,
            body?.toRequestBody(JSON),
        ).build()
        val resp = try {
            client.newCall(req).execute()
        } catch (e: java.io.IOException) {
            // Network-layer failure (nothing came back) — message must not carry secrets.
            throw java.io.IOException("network_error on $method $path", e)
        }
        if (!resp.isSuccessful) throw toError(resp)
        return resp
    }

    private fun toError(resp: Response): FreebuffRequestError {
        val code = resp.code
        val retryAfter = RetryPolicy.parseRetryAfterMs(resp.header("Retry-After"))
        val errorCode = runCatching {
            resp.peekBody(4096).string().let { mapper.readTree(it).path("error").asText(null) }
        }.getOrNull()
        // Deliberately: no body, no headers, no Authorization in this message.
        return FreebuffRequestError(
            "http $code on ${resp.request.url.encodedPath}",
            code,
            retryAfter,
            errorCode,
        )
    }

    private fun parse(resp: Response): JsonNode {
        val text = resp.body?.string().orEmpty()
        if (text.isBlank()) return mapper.createObjectNode()
        return mapper.readTree(text)
    }

    /**
     * Stream an agent turn from POST /api/v1/chat/completions (SSE).
     * `onDelta` receives assistant text tokens; `onToolCall` receives each
     * client tool call the server dispatches to this host.
     */
    fun streamChat(
        messages: List<Map<String, Any?>>,
        onDelta: (String) -> Unit,
        onToolCall: (JsonNode) -> Unit = {},
        extra: ObjectNode.() -> Unit = {},
    ) {
        val payload = mapper.createObjectNode()
        payload.put("model", model ?: "freebuff")
        payload.put("stream", true)
        payload.set<JsonNode>("messages", mapper.valueToTree(messages))
        payload.extra()

        val req = newCall(FreebuffProtocol.CHAT_COMPLETIONS_PATH)
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw toError(resp)
            val reader: BufferedReader = resp.body?.charStream()?.buffered()
                ?: throw java.io.IOException("empty stream body")
            val sse = SseParser()
            var line = reader.readLine()
            while (line != null) {
                val ev = sse.feedLine(line)
                if (ev != null) {
                    if (ev.data == SseParser.DONE_SENTINEL) break
                    handleChunk(ev.data, onDelta, onToolCall)
                }
                line = reader.readLine()
            }
        }
    }

    private fun handleChunk(data: String, onDelta: (String) -> Unit, onToolCall: (JsonNode) -> Unit) {
        val node = runCatching { mapper.readTree(data) }.getOrNull() ?: return
        node.path("choices").get(0)?.let { choice ->
            choice.path("delta").path("content").asText("")?.takeIf { it.isNotEmpty() }?.let(onDelta)
            choice.path("message").path("tool_calls").forEach { onToolCall(it) }
            choice.path("delta").path("tool_calls").forEach { onToolCall(it) }
        }
        // Freebuff dispatches host tools as explicit client tool calls too.
        node.path("clientToolCall").takeIf { it.isObject }?.let { onToolCall(it) }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(FreebuffProtocol.SESSION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .build()
    }
}
