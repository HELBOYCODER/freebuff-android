package com.freebuff.android.network

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Integration tests against a real HTTP socket (a fake Freebuff server), per Master Spec S24.
 *  No HTTP-layer mocking: OkHttp performs genuine requests against MockWebServer. */
class FreebuffClientTest {
    private val server = MockWebServer().apply { start() }
    private val apiKey = "cb-live-secret-DO-NOT-LEAK"
    private val client = FreebuffClient(
        baseUrl = server.url("/").toString().trimEnd('/'),
        apiKey = apiKey,
        instanceId = "inst-77",
        model = "glm-5.3-flash",
        walletSpendLimit = "100",
        client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS).build(),
    )

    @AfterTest fun tearDown() { server.shutdown() }

    @Test fun `session status GET sends bearer and freebuff headers`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"active"}"""))
        assertEquals("active", client.sessionStatus())

        val rec = server.takeRequest()
        assertEquals("GET", rec.method)
        assertEquals("/api/v1/freebuff/session", rec.path)
        assertEquals("Bearer $apiKey", rec.getHeader("Authorization"))
        assertEquals("inst-77", rec.getHeader(FreebuffProtocol.INSTANCE_HEADER))
        assertEquals("glm-5.3-flash", rec.getHeader(FreebuffProtocol.MODEL_HEADER))
        assertEquals("100", rec.getHeader(FreebuffProtocol.WALLET_SPEND_LIMIT_HEADER))
    }

    @Test fun `admission POST targets the dedicated admission route`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        client.admitSession(mapOf("reason" to "start"))
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals(FreebuffProtocol.SESSION_ADMISSION_PATH, rec.path)
        assertTrue(rec.body.readUtf8().contains("\"start\""))
    }

    @Test fun `401 surfaces typed error without leaking the secret`() {
        server.enqueue(MockResponse().setResponseCode(401)
            .setBody("""{"error":"unauthorized","message":"Missing or invalid Authorization header"}"""))
        val e = assertFailsWith<FreebuffRequestError> { client.sessionStatus() }
        assertEquals(401, e.statusCode)
        assertEquals("unauthorized", e.errorCode)
        assertEquals(ConnectionState.UNAUTHORIZED, RetryPolicy.stateFor(e.statusCode, e.errorCode))
        assertFalse(e.message!!.contains(apiKey), "exception message leaked the bearer token")
        assertFalse(client.toString().contains(apiKey), "toString leaked the bearer token")
    }

    @Test fun `429 carries Retry-After and is retryable on GET`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7")
            .setBody("""{"error":"rate_limited"}"""))
        val e = assertFailsWith<FreebuffRequestError> { client.whoami() }
        assertEquals(7_000L, e.retryAfterMs)
        assertEquals(Disposition.RETRY, RetryPolicy.classify("GET", e))
        assertEquals(ConnectionState.RATE_LIMITED, RetryPolicy.stateFor(e.statusCode, e.errorCode))
    }

    @Test fun `chat completions streams SSE deltas and stops at DONE`() {
        val chunk1 = "{\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}"
        val chunk2 = "{\"choices\":[{\"delta\":{\"content\":\" world\"}}]}"
        val leak = "{\"choices\":[{\"delta\":{\"content\":\"MUST-NOT-APPEAR\"}}]}"
        val sse = "data: $chunk1\n\ndata: $chunk2\n\ndata: [DONE]\n\ndata: $leak\n\n"
        server.enqueue(MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream").setBody(sse))

        val deltas = StringBuilder()
        client.streamChat(listOf(mapOf("role" to "user", "content" to "hi")), onDelta = { deltas.append(it) })
        assertEquals("Hello world", deltas.toString())

        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals(FreebuffProtocol.CHAT_COMPLETIONS_PATH, rec.path)
        assertEquals("text/event-stream", rec.getHeader("Accept"))
        val body = rec.body.readUtf8()
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"messages\""))
    }

    @Test fun `client tool calls dispatched by server are surfaced to the host`() {
        val payload = """{"choices":[{"message":{"tool_calls":[{"id":"c1","function":{"name":"run_terminal_command"}}]}}]}"""
        server.enqueue(MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: $payload\n\ndata: [DONE]\n\n"))
        val seen = mutableListOf<String>()
        client.streamChat(
            listOf(mapOf("role" to "user", "content" to "ls")),
            onDelta = {},
            onToolCall = { node -> seen.add(node.path("id").asText()) },
        )
        assertEquals(listOf("c1"), seen)
    }

    @Test fun `endpoint constants match upstream paths`() {
        assertEquals("/api/v1/freebuff/session/admission", FreebuffProtocol.SESSION_ADMISSION_PATH)
        assertEquals("/api/v1/freebuff/session/reuse", FreebuffProtocol.SESSION_REUSE_PATH)
        assertEquals("/api/v1/chat/completions", FreebuffProtocol.CHAT_COMPLETIONS_PATH)
        assertEquals("x-freebuff-instance-id", FreebuffProtocol.INSTANCE_HEADER)
        assertEquals("x-freebuff-wallet-spend-limit", FreebuffProtocol.WALLET_SPEND_LIMIT_HEADER)
    }

    @Test fun `urlFor resolves paths against base and passes through absolute`() {
        assertEquals("https://x.test/api/v1/me", FreebuffProtocol.urlFor("https://x.test/", "/api/v1/me"))
        assertEquals("https://y.test/z", FreebuffProtocol.urlFor("https://x.test", "https://y.test/z"))
    }
}
