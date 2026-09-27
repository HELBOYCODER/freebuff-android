package com.freebuff.android.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RetryPolicyTest {
    private fun err(code: Int) = FreebuffRequestError("http $code", code)

    @Test fun `POST retries only codes emitted before the mutation can commit`() {
        assertEquals(Disposition.RETRY, RetryPolicy.classify("POST", err(408)))
        assertEquals(Disposition.RETRY, RetryPolicy.classify("POST", err(429)))
        assertEquals(Disposition.RETRY, RetryPolicy.classify("POST", err(503)))
        assertEquals(Disposition.STOP, RetryPolicy.classify("POST", err(400)))
        assertEquals(Disposition.STOP, RetryPolicy.classify("POST", err(401)))
        assertEquals(Disposition.STOP, RetryPolicy.classify("POST", err(403)))
        // 500 is NOT safe to repeat: the instance may already have rotated.
        assertEquals(Disposition.UNKNOWN, RetryPolicy.classify("POST", err(500)))
    }

    @Test fun `POST with no response at all is unknown, not retried`() {
        assertEquals(Disposition.UNKNOWN, RetryPolicy.classify("POST", java.io.IOException("reset")))
    }

    @Test fun `GET retries transient failures and stops on client errors`() {
        assertEquals(Disposition.RETRY, RetryPolicy.classify("GET", err(408)))
        assertEquals(Disposition.RETRY, RetryPolicy.classify("GET", err(429)))
        assertEquals(Disposition.RETRY, RetryPolicy.classify("GET", err(500)))
        assertEquals(Disposition.STOP, RetryPolicy.classify("GET", err(401)))
        assertEquals(Disposition.RETRY, RetryPolicy.classify("GET", java.io.IOException("net")))
    }

    @Test fun `retry-after accepts delta-seconds and fractional`() {
        assertEquals(120_000L, RetryPolicy.parseRetryAfterMs("120"))
        assertEquals(1_500L, RetryPolicy.parseRetryAfterMs("1.5"))
        assertEquals(0L, RetryPolicy.parseRetryAfterMs("0"))
        assertNull(RetryPolicy.parseRetryAfterMs(null))
        assertNull(RetryPolicy.parseRetryAfterMs(""))
    }

    @Test fun `retry-after accepts an HTTP date`() {
        val future = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(30)
            .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        val ms = RetryPolicy.parseRetryAfterMs(future)!!
        assertFalse(ms < 0)
    }

    @Test fun `status maps to explicit UI states (S15)`() {
        assertEquals(ConnectionState.OK, RetryPolicy.stateFor(200))
        assertEquals(ConnectionState.UNAUTHORIZED, RetryPolicy.stateFor(401))
        assertEquals(ConnectionState.EXPIRED, RetryPolicy.stateFor(401, "expired"))
        assertEquals(ConnectionState.BLOCKED, RetryPolicy.stateFor(403))
        assertEquals(ConnectionState.RATE_LIMITED, RetryPolicy.stateFor(429))
        assertEquals(ConnectionState.SERVER_ERROR, RetryPolicy.stateFor(503))
        assertEquals(ConnectionState.UNSUPPORTED_CAPABILITY, RetryPolicy.stateFor(404, "unsupported"))
    }
}

class SseParserTest {
    @Test fun `single data frame terminates on blank line`() {
        val events = SseParser().parseAll("data: hello\n\n")
        assertEquals(1, events.size)
        assertEquals("hello", events[0].data)
    }

    @Test fun `multi-line data joined with newline`() {
        val events = SseParser().parseAll("data: a\ndata: b\n\n")
        assertEquals("a\nb", events[0].data)
    }

    @Test fun `comments and keep-alives ignored`() {
        val events = SseParser().parseAll(": keep-alive\ndata: x\n\n")
        assertEquals(1, events.size)
        assertEquals("x", events[0].data)
    }

    @Test fun `event name preserved and optional space stripped`() {
        val events = SseParser().parseAll("event: delta\ndata:y\n\n")
        assertEquals("delta", events[0].event)
        assertEquals("y", events[0].data)
    }

    @Test fun `trailing frame without blank line still flushes`() {
        val events = SseParser().parseAll("data: last")
        assertEquals(1, events.size)
        assertEquals("last", events[0].data)
    }
}
