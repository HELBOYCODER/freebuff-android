package com.freebuff.android.network

// Mirrors upstream constants at commit 25f1d61:
//   cli/src/utils/freebuff-session-api.ts (sessionBaseUrl, endpoint selection)
//   common/src/constants/freebuff-models.ts (FREEBUFF_*_PATH / *_HEADER)
// Keep these as named constants so a tool/protocol change upstream fails loudly here
// rather than silently drifting (Master Spec S26).
object FreebuffProtocol {
    const val DEFAULT_BASE_URL = "https://codebuff.com"

    const val SESSION_PATH = "/api/v1/freebuff/session"
    const val SESSION_ADMISSION_PATH = "/api/v1/freebuff/session/admission"
    const val SESSION_REUSE_PATH = "/api/v1/freebuff/session/reuse"
    const val CHAT_COMPLETIONS_PATH = "/api/v1/chat/completions"
    const val ME_PATH = "/api/v1/me"

    const val INSTANCE_HEADER = "x-freebuff-instance-id"
    const val REUSE_INSTANCE_HEADER = "x-freebuff-reuse-instance-id"
    const val MODEL_HEADER = "x-freebuff-model"
    const val WALLET_SPEND_LIMIT_HEADER = "x-freebuff-wallet-spend-limit"
    const val TAKEOVER_INSTANCE_HEADER = "x-freebuff-takeover-instance-id"
    const val HEARTBEAT_HEADER = "x-freebuff-heartbeat"
    const val COMPACT_SESSION_HEADER = "x-freebuff-compact-session"

    /** Upstream default per-request budget for the session API (20s). */
    const val SESSION_TIMEOUT_MS = 20_000L

    /**
     * A POST admission is NOT idempotent: upstream documents that a request
     * without a response may already have rotated the active session instance,
     * so blind retry could repeat a takeover.
     */
    const val ADMIT_IS_IDEMPOTENT = false

    /** Resolve a path against a base URL (defaults to the production host). */
    fun urlFor(baseUrl: String, path: String): String {
        if (path.startsWith("http")) return path
        val base = baseUrl.ifBlank { DEFAULT_BASE_URL }.trimEnd('/')
        return base + (if (path.startsWith("/")) path else "/$path")
    }
}

/** Connection states surfaced to the UI instead of a generic "something went wrong"
 *  (Master Spec S15). */
enum class ConnectionState {
    OK, UNAUTHORIZED, EXPIRED, BLOCKED, RATE_LIMITED, OFFLINE, SERVER_ERROR, UNSUPPORTED_CAPABILITY
}
