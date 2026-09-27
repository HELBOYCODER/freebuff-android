package com.freebuff.android.model

/** Mirror of upstream `FileChangeSchema` (common/src/actions.ts @25f1d61):
 *  `{ type: 'patch' | 'file', path: string, content: string }`. */
enum class FileChangeType { PATCH, FILE }

data class FileChange(
    val type: FileChangeType,
    val path: String,
    val content: String,
) {
    companion object {
        fun parse(type: String?, path: String?, content: String?): FileChange? {
            val t = when (type) {
                "patch" -> FileChangeType.PATCH
                "file" -> FileChangeType.FILE
                else -> return null
            }
            if (path.isNullOrBlank() || content == null) return null
            return FileChange(t, path, content)
        }
    }
}
