package com.freebuff.android.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freebuff.android.data.SecretStore
import com.freebuff.android.network.ConnectionState
import com.freebuff.android.network.FreebuffClient
import com.freebuff.android.network.FreebuffProtocol
import com.freebuff.android.network.FreebuffRequestError
import com.freebuff.android.network.RetryPolicy
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface AgentUi {
    data object Idle : AgentUi
    data object Connecting : AgentUi
    data class Ready(val account: String, val session: String) : AgentUi
    data class Failed(val state: ConnectionState, val detail: String) : AgentUi
}

sealed interface TurnUi {
    data object Idle : TurnUi
    data object Streaming : TurnUi
    data class Token(val text: String) : TurnUi
    data class ToolCall(val name: String, val id: String) : TurnUi
    data class Done(val text: String) : TurnUi
    data class Failed(val state: ConnectionState, val detail: String) : TurnUi
}

class AgentViewModel(private val secrets: SecretStore) : ViewModel() {
    private val _agent = MutableStateFlow<AgentUi>(AgentUi.Idle)
    val agent: StateFlow<AgentUi> = _agent

    private val _turn = MutableStateFlow<TurnUi>(TurnUi.Idle)
    val turn: StateFlow<TurnUi> = _turn

    private val transcript = StringBuilder()

    fun client(): FreebuffClient? {
        val key = secrets.readApiKey() ?: return null
        return FreebuffClient(
            baseUrl = secrets.readBaseUrl(),
            apiKey = key,
            model = secrets.readModel(),
        )
    }

    /** Live probe against the real backend: GET /api/v1/me + session status. */
    fun connect() {
        val client = client()
        if (client == null) {
            _agent.value = AgentUi.Failed(ConnectionState.UNAUTHORIZED, "No API key configured")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _agent.value = AgentUi.Connecting
            try {
                val me = client.whoami()
                val account = me.path("email").asText(me.path("id").asText("authenticated"))
                val session = client.sessionStatus()
                _agent.value = AgentUi.Ready(account, session)
            } catch (e: FreebuffRequestError) {
                _agent.value = AgentUi.Failed(
                    RetryPolicy.stateFor(e.statusCode, e.errorCode),
                    "HTTP " + e.statusCode,
                )
            } catch (e: IOException) {
                _agent.value = AgentUi.Failed(ConnectionState.OFFLINE, "network unreachable")
            }
        }
    }

    fun saveConnection(baseUrl: String, model: String, apiKey: String) {
        secrets.saveBaseUrl(baseUrl.ifBlank { FreebuffProtocol.DEFAULT_BASE_URL })
        secrets.saveModel(model.ifBlank { SecretStore.DEFAULT_MODEL })
        if (apiKey.isNotBlank()) secrets.saveApiKey(apiKey)
    }

    /** Streams a real agent turn from POST /api/v1/chat/completions (SSE). */
    fun sendPrompt(prompt: String) {
        val client = client()
        if (client == null) {
            _turn.value = TurnUi.Failed(ConnectionState.UNAUTHORIZED, "No API key configured")
            return
        }
        if (prompt.isBlank()) return
        transcript.setLength(0)
        transcript.append(prompt).append("\n\n")
        viewModelScope.launch(Dispatchers.IO) {
            _turn.value = TurnUi.Streaming
            try {
                client.streamChat(
                    messages = listOf(mapOf("role" to "user", "content" to prompt)),
                    onDelta = { piece ->
                        transcript.append(piece)
                        _turn.value = TurnUi.Token(piece)
                    },
                    onToolCall = { node ->
                        val fn = node.path("function").path("name").asText("tool")
                        _turn.value = TurnUi.ToolCall(fn, node.path("id").asText(""))
                    },
                )
                _turn.value = TurnUi.Done(transcript.toString())
            } catch (e: FreebuffRequestError) {
                _turn.value = TurnUi.Failed(
                    RetryPolicy.stateFor(e.statusCode, e.errorCode),
                    "HTTP " + e.statusCode,
                )
            } catch (e: IOException) {
                _turn.value = TurnUi.Failed(ConnectionState.OFFLINE, "network unreachable")
            }
        }
    }
}
