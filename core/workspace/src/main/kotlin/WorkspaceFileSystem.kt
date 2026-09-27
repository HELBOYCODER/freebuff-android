package com.freebuff.android.workspace

import com.freebuff.android.security.PathSafety
import java.nio.file.Files
import java.nio.file.Path

/** Storage-neutral workspace contract (Master Spec S10): the same UI and host-tool
 *  code must work over SAF trees and over a local runtime filesystem, so nothing
 *  in the agent path assumes desktop path semantics.
 *
 *  Every mutating call funnels through [PathSafety] so a tool-supplied path can
 *  never escape the project root (S16). */
interface WorkspaceFileSystem {
    val root: String
    fun list(relativeDir: String): List<TreeEntry>
    fun readText(relativePath: String): Result<String>
    fun writeText(relativePath: String, content: String): Result<Unit>
    fun delete(relativePath: String): Result<Unit>
    fun exists(relativePath: String): Boolean

    /** Resolve a request path safely, or fail with a SecurityException. */
    fun safeRelative(relativePath: String): Result<String>
}

abstract class SafeWorkspaceFileSystem(private val rootDisplay: String) : WorkspaceFileSystem {
    override val root: String = rootDisplay

    override fun safeRelative(relativePath: String): Result<String> {
        val normalized = relativePath.replace('\\', '/')
        // An absolute-looking request is rejected outright rather than silently
        // made relative: the caller must state a path inside the project (S16).
        if (normalized.startsWith("//") || normalized.startsWith("/")) {
            return Result.failure(SecurityException("absolute path not allowed: $relativePath"))
        }
        val clean = normalized.trimStart('/')
        // Validate against a synthetic NON-root base. Resolving against "/" would
        // clamp ".." at the filesystem root and make an escape look harmless; a
        // deeper base makes `../..` actually leave the base and be rejected.
        return PathSafety.resolveInProject(SYNTHETIC_ROOT, clean).mapCatching { resolved ->
            val rel = SYNTHETIC_ROOT.relativize(resolved).toString().replace('\\', '/')
            if (rel.isEmpty()) throw SecurityException("empty path resolved from: $relativePath")
            rel
        }
    }

    // Kotlin's Result.flatMap is not resolvable on this toolchain, so the safe-path
    // check is threaded through fold instead: failure of the guard short-circuits
    // before any filesystem call.
    companion object {
        /** Synthetic base used only to make `..` escapes detectable (see safeRelative). */
        private val SYNTHETIC_ROOT: Path = Path.of("/project-root")
    }

    final override fun readText(relativePath: String): Result<String> =
        safeRelative(relativePath).fold(
            onSuccess = { rel -> runCatching { readUnsafe(rel) } },
            onFailure = { err -> Result.failure(err) },
        )

    final override fun writeText(relativePath: String, content: String): Result<Unit> =
        safeRelative(relativePath).fold(
            onSuccess = { rel -> runCatching { writeUnsafe(rel, content) } },
            onFailure = { err -> Result.failure(err) },
        )

    final override fun delete(relativePath: String): Result<Unit> =
        safeRelative(relativePath).fold(
            onSuccess = { rel -> runCatching { deleteUnsafe(rel) } },
            onFailure = { err -> Result.failure(err) },
        )

    final override fun exists(relativePath: String): Boolean =
        safeRelative(relativePath).getOrNull()?.let { existsUnsafe(it) } == true

    final override fun list(relativeDir: String): List<TreeEntry> {
        val dir = if (relativeDir.isBlank()) null else safeRelative(relativeDir).getOrNull() ?: return emptyList()
        return listUnsafe(dir)
    }

    protected abstract fun readUnsafe(rel: String): String
    protected abstract fun writeUnsafe(rel: String, content: String)
    protected abstract fun deleteUnsafe(rel: String)
    protected abstract fun existsUnsafe(rel: String): Boolean
    protected abstract fun listUnsafe(rel: String?): List<TreeEntry>
}

/** JVM/local implementation used by unit tests and by a local runtime backend. */
class LocalWorkspaceFileSystem(rootPath: Path) : SafeWorkspaceFileSystem(rootPath.toString()) {
    private val base: Path = rootPath.toAbsolutePath().normalize()

    private fun resolve(rel: String): Path =
        PathSafety.resolveInProject(base, rel).getOrThrow()

    override fun readUnsafe(rel: String): String = Files.readString(resolve(rel))
    override fun writeUnsafe(rel: String, content: String) {
        val p = resolve(rel)
        p.parent?.let { Files.createDirectories(it) }
        Files.writeString(p, content)
    }

    override fun deleteUnsafe(rel: String) { Files.deleteIfExists(resolve(rel)) }
    override fun existsUnsafe(rel: String): Boolean = Files.exists(resolve(rel))

    override fun listUnsafe(rel: String?): List<TreeEntry> {
        val dir = if (rel == null) base else resolve(rel)
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.map { child ->
                val name = child.fileName.toString()
                TreeEntry(
                    path = base.relativize(child).toString().replace('\\', '/'),
                    name = name,
                    isDirectory = Files.isDirectory(child),
                    sizeBytes = if (Files.isRegularFile(child)) Files.size(child) else 0L,
                )
            }.toList().sortedBy { it.name.lowercase() }
        }
    }
}
