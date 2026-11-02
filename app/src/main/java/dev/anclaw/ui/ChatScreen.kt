package dev.anclaw.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anclaw.AppViewModel
import dev.anclaw.Provider
import dev.anclaw.Screen
import dev.anclaw.agent.ChatItem
import dev.anclaw.agent.ChatState
import dev.anclaw.agent.PermissionRequest
import dev.anclaw.agent.QuestionRequest
import dev.anclaw.agent.SlashCommands
import dev.anclaw.engine.EngineState
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Width of the reading column on tablets/landscape (Claude-desktop style). */
private val COLUMN = 760.dp

private fun Density.gap() = when (this) {
    Density.COMPACT -> 6.dp; Density.COMFORTABLE -> 12.dp; Density.SPACIOUS -> 20.dp
}

@Composable
fun ChatScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val chat by vm.chat.collectAsStateWithLifecycle()
    val engine by vm.engine.state.collectAsStateWithLifecycle()
    val ui = LocalUi.current
    val listState = rememberLazyListState()

    val lastLen = when (val last = chat.items.lastOrNull()) {
        is ChatItem.Assistant -> last.text.length
        is ChatItem.Thinking -> last.text.length
        else -> 0
    }
    LaunchedEffect(chat.items.size, lastLen, chat.busy) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.scrollToItem(count - 1, Int.MAX_VALUE / 4)
    }

    val incognito by vm.incognito.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().imePadding()) {
        if (incognito) Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.VisibilityOff, null, Modifier.size(16.dp))
            Text("  Incognito chat: nothing is saved, and it is not in your chat list.", style = MaterialTheme.typography.labelMedium)
        }
        EngineStrip(engine)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (chat.items.isEmpty()) {
                Welcome(vm, engine) { vm.send(it) }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().widthIn(max = COLUMN),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(ui.density.gap()),
                ) {
                    items(chat.items, key = { it.key }) { item ->
                        when (item) {
                            is ChatItem.User -> UserMessage(item, ui)
                            is ChatItem.Assistant -> AssistantMessage(item)
                            is ChatItem.Thinking -> if (ui.showThinking) ThinkingRow(item)
                            is ChatItem.Tool -> when (ui.toolDisplay) {
                                ToolDisplay.HIDDEN -> {}
                                ToolDisplay.MINIMAL -> ToolLine(item)
                                else -> ToolCard(item, startOpen = ui.toolDisplay == ToolDisplay.EXPANDED)
                            }
                            is ChatItem.Notice -> NoticeRow(item)
                        }
                    }
                    if (chat.busy) item(key = "working") { Working(chat) }
                    if (!chat.busy && ui.showUsage) chat.lastUsage?.let { (inp, out) ->
                        item(key = "usage") {
                            Text("↑ ${fmt(inp)} · ↓ ${fmt(out)} tokens", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
        if (chat.background.isNotEmpty()) {
            LazyRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(chat.background, key = { it.id }) { job ->
                    InputChip(
                        selected = false,
                        onClick = { vm.stopBackground(job.id) },
                        label = { Text("${job.command.take(28)} · ${job.seconds}s", maxLines = 1) },
                        leadingIcon = { Icon(Icons.Default.Terminal, null, Modifier.size(16.dp)) },
                        trailingIcon = { Icon(Icons.Default.Close, "Stop", Modifier.size(16.dp)) },
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Composer(vm, chat, Modifier.widthIn(max = COLUMN))
        }
    }

    chat.permission?.let { PermissionDialog(it, vm::answerPermission) }
    chat.question?.let { QuestionDialog(it, vm::answerQuestion) }
}

@Composable
private fun EngineStrip(engine: EngineState) {
    when (engine) {
        is EngineState.Starting -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text("Loading ${engine.model.name} · ${engine.detail}", style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 2.dp))
        }
        is EngineState.Failed -> Text(
            engine.message, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2,
        )
        else -> {}
    }
}

fun fmt(n: Long) = if (n >= 1000) "%.1fk".format(n / 1000.0) else n.toString()

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Welcome(vm: AppViewModel, engine: EngineState, onStarter: (String) -> Unit) {
    val models by vm.models.models.collectAsStateWithLifecycle()
    val ui = LocalUi.current
    Column(
        Modifier.fillMaxSize().widthIn(max = COLUMN).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("✻", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text(greeting(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.size(20.dp))
        when {
            !vm.hasNativeBinaries -> Text("This build has no native binaries: run native/build-native.sh, then rebuild.",
                color = MaterialTheme.colorScheme.error)
            models.isEmpty() && vm.prefs.provider == Provider.LOCAL ->
                OutlinedButton(onClick = { vm.screen.value = Screen.MODELS }) { Text("Add a GGUF model to start") }
            engine is EngineState.Stopped && vm.prefs.provider == Provider.LOCAL -> {
                val active = vm.models.byId(vm.prefs.activeModelId) ?: models.firstOrNull()
                if (active != null) OutlinedButton(onClick = { vm.startEngine(active) }) { Text("Load ${active.name}") }
            }
        }
        Spacer(Modifier.size(20.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ui.starters.filter { it.isNotBlank() }.forEach { s ->
                Surface(
                    onClick = { onStarter(s) }, shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Text(s, Modifier.padding(horizontal = 12.dp, vertical = 10.dp).widthIn(max = 280.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun greeting(): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when (h) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Up late?" }
}

@Composable
private fun UserMessage(item: ChatItem.User, ui: UiSettings) {
    val content: @Composable () -> Unit = {
        Column {
            SelectionContainer { Text(item.text, style = MaterialTheme.typography.bodyLarge) }
            if (item.queued) Text("queued for the running turn", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (ui.userStyle == UserStyle.BUBBLE) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(18.dp),
                modifier = Modifier.padding(start = 40.dp)) {
                Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) { content() }
            }
        }
    } else {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center) { Text("Y", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp) }
            Spacer(Modifier.size(10.dp))
            content()
        }
    }
}

@Composable
private fun AssistantMessage(item: ChatItem.Assistant) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth()) {
        SelectionContainer { Markdown(item.text) }
        Row {
            IconButton(onClick = { clipboard.setText(AnnotatedString(item.text)) }, Modifier.size(28.dp)) {
                Icon(Icons.Default.ContentCopy, "Copy", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ThinkingRow(item: ChatItem.Thinking) {
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { open = !open }.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Psychology, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (open) " Thought process" else " " + item.text.trim().lineSequence().lastOrNull().orEmpty().take(90),
                style = MaterialTheme.typography.labelMedium, fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) Text(
            item.text.trim(), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 10.dp, top = 6.dp),
        )
    }
}

/** A short description of a tool call: its command, path, query or URL. */
fun toolSummary(name: String, input: String): String {
    val o = runCatching { JSONObject(input) }.getOrNull() ?: return input.take(120)
    for (key in listOf("command", "file_path", "path", "pattern", "query", "url", "repo_id", "repo", "prompt", "description", "skill")) {
        o.optString(key).takeIf { it.isNotBlank() }?.let { return it.lineSequence().first().take(160) }
    }
    return o.keys().asSequence().firstOrNull()?.let { "$it: ${o.opt(it)}".take(120) } ?: ""
}

private fun toolIcon(name: String): ImageVector = when {
    name.contains("bash", true) || name.contains("shell", true) || name == "Pty" -> Icons.Default.Terminal
    else -> Icons.Default.Build
}

@Composable
private fun ToolLine(item: ChatItem.Tool) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (item.output == null) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
        else Icon(toolIcon(item.name), null, Modifier.size(14.dp),
            tint = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(" ${item.name} · ${toolSummary(item.name, item.input)}", style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToolCard(item: ChatItem.Tool, startOpen: Boolean) {
    var open by rememberSaveable(item.key) { mutableStateOf(startOpen) }
    val running = item.output == null
    val tint = when {
        item.isError -> MaterialTheme.colorScheme.error
        running -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.clickable { open = !open }.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                else Icon(toolIcon(item.name), null, Modifier.size(16.dp), tint = tint)
                Text(" ${item.name}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                Text("  " + toolSummary(item.name, item.input), style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open) {
                Spacer(Modifier.size(8.dp))
                if (item.input.isNotBlank()) CodeBlock(prettyJson(item.input), "input")
                item.output?.let {
                    Spacer(Modifier.size(6.dp))
                    CodeBlock(it.ifEmpty { "(no output)" }, if (item.isError) "error" else "output")
                }
            } else if (!item.output.isNullOrBlank()) {
                Text(item.output.trim().lineSequence().take(2).joinToString("\n"),
                    style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, start = 22.dp))
            }
        }
    }
}

fun prettyJson(text: String): String = runCatching { JSONObject(text).toString(2).replace("\\/", "/") }.getOrDefault(text)

@Composable
private fun NoticeRow(item: ChatItem.Notice) {
    val (icon, color) = when (item.kind) {
        ChatItem.Notice.Kind.ERROR -> Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
        ChatItem.Notice.Kind.BTW -> Icons.Default.Lightbulb to MaterialTheme.colorScheme.secondary
        ChatItem.Notice.Kind.INFO -> Icons.Default.Info to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
        .padding(10.dp)) {
        Icon(icon, null, Modifier.size(16.dp).padding(top = 2.dp), tint = color)
        Spacer(Modifier.size(8.dp))
        SelectionContainer { Markdown(item.text, Modifier.weight(1f)) }
    }
}

@Composable
private fun Working(chat: ChatState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("✻", color = MaterialTheme.colorScheme.primary)
        val p = chat.progress
        Text(
            if (p != null && p.second > 0) "  Reading prompt… ${p.first * 100 / p.second}%" else "  Working…",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val MODES = listOf(
    "read-only" to "Read only", "prompt" to "Ask always", "workspace-write" to "Edit workspace", "danger-full-access" to "Full access",
)

@Composable
private fun Composer(vm: AppViewModel, chat: ChatState, modifier: Modifier) {
    val ui = LocalUi.current
    var text by rememberSaveable { mutableStateOf("") }
    val suggestions = remember(text) { SlashCommands.matching(text).take(8) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val attach = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch { vm.attach(uri)?.let { text = (text.trimEnd() + " " + it + " ").trimStart() } }
    }
    fun send() {
        if (text.isBlank()) return
        if (ui.haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        vm.send(text); text = ""
    }

    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (suggestions.isNotEmpty()) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.padding(bottom = 6.dp)) {
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                    suggestions.forEach { cmd ->
                        Row(Modifier.fillMaxWidth().clickable { text = "/${cmd.name} " }.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("/${cmd.name}", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge)
                            Text("  ${cmd.help}", style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 2.dp,
        ) {
            Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 6.dp)) {
                Box(Modifier.fillMaxWidth().heightIn(min = 28.dp, max = 200.dp)) {
                    if (text.isEmpty()) Text(
                        if (chat.busy) "Reply to claw…" else "How can I help you today?",
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    BasicTextField(
                        value = text, onValueChange = { text = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = if (ui.enterSends) ImeAction.Send else ImeAction.Default),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { attach.launch(arrayOf("*/*")) }, Modifier.size(36.dp)) {
                        Icon(Icons.Default.Add, "Attach", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ModeChip(vm.prefs.permissionMode) { vm.setPermissionMode(it) }
                    Spacer(Modifier.weight(1f))
                    if (chat.contextWindow > 0) ContextRing(chat.contextTokens.toFloat() / chat.contextWindow)
                    Spacer(Modifier.size(6.dp))
                    val stop = chat.busy && text.isBlank()
                    Surface(
                        onClick = { if (stop) vm.cancel() else send() },
                        enabled = stop || text.isNotBlank(),
                        shape = RoundedCornerShape(10.dp),
                        color = if (stop || text.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(if (stop) Icons.Default.Stop else Icons.Default.ArrowUpward, if (stop) "Stop" else "Send",
                                tint = if (stop || text.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = true }.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(" " + (MODES.firstOrNull { it.first == current }?.second ?: current), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(open, { open = false }) {
            MODES.forEach { (k, label) ->
                DropdownMenuItem(text = { Text(label + if (k == current) "  ✓" else "") }, onClick = { open = false; onPick(k) })
            }
        }
    }
}

@Composable
private fun ContextRing(fraction: Float) {
    Box(contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp,
            color = if (fraction > 0.85f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun PermissionDialog(req: PermissionRequest, answer: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Default.Lock, null) },
        title = { Text("Allow ${req.tool}?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                req.reason?.let { Text(it, style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.size(8.dp)) }
                CodeBlock(req.command ?: prettyJson(req.input), if (req.command != null) "sh" else "input", maxLines = 30)
                if (req.host) Text("Runs with the app's full access.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { answer("always") }) { Text("Always allow") }
                TextButton(onClick = { answer("allow") }) { Text("Allow once") }
            }
        },
        dismissButton = { TextButton(onClick = { answer("deny") }) { Text("Deny") } },
    )
}

@Composable
private fun QuestionDialog(q: QuestionRequest, answer: (String) -> Unit) {
    var custom by remember(q.id) { mutableStateOf("") }
    val picked = remember(q.id) { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(q.header ?: "Question") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(q.question)
                q.options.forEach { option ->
                    if (q.multiSelect) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                            if (option in picked) picked.remove(option) else picked.add(option)
                        }) {
                            Checkbox(option in picked, onCheckedChange = null)
                            Text(option, Modifier.padding(start = 8.dp))
                        }
                    } else {
                        AssistChip(onClick = { answer(option) }, label = { Text(option) }, modifier = Modifier.fillMaxWidth(),
                            trailingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp)) })
                    }
                }
                OutlinedTextField(custom, { custom = it }, label = { Text("Other answer") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { answer(custom.ifBlank { picked.joinToString(", ") }) },
                enabled = custom.isNotBlank() || picked.isNotEmpty()) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = { answer("") }) { Text("Dismiss") } },
    )
}
