package com.freebuff.android.workspace.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freebuff.android.data.IOExceptionSafe
import com.freebuff.android.data.SafWorkspaceFileSystem
import com.freebuff.android.workspace.EditSession
import com.freebuff.android.workspace.IgnoreRules
import com.freebuff.android.workspace.TreeEntry
import com.freebuff.android.workspace.WorkspaceFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Drives Open Project -> File tree -> Editor -> Diff review (Master Spec S31.6). */
class WorkspaceViewModel : ViewModel() {

    sealed interface Status {
        data object NoProject : Status
        data object Loading : Status
        data class Ready(val root: String) : Status
        data class Error(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.NoProject)
    val status: StateFlow<Status> = _status

    private val _children = MutableStateFlow<Map<String, List<TreeEntry>>>(emptyMap())
    val children: StateFlow<Map<String, List<TreeEntry>>> = _children

    private val _openFile = MutableStateFlow<EditSession?>(null)
    val openFile: StateFlow<EditSession?> = _openFile

    private val _diff = MutableStateFlow<String?>(null)
    val diff: StateFlow<String?> = _diff

    private val _expanded = MutableStateFlow<Set<String>>(emptySet())
    val expanded: StateFlow<Set<String>> = _expanded

    private var fs: WorkspaceFileSystem? = null

    fun openProject(context: Context, treeUri: Uri) {
        _status.value = Status.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                SafWorkspaceFileSystem.takePersistablePermission(context, treeUri)
                val impl = SafWorkspaceFileSystem(context.applicationContext, treeUri)
                fs = impl
                _children.value = mapOf("" to impl.list(""))
                _expanded.value = setOf("")
                _status.value = Status.Ready(treeUri.toString())
            } catch (e: Exception) {
                _status.value = Status.Error(safeMessage(e))
            }
        }
    }

    fun toggleDir(path: String) {
        val store = fs ?: return
        val current = _expanded.value
        if (current.contains(path)) {
            _expanded.value = current - path
            return
        }
        _expanded.value = current + path
        if (!_children.value.containsKey(path)) {
            viewModelScope.launch(Dispatchers.IO) {
                val kids = runCatching { store.list(path) }.getOrDefault(emptyList())
                _children.value = _children.value + (path to kids)
            }
        }
    }

    fun openFile(path: String) {
        val store = fs ?: return
        if (IgnoreRules.isSensitive(path)) {
            _status.value = Status.Error("refusing to open sensitive file without approval: $path")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            store.readText(path).fold(
                onSuccess = { content ->
                    _openFile.value = EditSession(path, content)
                    _diff.value = null
                },
                onFailure = { _status.value = Status.Error(safeMessage(it)) },
            )
        }
    }

    fun edit(newContent: String) {
        val current = _openFile.value ?: return
        // Publish a new instance: mutating the held object would not emit through
        // StateFlow, so the editor would silently stop updating.
        val next = EditSession(current.path, current.loadedContent)
        next.edit(newContent)
        _openFile.value = next
    }

    fun save() {
        val store = fs ?: return
        val session = _openFile.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            store.writeText(session.path, session.content).fold(
                onSuccess = { _diff.value = session.diff() },
                onFailure = { _status.value = Status.Error(safeMessage(it)) },
            )
        }
    }

    fun revert() {
        _openFile.value?.revert()
        _diff.value = null
    }

    /** Called by the agent path after a host tool wrote a file, so the editor and
     *  the review surface show the real on-disk result rather than an assumption. */
    fun reloadAfterAgentEdit(path: String) {
        val store = fs ?: return
        viewModelScope.launch(Dispatchers.IO) {
            store.readText(path).onSuccess { content ->
                if (_openFile.value?.path == path) {
                    _openFile.value = EditSession(path, content)
                }
                _diff.value = (_diff.value ?: "") + "\n[agent wrote $path]"
            }
        }
    }

    private fun safeMessage(t: Throwable): String = when (t) {
        is IOExceptionSafe -> t.message ?: "storage io error"
        is SecurityException -> "blocked by path safety: ${t.message}"
        else -> t.javaClass.simpleName
    }
}
