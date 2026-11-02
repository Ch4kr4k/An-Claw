package dev.anclaw.ui

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anclaw.AppViewModel
import dev.anclaw.engine.EngineState
import dev.anclaw.engine.LlamaServer
import dev.anclaw.model.ModelEntry

fun humanBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var u = 0
    while (v >= 1024 && u < units.lastIndex) { v /= 1024; u++ }
    return "%.1f %s".format(v, units[u])
}

@Composable
fun ModelsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val models by vm.models.models.collectAsStateWithLifecycle()
    val transfer by vm.models.transfer.collectAsStateWithLifecycle()
    val engine by vm.engine.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var copyMode by remember { mutableStateOf(false) }
    var showPath by remember { mutableStateOf(false) }
    var showHf by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ModelEntry?>(null) }
    var allFiles by remember { mutableStateOf(Environment.isExternalStorageManager()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.addModelUri(uri, copyMode)
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            EngineCard(vm, engine)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add a model", style = MaterialTheme.typography.titleMedium)
                    if (!allFiles) {
                        Text(
                            "To load a GGUF where it is (no copy), allow All-files access. Without it, picked files are imported (copied) into app storage.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}")))
                        }) { Text("Grant All-files access") }
                        TextButton(onClick = { allFiles = Environment.isExternalStorageManager() }) { Text("I granted it — recheck") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { copyMode = false; picker.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Load from storage")
                        }
                        OutlinedButton(onClick = { copyMode = true; picker.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.FileOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Import (copy)")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showPath = true }) { Text("Enter path") }
                        OutlinedButton(onClick = { showHf = true }) {
                            Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Hugging Face")
                        }
                        IconButton(onClick = { vm.refreshModels() }) { Icon(Icons.Default.Refresh, "Rescan") }
                    }
                    Text(
                        "Imported and downloaded models: ${vm.paths.modelsDir.path}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    transfer?.let { t ->
                        Text(t.label + if (t.total > 0) " — ${humanBytes(t.done)} / ${humanBytes(t.total)}" else "",
                            style = MaterialTheme.typography.bodySmall)
                        if (t.error != null) {
                            Text(t.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { vm.models.clearTransferError() }) { Text("OK") }
                        } else LinearProgressIndicator(progress = { t.fraction }, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if (models.isEmpty()) item {
            Text("No models yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        }
        items(models, key = { it.id }) { model ->
            ModelRow(
                model = model,
                active = model.id == vm.prefs.activeModelId,
                running = (engine as? EngineState.Ready)?.model?.id == model.id || (engine as? EngineState.Starting)?.model?.id == model.id,
                onLoad = { vm.startEngine(model) },
                onRename = { renaming = model },
                onDelete = { vm.models.remove(model.id) },
                onTemplate = { vm.models.setTemplate(model.id, it) },
                onOptimize = { vm.optimizeModel(model) },
            )
        }
        item { EngineParams(vm) }
    }

    if (showPath) TextDialog("GGUF path", "/sdcard/Download/model.gguf", onDismiss = { showPath = false }) {
        vm.addModelPath(it); showPath = false
    }
    if (showHf) HfDialog(onDismiss = { showHf = false }) { repo, file -> vm.downloadModel(repo, file); showHf = false }
    renaming?.let { m ->
        TextDialog("Rename", m.name, initial = m.name, onDismiss = { renaming = null }) { vm.models.rename(m.id, it); renaming = null }
    }
}

@Composable
private fun EngineCard(vm: AppViewModel, engine: EngineState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Engine (llama-abominated)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (engine is EngineState.Ready || engine is EngineState.Starting) {
                    IconButton(onClick = { vm.stopEngine() }) { Icon(Icons.Default.Stop, "Stop") }
                }
            }
            val (text, color) = when (engine) {
                EngineState.Stopped -> "Stopped" to MaterialTheme.colorScheme.onSurfaceVariant
                is EngineState.Starting -> "Loading ${engine.model.name}… ${engine.detail}" to MaterialTheme.colorScheme.primary
                is EngineState.Ready -> "Ready: ${engine.model.name} on 127.0.0.1:${engine.port}, ctx ${engine.ctx}" to MaterialTheme.colorScheme.secondary
                is EngineState.Failed -> engine.message to MaterialTheme.colorScheme.error
            }
            Text(text, color = color, style = MaterialTheme.typography.bodySmall)
            if (engine is EngineState.Starting) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!vm.engine.isAvailable) Text("llama-server is not in this build.", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ModelRow(
    model: ModelEntry, active: Boolean, running: Boolean,
    onLoad: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onTemplate: (String) -> Unit,
    onOptimize: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(
        containerColor = if (running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    )) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active) Icon(Icons.Default.CheckCircle, "Active", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(" ${model.name}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onLoad, enabled = model.exists) { Icon(Icons.Default.PlayArrow, "Load") }
                IconButton(onClick = onRename) { Icon(Icons.Default.Edit, "Rename") }
                IconButton(onClick = { confirm = true }) { Icon(Icons.Default.Delete, "Remove") }
            }
            val info = model.info
            val meta = listOfNotNull(
                humanBytes(model.sizeBytes),
                info?.architecture,
                info?.sizeLabel,
                info?.quant,
                info?.contextLength?.let { "ctx ${it}" },
                info?.expertCount?.takeIf { it > 0 }?.let { "MoE ${it} experts" },
                if (model.imported) "imported" else "in place",
            ).joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(model.path, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                color = if (model.exists) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (info?.quant?.let { it.startsWith("Q") && it.contains("_K") || it.startsWith("Q5") || it.startsWith("Q6") } == true) {
                TextButton(onClick = onOptimize, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) {
                    Text("⚡ Optimize for this phone (make a Q4_0 copy: ~1.6× faster prompts)", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (info != null && !info.hasChatTemplate) Text(
                "No chat template in this file: tool calling may not work.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
            )
            if (info != null && (info.needsToolTemplate || model.template != "auto")) {
                val effective = when (model.template) {
                    "auto" -> if (info.needsToolTemplate) "bundled Qwen3 tool template (the model's own has no tool support)" else "model's own"
                    "own" -> "model's own" + if (!info.templateHasTools) " — no tool calls!" else ""
                    else -> model.template
                }
                Text("Chat template: $effective", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("auto" to "Auto", "qwen3-tools" to "Qwen3 tools", "own" to "Model's own").forEach { (k, label) ->
                        FilterChip(model.template == k, { onTemplate(k) }, label = { Text(label) })
                    }
                }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Remove ${model.name}?") },
        text = { Text(if (model.imported) "The imported copy is deleted." else "The file stays where it is; only the entry is removed.") },
        confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

@Composable
private fun EngineParams(vm: AppViewModel) {
    val p = vm.prefs
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Engine parameters", style = MaterialTheme.typography.titleMedium)
            Text("Applied the next time a model is loaded.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            NumberField("Context size (tokens)", p.contextSize) { p.contextSize = it }
            NumberField("Threads (0 = auto: ${LlamaServer.performanceCores()} performance cores)", p.threads) { p.threads = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Batch", p.batch, Modifier.weight(1f)) { p.batch = it }
                NumberField("Micro-batch", p.ubatch, Modifier.weight(1f)) { p.ubatch = it }
            }
            Choice("Flash attention", listOf("auto", "on", "off"), p.flashAttn) { p.flashAttn = it }
            Choice("KV cache type", listOf("f16", "q8_0", "q4_0"), p.kvCacheType) { p.kvCacheType = it }
            var mmap by remember { mutableStateOf(p.mmap) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Memory-map the model (mmap)", Modifier.weight(1f))
                Switch(mmap, { mmap = it; p.mmap = it })
            }
            NumberField("Port", p.port) { p.port = it }
            var extra by remember { mutableStateOf(p.extraArgs) }
            OutlinedTextField(
                extra, { extra = it; p.extraArgs = it }, Modifier.fillMaxWidth(),
                label = { Text("Extra llama-server args") },
                supportingText = { Text("e.g. --moe-n-expert 6 · -ser 6,0.4 · --reasoning-budget 0") },
            )
        }
    }
}

@Composable
fun NumberField(label: String, value: Int, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    OutlinedTextField(
        text, { t -> text = t.filter(Char::isDigit); text.toIntOrNull()?.let(onChange) }, modifier,
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
fun Choice(label: String, options: List<String>, value: String, onChange: (String) -> Unit) {
    var current by remember { mutableStateOf(value) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { o -> FilterChip(current == o, { current = o; onChange(o) }, label = { Text(o) }) }
        }
    }
}

@Composable
fun TextDialog(title: String, hint: String, initial: String = "", onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, placeholder = { Text(hint) }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onOk(text) }, enabled = text.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun HfDialog(onDismiss: () -> Unit, onOk: (String, String) -> Unit) {
    var repo by remember { mutableStateOf("") }
    var file by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download from Hugging Face") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(repo, { repo = it }, label = { Text("Repo") }, placeholder = { Text("Qwen/Qwen3-1.7B-GGUF") }, singleLine = true)
                OutlinedTextField(file, { file = it }, label = { Text("File") }, placeholder = { Text("Qwen3-1.7B-Q8_0.gguf") }, singleLine = true)
                HorizontalDivider()
                Text(
                    "Tip: ask the agent to find a model (hf_search, hf_list_files) and download it with hf_download; it lands in the models folder.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onOk(repo, file) }, enabled = repo.contains('/') && file.endsWith(".gguf", true)) { Text("Download") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
