package dev.anclaw.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anclaw.AppViewModel
import dev.anclaw.agent.ChatEntry
import dev.anclaw.agent.Project
import java.text.DateFormat
import java.util.Date

/** Long-press selection over a chat list: delete, rename, move to a project. */
class ChatSelection {
    var selected by mutableStateOf(emptySet<String>())
    val active get() = selected.isNotEmpty()
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }
    fun clear() { selected = emptySet() }
}

/** The bar shown while chats are selected. */
@Composable
fun SelectionBar(vm: AppViewModel, sel: ChatSelection, all: List<ChatEntry>) {
    var confirmDelete by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ChatEntry?>(null) }
    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { sel.clear() }) { Icon(Icons.Default.Close, "Cancel") }
            Text("${sel.selected.size}", style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f))
            IconButton(onClick = { sel.selected = all.map { it.id }.toSet() }) { Icon(Icons.Default.SelectAll, "Select all") }
            if (sel.selected.size == 1) IconButton(onClick = { renaming = all.firstOrNull { it.id in sel.selected } }) {
                Icon(Icons.Default.Edit, "Rename")
            }
            IconButton(onClick = { moving = true }) { Icon(Icons.AutoMirrored.Filled.DriveFileMove, "Move to project") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${sel.selected.size} chat${if (sel.selected.size == 1) "" else "s"}?") },
        text = { Text("Their conversations are deleted from the phone. Files the agent made in the workspace stay.") },
        confirmButton = { TextButton(onClick = { vm.deleteChats(sel.selected); sel.clear(); confirmDelete = false }) {
            Text("Delete", color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
    if (moving) MoveDialog(vm, onDismiss = { moving = false }) { projectId ->
        vm.moveChats(sel.selected, projectId); sel.clear(); moving = false
    }
    renaming?.let { e ->
        TextDialog("Rename chat", "Title", initial = e.title, onDismiss = { renaming = null }) {
            vm.renameChat(e.id, it); renaming = null; sel.clear()
        }
    }
}

@Composable
private fun MoveDialog(vm: AppViewModel, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val projects by vm.index.projects.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to project") },
        text = {
            Column {
                Row(Modifier.fillMaxWidth().clickable { onPick(null) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.FolderOff, null); Text("  No project")
                }
                projects.forEach { p ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(p.id) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary); Text("  ${p.name}")
                    }
                }
                TextButton(onClick = { creating = true }) { Text("+ New project") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (creating) ProjectDialog(null, onDismiss = { creating = false }) { name, folder, instructions ->
        val p = vm.index.createProject(name, folder, instructions)
        creating = false
        onPick(p.id)
    }
}

/** Create or edit a project. */
@Composable
fun ProjectDialog(initial: Project?, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null, onSave: (String, String?, String) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var folder by remember { mutableStateOf(initial?.workspace ?: "") }
    var instructions by remember { mutableStateOf(initial?.instructions ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New project" else "Edit project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(folder, { folder = it }, label = { Text("Folder (empty = new private folder)") },
                    singleLine = true, enabled = initial == null,
                    supportingText = { Text("claw works in this folder. Shared storage (e.g. /sdcard/code/x) needs All-files access.") })
                OutlinedTextField(instructions, { instructions = it }, label = { Text("Project instructions") }, minLines = 3,
                    supportingText = { Text("Saved as CLAW.md in the folder; claw reads it in every chat of the project.") })
                if (onDelete != null) TextButton(onClick = onDelete) {
                    Text("Delete project (chats are kept, folder is kept)", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, folder.ifBlank { null }, instructions) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One chat row: tap opens (or toggles while selecting), long-press starts selecting. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatRow(entry: ChatEntry, current: Boolean, sel: ChatSelection, project: String?, onOpen: () -> Unit, detailed: Boolean = false) {
    val haptic = LocalHapticFeedback.current
    val selected = entry.id in sel.selected
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(when {
                selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                current -> MaterialTheme.colorScheme.surfaceContainerHighest
                else -> Color.Transparent
            })
            .combinedClickable(
                onClick = { if (sel.active) sel.toggle(entry.id) else onOpen() },
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); sel.toggle(entry.id) },
            )
            .padding(horizontal = 10.dp, vertical = if (detailed) 14.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sel.active) {
            Icon(if (selected) Icons.Default.CheckCircle else Icons.Outlined.Circle, null, Modifier.size(18.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Spacer(Modifier.size(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(entry.title, maxLines = if (detailed) 2 else 1, overflow = TextOverflow.Ellipsis,
                style = if (detailed) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
            if (detailed) Text(
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.updatedMs)) +
                    (project?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "All chats" screen: every chat with search, long-press selection and project filter. */
@Composable
fun AllChatsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val chats by vm.index.chats.collectAsStateWithLifecycle()
    val projects by vm.index.projects.collectAsStateWithLifecycle()
    val current by vm.currentChatId.collectAsStateWithLifecycle()
    val sel = remember { ChatSelection() }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<String?>("all") }
    LaunchedEffect(Unit) { vm.refreshSessions() }
    val shown = chats.filter { (filter == "all" || it.projectId == filter) && (query.isBlank() || it.title.contains(query, true)) }
    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search chats") }, singleLine = true)
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            item { androidx.compose.material3.FilterChip(filter == "all", { filter = "all" }, label = { Text("All") }) }
            item { androidx.compose.material3.FilterChip(filter == null, { filter = null }, label = { Text("No project") }) }
            items(projects, key = { it.id }) { p ->
                androidx.compose.material3.FilterChip(filter == p.id, { filter = p.id }, label = { Text(p.name) })
            }
        }
        if (sel.active) SelectionBar(vm, sel, shown)
        else Text("Long-press a chat to select, delete or move it.", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.id }) { e ->
                ChatRow(e, e.id == current, sel, vm.index.project(e.projectId)?.name, onOpen = { vm.openChat(e) }, detailed = true)
            }
            if (shown.isEmpty()) item { Text("No chats", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        OutlinedButton(onClick = { vm.newChat() }, Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text("New chat") }
    }
}
