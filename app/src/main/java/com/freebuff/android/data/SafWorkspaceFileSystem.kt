package com.freebuff.android.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.freebuff.android.workspace.SafeWorkspaceFileSystem
import com.freebuff.android.workspace.TreeEntry

/** SAF-backed workspace (Master Spec S10): scoped-storage access to a user-picked
 *  project tree, behind the same [WorkspaceFileSystem] contract the local runtime
 *  uses — so no agent code assumes desktop path semantics.
 *
 *  Read/write/delete go through ContentResolver on the picked tree Uri; the base
 *  class already funnels every path through PathSafety before this adapter sees it. */
class SafWorkspaceFileSystem(
    private val context: Context,
    private val treeUri: Uri,
) : SafeWorkspaceFileSystem(treeUri.toString()) {

    private val treeRoot: DocumentFile = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IllegalArgumentException("not a tree uri: $treeUri")

    private val resolver get() = context.contentResolver

    private fun documentFor(rel: String): DocumentFile? {
        val segments = rel.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        var current: DocumentFile = treeRoot
        for (i in segments.dropLast(1)) {
            current = current.findFile(i) ?: return null
        }
        return current.findFile(segments.last())
    }

    private fun parentDir(rel: String): DocumentFile {
        val segments = rel.split('/').filter { it.isNotEmpty() }
        var current: DocumentFile = treeRoot
        for (seg in segments.dropLast(1)) {
            val next = current.findFile(seg)
                ?: current.createDirectory(seg)
                ?: throw IOExceptionSafe("cannot create directory $seg")
            current = next
        }
        return current
    }

    override fun existsUnsafe(rel: String): Boolean = documentFor(rel) != null

    override fun readUnsafe(rel: String): String {
        val doc = documentFor(rel) ?: throw IOExceptionSafe("file not found: $rel")
        if (doc.isDirectory) throw IOExceptionSafe("path is a directory: $rel")
        return resolver.openInputStream(doc.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOExceptionSafe("cannot open: $rel")
    }

    override fun writeUnsafe(rel: String, content: String) {
        val existing = documentFor(rel)
        val target = existing ?: run {
            parentDir(rel).createFile(mimeFor(rel), rel.substringAfterLast('/'))
                ?: throw IOExceptionSafe("cannot create file: $rel")
        }
        resolver.openOutputStream(target.uri, "wt")?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IOExceptionSafe("cannot open for write: $rel")
    }

    override fun deleteUnsafe(rel: String) {
        val doc = documentFor(rel) ?: return
        if (!doc.delete()) throw IOExceptionSafe("delete refused by provider: $rel")
    }

    override fun listUnsafe(rel: String?): List<TreeEntry> {
        val dir = if (rel == null) treeRoot else documentFor(rel) ?: return emptyList()
        return dir.listFiles().mapNotNull { child ->
            val name = child.name ?: return@mapNotNull null
            val path = if (rel == null) name else "$rel/$name"
            TreeEntry(
                path = path,
                name = name,
                isDirectory = child.isDirectory,
                // SAF reports a length column; 0 is honest "unknown", never a guess.
                sizeBytes = runCatching { child.length() }.getOrDefault(0L),
            )
        }.sortedBy { it.name.lowercase() }
    }

    private fun mimeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> "text/x-kotlin"
        "ts", "tsx" -> "application/typescript"
        "js", "jsx", "mjs" -> "text/javascript"
        "json" -> "application/json"
        "md", "txt" -> "text/plain"
        "py" -> "text/x-python"
        "sh" -> "application/x-sh"
        else -> "application/octet-stream"
    }

    companion object {
        /** Take a durable permission for a picked tree Uri (survives reboot/process death). */
        fun takePersistablePermission(context: Context, uri: Uri) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
        }
    }
}

/** Typed IO failure so the UI can distinguish "provider refused" from "unsafe path". */
class IOExceptionSafe(message: String) : java.io.IOException(message)
