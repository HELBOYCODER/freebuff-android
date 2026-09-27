package com.freebuff.android.security

import java.nio.file.Path

// Spec S16: path canonicalization + traversal protection. A tool-supplied path
// must resolve to a location inside the project root. Absolute paths and
// parent-dir escapes are rejected; the result is normalized and re-checked
// against the canonicalized root.
object PathSafety {
    fun resolveInProject(projectRoot: Path, requested: String): Result<Path> {
        if (requested.isBlank()) return Result.failure(IllegalArgumentException("empty path"))
        if (requested.indexOf(0.toChar()) >= 0) return Result.failure(IllegalArgumentException("NUL byte in path"))
        if (requested.startsWith("/") || requested.startsWith("\\\\")) {
            return Result.failure(SecurityException("absolute path not allowed: " + requested))
        }
        if (Path.of(requested).isAbsolute) {
            return Result.failure(SecurityException("absolute path not allowed: " + requested))
        }
        val root = projectRoot.toAbsolutePath().normalize()
        val resolved = root.resolve(requested).normalize()
        if (!resolved.startsWith(root)) {
            return Result.failure(SecurityException("path escapes project root: " + requested))
        }
        return Result.success(resolved)
    }

    fun isInside(projectRoot: Path, candidate: Path): Boolean {
        val root = projectRoot.toAbsolutePath().normalize()
        return candidate.toAbsolutePath().normalize().startsWith(root)
    }
}
