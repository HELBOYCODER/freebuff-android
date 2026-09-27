package com.freebuff.android.security

/** Spec §14/§16: provider secrets must never be logged or leaked into terminal
 *  subprocess environments, and sensitive env-file keys must never be exposed to
 *  the agent. Detection is key-name based (case-insensitive) so it stays
 *  conservative without a fixed secret list. */
object EnvRedaction {
    private val SENSITIVE_KEY = Regex(
        """(api[_-]?key|secret|token|passwd|password|credential|auth|private[_-]?key|access[_-]?key)""",
        RegexOption.IGNORE_CASE,
    )

    fun isSensitiveKey(key: String): Boolean = SENSITIVE_KEY.containsMatchIn(key)

    /** Full value replaced with a stable mask; never echo the raw secret. */
    fun redact(value: String): String = if (value.isEmpty()) "" else "***redacted***"

    /** Scrub a subprocess environment map: sensitive keys are dropped entirely
     *  (not merely masked) so BYOK keys cannot reach a terminal child process. */
    fun scrubSubprocessEnv(env: Map<String, String>): Map<String, String> =
        env.filterKeys { !isSensitiveKey(it) }

    /** Redact `KEY=VALUE` occurrences of sensitive keys inside free-text logs. */
    fun redactText(text: String): String {
        val lineRegex = Regex("""(?m)([A-Za-z0-9_.\-]*(?:api[_-]?key|secret|token|password|passwd|credential|private[_-]?key|access[_-]?key)[A-Za-z0-9_.\-]*)\s*=\s*(\S+)""", RegexOption.IGNORE_CASE)
        return lineRegex.replace(text) { m -> "${m.groupValues[1]}=${redact(m.groupValues[2])}" }
    }
}
