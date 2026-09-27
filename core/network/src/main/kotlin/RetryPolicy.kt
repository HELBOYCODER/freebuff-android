package com.freebuff.android.network

import kotlin.math.ceil
import kotlin.math.max

/** Raised for a response we actually received and did not like.
 *  Mirrors upstream `FreebuffSessionRequestError` (statusCode, retryAfterMs, errorCode). */
class FreebuffRequestError(
    message: String,
    val statusCode: Int,
    val retryAfterMs: Long? = null,
    val errorCode: String? = null,
) : Exception(message)

enum class Disposition { RETRY, STOP, UNKNOWN }

/** Faithful port of upstream `classifyFreebuffSessionRequestFailure` and
 *  `parseRetryAfterMs` (cli/src/utils/freebuff-session-api.ts @25f1d61).
 *
 *  The asymmetry matters: a POST admission may already have rotated the active
 *  instance even when no response came back, so only the codes the edge emits
 *  *before* the mutation can commit (408/429/503) are safe to repeat. */
object RetryPolicy {

    fun classify(method: String, error: Throwable): Disposition {
        val isPost = method.equals("POST", ignoreCase = true)
        if (isPost) {
            if (error !is FreebuffRequestError) return Disposition.UNKNOWN
            if (error.statusCode == 408 || error.statusCode == 429 || error.statusCode == 503) {
                return Disposition.RETRY
            }
            return if (error.statusCode in 400..499) Disposition.STOP else Disposition.UNKNOWN
        }
        // GET (and other read-only methods)
        if (error !is FreebuffRequestError) return Disposition.RETRY
        return if (error.statusCode == 408 || error.statusCode == 429 || error.statusCode >= 500) {
            Disposition.RETRY
        } else {
            Disposition.STOP
        }
    }

    /** Accepts either delta-seconds or an HTTP-date, as upstream does. */
    fun parseRetryAfterMs(value: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        if (value.isNullOrBlank()) return null
        value.toLongOrNull()?.let { seconds ->
            if (seconds >= 0) {
                val ms = seconds * 1000.0
                return if (ms.isFinite()) ceil(ms).toLong() else null
            }
        }
        value.toDoubleOrNull()?.let { seconds ->
            if (seconds >= 0) {
                val ms = seconds * 1000.0
                return if (ms.isFinite()) ceil(ms).toLong() else null
            }
        }
        val dateMs = parseHttpDate(value) ?: return null
        return max(0L, dateMs - nowMs)
    }

    /** Minimal RFC-1123 / RFC-850 / asctime date support (HTTP-date forms). */
    internal fun parseHttpDate(value: String): Long? = try {
        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            .parse(value) { Instant.from(it).toEpochMilli() }
    } catch (_: Exception) {
        null
    }

    /** Map an HTTP status to the explicit UI state required by Master Spec S15. */
    fun stateFor(statusCode: Int, errorCode: String? = null): ConnectionState = when {
        statusCode == 200 || statusCode == 204 -> ConnectionState.OK
        statusCode == 401 -> if (errorCode == "expired") ConnectionState.EXPIRED else ConnectionState.UNAUTHORIZED
        statusCode == 403 -> ConnectionState.BLOCKED
        statusCode == 404 && errorCode == "unsupported" -> ConnectionState.UNSUPPORTED_CAPABILITY
        statusCode == 429 -> ConnectionState.RATE_LIMITED
        statusCode in 500..599 -> ConnectionState.SERVER_ERROR
        else -> ConnectionState.SERVER_ERROR
    }
}

private typealias Instant = java.time.Instant
