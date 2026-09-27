package com.freebuff.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.freebuff.android.agent.AgentViewModel
import com.freebuff.android.agent.AgentUi
import com.freebuff.android.agent.TurnUi
import com.freebuff.android.data.SecretStore
import com.freebuff.android.model.ClientToolName

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val secrets = SecretStore(applicationContext)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: AgentViewModel = viewModel(
                        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                                AgentViewModel(secrets) as T
                        },
                    )
                    ConnectionAndChat(vm, secrets)
                }
            }
        }
    }
}

@Composable
private fun ConnectionAndChat(vm: AgentViewModel, secrets: SecretStore) {
    var baseUrl by remember { mutableStateOf(secrets.readBaseUrl()) }
    var model by remember { mutableStateOf(secrets.readModel()) }
    var key by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }

    val agent by vm.agent.collectAsState()
    val turn by vm.turn.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Freebuff Android", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Host tools implemented: ${ClientToolName.entries.size} (clientToolCallSchema)",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = baseUrl, onValueChange = { baseUrl = it },
            label = { Text("Server base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        OutlinedTextField(
            value = model, onValueChange = { model = it },
            label = { Text("Model") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        OutlinedTextField(
            value = key, onValueChange = { key = it },
            label = { Text("API key (stored in Android Keystore)") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                vm.saveConnection(baseUrl, model, key)
                key = ""
                vm.connect()
            }) { Text("Connect") }
            TextButton(onClick = { secrets.clearApiKey() }) { Text("Forget key") }
        }
        Text(secrets.redactedKeySummary(), style = MaterialTheme.typography.bodySmall)

        when (val a = agent) {
            AgentUi.Idle -> Text("Not connected.", style = MaterialTheme.typography.bodyMedium)
            AgentUi.Connecting -> Text("Connecting to ${baseUrl}…")
            is AgentUi.Ready -> Text("✓ ${a.account} · session: ${a.session}", color = MaterialTheme.colorScheme.primary)
            is AgentUi.Failed -> Text("✗ ${a.state}: ${a.detail}", color = MaterialTheme.colorScheme.error)
        }

        OutlinedTextField(
            value = prompt, onValueChange = { prompt = it },
            label = { Text("Ask the agent") }, modifier = Modifier.fillMaxWidth(), minLines = 2,
        )
        Button(onClick = { vm.sendPrompt(prompt); prompt = "" }) { Text("Send") }

        when (val t = turn) {
            TurnUi.Idle -> Unit
            TurnUi.Streaming -> Text("streaming…")
            is TurnUi.Token -> Text(t.text)
            is TurnUi.ToolCall -> Text("tool call → ${t.name} (${t.id})")
            is TurnUi.Done -> Text(t.text, style = MaterialTheme.typography.bodyMedium)
            is TurnUi.Failed -> Text("✗ ${t.state}: ${t.detail}", color = MaterialTheme.colorScheme.error)
        }
    }
}
