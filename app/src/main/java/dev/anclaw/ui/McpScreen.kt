package dev.anclaw.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anclaw.AppViewModel
import dev.anclaw.agent.ClawSettings
import dev.anclaw.agent.McpPresets
import dev.anclaw.agent.McpServer

@Composable
fun McpScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val servers by vm.clawSettings.servers.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<McpServer?>(null) }
    var adding by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { TokensCard(vm) { dirty = true } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MCP servers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Add") }
            }
            Text(
                "Stored in claw's ~/.claw/settings.json. claw connects them when a chat starts; use /mcp in a chat to see their tools.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(servers, key = { it.name }) { server ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(server.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (server.builtin) "built in: Hugging Face (hf_*) and GitHub (gh_*) tools"
                                else "${server.type} · " + if (server.type == "stdio") (listOf(server.command) + server.args).joinToString(" ") else server.url,
                                style = MaterialTheme.typography.bodySmall, fontFamily = if (server.builtin) null else FontFamily.Monospace,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(server.enabled, { vm.clawSettings.setEnabled(server.name, it); dirty = true })
                        if (!server.builtin) {
                            IconButton(onClick = { editing = server }) { Icon(Icons.Default.Edit, "Edit") }
                            IconButton(onClick = { vm.clawSettings.remove(server.name); dirty = true }) { Icon(Icons.Default.Delete, "Remove") }
                        }
                    }
                }
            }
        }
        item {
            Text("Presets", style = MaterialTheme.typography.titleMedium)
            McpPresets.all.forEach { preset ->
                val token = when (preset.tokenKind) { "hf" -> vm.prefs.hfToken; "github" -> vm.prefs.githubToken; else -> "" }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(preset.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(
                        onClick = {
                            vm.clawSettings.upsert(McpServer(
                                preset.name, "http", url = preset.url,
                                headers = if (token.isNotBlank()) mapOf("Authorization" to "Bearer $token") else emptyMap(),
                            ))
                            dirty = true
                        },
                        enabled = servers.none { it.name == preset.name } && (preset.tokenKind == null || token.isNotBlank()),
                    ) { Text(if (preset.tokenKind != null && token.isBlank()) "needs token" else "Add") }
                }
            }
        }
        if (dirty) item {
            OutlinedButton(onClick = { vm.restartAgent(); dirty = false }, modifier = Modifier.fillMaxWidth()) {
                Text("Apply to the current chat (restarts the agent)")
            }
        }
    }

    if (adding || editing != null) McpEditDialog(editing, onDismiss = { adding = false; editing = null }) {
        vm.clawSettings.upsert(it); adding = false; editing = null; dirty = true
    }
}

@Composable
private fun TokensCard(vm: AppViewModel, changed: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tokens", style = MaterialTheme.typography.titleMedium)
            Text(
                "Given to claw and its MCP servers as HF_TOKEN and GITHUB_TOKEN. Optional: raise rate limits, open gated models and private repos, and let gh_create_issue/gh_comment write.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SecretField("Hugging Face token", vm.prefs.hfToken) { vm.prefs.hfToken = it; changed() }
            SecretField("GitHub token", vm.prefs.githubToken) { vm.prefs.githubToken = it; changed() }
        }
    }
}

@Composable
fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    OutlinedTextField(
        text, { text = it.trim(); onChange(text) }, Modifier.fillMaxWidth(),
        label = { Text(label) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
    )
}

@Composable
private fun McpEditDialog(initial: McpServer?, onDismiss: () -> Unit, onSave: (McpServer) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: "http") }
    var command by remember { mutableStateOf(initial?.command ?: "") }
    var args by remember { mutableStateOf(initial?.args?.joinToString(" ") ?: "") }
    var env by remember { mutableStateOf(initial?.env?.entries?.joinToString("\n") { "${it.key}=${it.value}" } ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var headers by remember { mutableStateOf(initial?.headers?.entries?.joinToString("\n") { "${it.key}: ${it.value}" } ?: "") }
    val validName = name.matches(Regex("[A-Za-z0-9_-]+")) && name != ClawSettings.HUB

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add MCP server" else "Edit ${initial.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, enabled = initial == null)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("http", "stdio").forEach { t -> FilterChip(type == t, { type = t }, label = { Text(t) }) }
                }
                if (type == "stdio") {
                    Text(
                        "stdio servers run on the phone: the command must be an Android executable (no node/python unless installed).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(command, { command = it }, label = { Text("Command (absolute path)") }, singleLine = true)
                    OutlinedTextField(args, { args = it }, label = { Text("Arguments") })
                    OutlinedTextField(env, { env = it }, label = { Text("Env (KEY=value per line)") }, minLines = 2)
                } else {
                    OutlinedTextField(url, { url = it }, label = { Text("URL") }, singleLine = true)
                    OutlinedTextField(headers, { headers = it }, label = { Text("Headers (Name: value per line)") }, minLines = 2)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = validName && (if (type == "stdio") command.isNotBlank() else url.startsWith("http") || url.startsWith("ws")),
                onClick = {
                    onSave(McpServer(
                        name = name.trim(), type = type, command = command.trim(),
                        args = dev.anclaw.engine.LlamaServer.splitArgs(args),
                        env = env.lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap(),
                        url = url.trim(),
                        headers = headers.lines().mapNotNull { l -> l.split(':', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap(),
                    ))
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
