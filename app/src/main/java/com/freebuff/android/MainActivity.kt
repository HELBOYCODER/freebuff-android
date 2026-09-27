package com.freebuff.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.freebuff.android.agent.AgentViewModel
import com.freebuff.android.agent.AgentUi
import com.freebuff.android.agent.TurnUi
import com.freebuff.android.data.SecretStore
import com.freebuff.android.model.ClientToolName
import com.freebuff.android.workspace.ui.WorkspaceViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val secrets = SecretStore(applicationContext)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                            when {
                                AgentViewModel::class.java.isAssignableFrom(modelClass) ->
                                    AgentViewModel(secrets) as T
                                else -> WorkspaceViewModel() as T
                            }
                    }
                    val agentVm: AgentViewModel = viewModel(factory = factory)
                    val workspaceVm: WorkspaceViewModel = viewModel(factory = factory)

                    var tab by remember { mutableStateOf(0) }
                    Column(Modifier.fillMaxSize()) {
                        TabRow(selectedTabIndex = tab) {
                            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Workspace") })
                            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Agent") })
                        }
                        when (tab) {
                            0 -> WorkspacePane(workspaceVm)
                            else -> AgentPane(agentVm, secrets)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspacePane(vm: WorkspaceViewModel) {
    val status by vm.status.collectAsState()
    val children by vm.children.collectAsState()
    val expanded by vm.expanded.collectAsState()
    val file by vm.openFile.collectAsState()
    val diff by vm.diff.collectAsState()

    val appContext = androidx.compose.ui.platform.LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.openProject(appContext, uri)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(onClick = { picker.launch(null) }) { Text("Open project folder") }
        when (val s = status) {
            WorkspaceViewModel.Status.NoProject -> Text("No project open. Pick a folder (SAF scoped access).")
            WorkspaceViewModel.Status.Loading -> Text("Opening…")
            is WorkspaceViewModel.Status.Ready -> Text("Root: ${s.root}", style = MaterialTheme.typography.bodySmall)
            is WorkspaceViewModel.Status.Error ->
                Text("✗ ${s.message}", color = MaterialTheme.colorScheme.error)
        }

        if (children.isNotEmpty()) {
            Text("Files", style = MaterialTheme.typography.titleSmall)
            renderLevel("", 0, children, expanded, vm)
        }

        file?.let { f ->
            Text("Editor: ${f.path}${if (f.isDirty()) "  ●" else ""}", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = f.content,
                onValueChange = { vm.edit(it) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 8,
                visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.save() }) { Text("Save") }
                OutlinedButton(onClick = { vm.revert() }) { Text("Revert") }
            }
        }

        diff?.let {
            Text("Diff review", style = MaterialTheme.typography.titleSmall)
            Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun renderLevel(
    dir: String,
    depth: Int,
    children: Map<String, List<com.freebuff.android.workspace.TreeEntry>>,
    expanded: Set<String>,
    vm: WorkspaceViewModel,
) {
    children[dir]?.forEach { entry ->
        val indent = Modifier.padding(start = (depth * 14).dp)
        if (entry.isDirectory) {
            Row(indent.clickable { vm.toggleDir(entry.path) }.fillMaxWidth()) {
                Text(if (expanded.contains(entry.path)) "▾ ${entry.name}/" else "▸ ${entry.name}/")
            }
            if (expanded.contains(entry.path)) renderLevel(entry.path, depth + 1, children, expanded, vm)
        } else {
            Row(indent.clickable { vm.openFile(entry.path) }.fillMaxWidth()) {
                Text("  ${entry.name}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun AgentPane(vm: AgentViewModel, secrets: SecretStore) {
    var baseUrl by remember { mutableStateOf(secrets.readBaseUrl()) }
    var model by remember { mutableStateOf(secrets.readModel()) }
    var key by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    val agent by vm.agent.collectAsState()
    val turn by vm.turn.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Host tools bound to clientToolCallSchema: ${ClientToolName.entries.size}",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Server base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            key, { key = it },
            label = { Text("API key (Android Keystore)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveConnection(baseUrl, model, key); key = ""; vm.connect() }) { Text("Connect") }
            TextButton(onClick = { secrets.clearApiKey() }) { Text("Forget key") }
        }
        Text(secrets.redactedKeySummary(), style = MaterialTheme.typography.bodySmall)
        when (val a = agent) {
            AgentUi.Idle -> Text("Not connected.")
            AgentUi.Connecting -> Text("Connecting…")
            is AgentUi.Ready -> Text("✓ ${a.account} · session: ${a.session}")
            is AgentUi.Failed -> Text("✗ ${a.state}: ${a.detail}", color = MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(prompt, { prompt = it }, label = { Text("Ask the agent") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.sendPrompt(prompt); prompt = "" }) { Text("Send") }
        when (val t = turn) {
            TurnUi.Idle -> Unit
            TurnUi.Streaming -> Text("streaming…")
            is TurnUi.Token -> Text(t.text)
            is TurnUi.ToolCall -> Text("tool call → ${t.name} (${t.id})")
            is TurnUi.Done -> Text(t.text)
            is TurnUi.Failed -> Text("✗ ${t.state}: ${t.detail}", color = MaterialTheme.colorScheme.error)
        }
    }
}
