package com.freebuff.android.security

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertIs

class PathSafetyTest {
    private val root = Path.of("/home/user/proj")

    @Test fun `relative path inside project resolves`() {
        val r = PathSafety.resolveInProject(root, "src/app.ts")
        assertTrue(r.isSuccess)
        assertEquals(root.resolve("src/app.ts"), r.getOrNull())
    }

    @Test fun `parent dir escape is rejected`() {
        val r = PathSafety.resolveInProject(root, "../../etc/passwd")
        assertIs<SecurityException>(r.exceptionOrNull())
    }

    @Test fun `absolute path is rejected`() {
        assertIs<SecurityException>(PathSafety.resolveInProject(root, "/etc/passwd").exceptionOrNull())
    }

    @Test fun `empty path is rejected`() {
        assertFalse(PathSafety.resolveInProject(root, "").isSuccess)
    }

    @Test fun `dot inside root stays inside`() {
        assertTrue(PathSafety.resolveInProject(root, "src/../src/main.kt").isSuccess)
    }
}

class EnvRedactionTest {
    @Test fun `sensitive keys detected`() {
        assertTrue(EnvRedaction.isSensitiveKey("OPENAI_API_KEY"))
        assertTrue(EnvRedaction.isSensitiveKey("AWS_SECRET_ACCESS_KEY"))
        assertTrue(EnvRedaction.isSensitiveKey("GITHUB_TOKEN"))
        assertFalse(EnvRedaction.isSensitiveKey("PATH"))
        assertFalse(EnvRedaction.isSensitiveKey("HOME"))
    }

    @Test fun `subprocess env scrubs secrets entirely`() {
        val out = EnvRedaction.scrubSubprocessEnv(mapOf("HOME" to "/h", "OPENAI_API_KEY" to "sk-live", "PORT" to "8080"))
        assertFalse(out.containsKey("OPENAI_API_KEY"))
        assertTrue(out.containsKey("HOME"))
        assertEquals("8080", out["PORT"])
    }

    @Test fun `log text redacts secret values`() {
        val red = EnvRedaction.redactText("connecting with API_KEY=sk-secret-123 to host")
        assertFalse(red.contains("sk-secret-123"))
        assertTrue(red.contains("***redacted***"))
    }
}
