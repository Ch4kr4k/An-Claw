package dev.anclaw.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anclaw.AppViewModel
import dev.anclaw.Provider
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val p = vm.prefs
    val ui by vm.ui.settings.collectAsStateWithLifecycle()
    fun set(change: (UiSettings) -> UiSettings) = vm.ui.update(change)
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Section("Appearance") {
            EnumChips("Theme", ThemeMode.entries, ui.theme, { it.label }) { v -> set { it.copy(theme = v) } }
            Text("Accent", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ACCENTS.forEach { (name, argb) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { set { it.copy(accent = argb, dynamicColor = false) } }) {
                        Box(Modifier.size(34.dp).clip(CircleShape).background(Color(argb))
                            .border(if (ui.accent == argb && !ui.dynamicColor) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape))
                        Text(name, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            var hex by remember { mutableStateOf("%06X".format(ui.accent and 0xFFFFFF)) }
            OutlinedTextField(hex, { t ->
                hex = t.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                if (hex.length == 6) set { it.copy(accent = 0xFF000000 or hex.toLong(16), dynamicColor = false) }
            }, label = { Text("Custom accent (hex RRGGBB)") }, singleLine = true, prefix = { Text("#") })
            Toggle("Material You colours (from wallpaper)", ui.dynamicColor) { v -> set { it.copy(dynamicColor = v) } }
            EnumChips("Chat font", ChatFont.entries, ui.font, { it.label }) { v -> set { it.copy(font = v) } }
            Text("Text size: ${"%.0f".format(ui.fontScale * 100)}%", style = MaterialTheme.typography.labelLarge)
            Slider(ui.fontScale, { v -> set { it.copy(fontScale = (v * 20).roundToInt() / 20f) } }, valueRange = 0.8f..1.5f)
            Text("Line spacing: ${"%.2f".format(ui.lineSpacing)}×", style = MaterialTheme.typography.labelLarge)
            Slider(ui.lineSpacing, { v -> set { it.copy(lineSpacing = (v * 20).roundToInt() / 20f) } }, valueRange = 0.85f..1.6f)
            EnumChips("Density", Density.entries, ui.density, { it.label }) { v -> set { it.copy(density = v) } }
        }
        Section("Conversation") {
            EnumChips("Your messages", UserStyle.entries, ui.userStyle, { it.label }) { v -> set { it.copy(userStyle = v) } }
            EnumChips("Tool calls", ToolDisplay.entries, ui.toolDisplay, { it.label }) { v -> set { it.copy(toolDisplay = v) } }
            Toggle("Show model thinking", ui.showThinking) { v -> set { it.copy(showThinking = v) } }
            Toggle("Wrap long lines in code blocks", ui.wrapCode) { v -> set { it.copy(wrapCode = v) } }
            Toggle("Show token usage after replies", ui.showUsage) { v -> set { it.copy(showUsage = v) } }
            Toggle("Enter key sends", ui.enterSends) { v -> set { it.copy(enterSends = v) } }
            Toggle("Haptic feedback", ui.haptics) { v -> set { it.copy(haptics = v) } }
        }
        Section("Personalization") {
            var personal by remember { mutableStateOf(ui.personalPrompt) }
            OutlinedTextField(
                personal, { personal = it; set { s -> s.copy(personalPrompt = it) } }, Modifier.fillMaxWidth(),
                label = { Text("What should claw know about you?") }, minLines = 3,
                supportingText = { Text("Added to every chat's system prompt (new chats, or Apply below).") },
            )
            var starters by remember { mutableStateOf(ui.starters.joinToString("\n")) }
            OutlinedTextField(
                starters, { starters = it; set { s -> s.copy(starters = it.lines().filter(String::isNotBlank)) } }, Modifier.fillMaxWidth(),
                label = { Text("Starter prompts (one per line)") }, minLines = 3,
            )
            OutlinedButton(onClick = { vm.ui.reset() }) { Text("Reset appearance to defaults") }
        }
        Section("Model provider") {
            var provider by remember { mutableStateOf(p.provider) }
            Provider.entries.forEach { opt ->
                Row(Modifier.fillMaxWidth().clickable { provider = opt; p.provider = opt }, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(provider == opt, { provider = opt; p.provider = opt })
                    Text(opt.label)
                }
            }
            when (provider) {
                Provider.LOCAL -> Text("Uses the model loaded on the Models screen.", style = MaterialTheme.typography.bodySmall)
                Provider.OPENAI_COMPAT -> {
                    PrefField("Base URL (…/v1)", p.remoteBaseUrl) { p.remoteBaseUrl = it }
                    PrefField("Model id", p.remoteModel) { p.remoteModel = it }
                    SecretField("API key (optional for LAN servers)", p.remoteApiKey) { p.remoteApiKey = it }
                    Text("e.g. llama-server on your PC: http://192.168.1.10:8080/v1", style = MaterialTheme.typography.bodySmall)
                }
                Provider.ANTHROPIC -> {
                    PrefField("Model", p.anthropicModel) { p.anthropicModel = it }
                    SecretField("Anthropic API key", p.anthropicKey) { p.anthropicKey = it }
                }
            }
        }
        Section("Agent") {
            Choice(
                "Permission mode",
                listOf("read-only", "workspace-write", "prompt", "danger-full-access"),
                p.permissionMode,
            ) { p.permissionMode = it }
            Text(
                "workspace-write: edits inside the workspace freely, asks for risky commands. prompt: asks for every write. danger-full-access: never asks.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrefField("Workspace folder (empty = app private)", p.workspace) { p.workspace = it }
            Text("Default: ${vm.paths.defaultWorkspace.path}. A folder in shared storage (e.g. /sdcard/an-claw) needs All-files access.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var mem by remember { mutableStateOf(p.autoMemory) }
            Toggle("Auto memory + after-turn learning (slower on device)", mem) { mem = it; p.autoMemory = it }
            var cache by remember { mutableStateOf(p.persistCache) }
            Toggle("Keep the prompt cache on disk (fast new chats)", cache) { cache = it; p.persistCache = it }
            TextButton(onClick = { vm.engine.clearPromptCaches(); vm.toast.value = "Prompt caches cleared" }) { Text("Clear saved prompt caches") }
            PrefField("Hidden tools (comma-separated)", p.hiddenTools) { p.hiddenTools = it }
            Text("Desktop-only tools hidden from the model to keep prompts short on a phone. MCP tools are never hidden.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { vm.restartAgent() }) { Text("Apply now (restarts the agent, keeps the chat)") }
        }
        Section("claw settings.json") {
            var text by remember { mutableStateOf(vm.clawSettings.rawText()) }
            var error by remember { mutableStateOf<String?>(null) }
            Text("Full claw configuration: hooks, permission rules, env, model aliases, mcpServers…",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                text, { text = it; error = null }, Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                minLines = 8, maxLines = 24, isError = error != null,
                supportingText = error?.let { { Text(it) } },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    vm.clawSettings.saveRaw(text).onSuccess { vm.toast.value = "Saved" }.onFailure { error = it.message }
                }) { Text("Save") }
                OutlinedButton(onClick = { text = vm.clawSettings.rawText() }) { Text("Reload") }
            }
        }
        Section("About") {
            Text(
                "an-claw: claw-code's agent (tools, slash commands, MCP, skills, memory, checkpoints) with llama-abominated " +
                    "(llama.cpp) running on the phone.\nclaw: ${status(vm.paths.claw)}\nllama-server: ${status(vm.paths.llamaServer)}\n" +
                    "MCP hub: ${status(vm.paths.mcpHub)}\nclaw home: ${vm.paths.clawHome.path}",
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun status(f: File) = if (f.canExecute()) "ok (${humanBytes(f.length())})" else "missing"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <E> EnumChips(label: String, values: List<E>, current: E, name: (E) -> String, onPick: (E) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            values.forEach { v -> FilterChip(v == current, { onPick(v) }, label = { Text(name(v)) }) }
        }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(value, onChange)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun PrefField(label: String, value: String, onChange: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    OutlinedTextField(text, { text = it; onChange(it.trim()) }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
}

@Composable
fun SessionsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshSessions() }
    LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedButton(onClick = { vm.newChat() }, Modifier.fillMaxWidth()) { Text("New chat") }
        }
        if (sessions.isEmpty()) item { Text("No saved sessions yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(sessions, key = { it.path }) { s ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth().clickable { vm.resume(s) },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.updatedMs)) +
                                (s.model?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { vm.deleteSession(s) }) { Icon(Icons.Default.Delete, "Delete") }
                }
            }
        }
    }
}

@Composable
fun LogsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val engineLog by vm.engine.log.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) }
    var agentLog by remember { mutableStateOf("") }
    LaunchedEffect(tab) {
        if (tab == 1) agentLog = runCatching { File(vm.paths.logs, "claw-agent.log").readLines().takeLast(400).joinToString("\n") }.getOrDefault("(empty)")
    }
    Column(modifier.fillMaxSize().padding(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(tab == 0, { tab = 0 }, label = { Text("llama-server") })
            FilterChip(tab == 1, { tab = 1 }, label = { Text("claw") })
        }
        val scroll = rememberScrollState()
        val text = if (tab == 0) engineLog.ifEmpty { "(engine not started)" } else agentLog
        LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
        SelectionContainer(Modifier.weight(1f).verticalScroll(scroll).horizontalScroll(rememberScrollState())) {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, softWrap = false)
        }
    }
}
