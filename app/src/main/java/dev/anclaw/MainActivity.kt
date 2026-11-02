package dev.anclaw

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Folder
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anclaw.engine.EngineState
import dev.anclaw.ui.AnClawTheme
import dev.anclaw.ui.ChatScreen
import dev.anclaw.ui.LogsScreen
import dev.anclaw.ui.McpScreen
import dev.anclaw.ui.ModelsScreen
import dev.anclaw.ui.SessionsScreen
import dev.anclaw.ui.SettingsScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val ui by vm.ui.settings.collectAsStateWithLifecycle()
            AnClawTheme(ui) { AppShell(vm) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppShell(vm: AppViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(toast) { toast?.let { snackbar.showSnackbar(it); vm.toast.value = null } }
    LaunchedEffect(drawer.isOpen) { if (drawer.isOpen) vm.refreshSessions() }
    BackHandler(enabled = screen != Screen.CHAT || drawer.isOpen) {
        if (drawer.isOpen) scope.launch { drawer.close() } else vm.screen.value = Screen.CHAT
    }
    fun go(s: Screen) { vm.screen.value = s; scope.launch { drawer.close() } }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = { Sidebar(vm, onGo = ::go, onClose = { scope.launch { drawer.close() } }) },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    title = {
                        if (screen == Screen.CHAT) ModelPicker(vm, chat.model)
                        else Text(screen.title, maxLines = 1)
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Menu") }
                    },
                    actions = {
                        if (screen == Screen.CHAT) IconButton(onClick = { vm.newChat() }) {
                            Icon(Icons.Outlined.ChatBubbleOutline, "New chat")
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            val m = Modifier.padding(padding)
            when (screen) {
                Screen.CHAT -> ChatScreen(vm, m)
                Screen.SESSIONS -> dev.anclaw.ui.AllChatsScreen(vm, m)
                Screen.MODELS -> ModelsScreen(vm, m)
                Screen.MCP -> McpScreen(vm, m)
                Screen.PHONE -> dev.anclaw.ui.PhoneAccessScreen(vm, m)
                Screen.SETTINGS -> SettingsScreen(vm, m)
                Screen.LOGS -> LogsScreen(vm, m)
            }
        }
    }
}

/** Top-bar model switcher: the loaded model and a menu of the others (and cloud providers). */
@Composable
private fun ModelPicker(vm: AppViewModel, current: String) {
    val models by vm.models.models.collectAsStateWithLifecycle()
    val engine by vm.engine.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val label = when (vm.prefs.provider) {
        Provider.LOCAL -> when (val e = engine) {
            is EngineState.Ready -> e.model.name.removeSuffix(".gguf")
            is EngineState.Starting -> "Loading…"
            else -> vm.models.byId(vm.prefs.activeModelId)?.name?.removeSuffix(".gguf") ?: "No model"
        }
        else -> current.ifEmpty { vm.prefs.provider.label }
    }
    Box {
        Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { open = true }.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp))
            Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp))
        }
        DropdownMenu(open, { open = false }) {
            models.forEach { m ->
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.Memory, null) },
                    text = {
                        Column {
                            Text(m.name.removeSuffix(".gguf"))
                            Text(listOfNotNull(m.info?.sizeLabel, m.info?.quant).joinToString(" · ").ifEmpty { "on device" },
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { open = false; vm.prefs.provider = Provider.LOCAL; vm.startEngine(m) },
                )
            }
            HorizontalDivider()
            Provider.entries.filter { it != Provider.LOCAL }.forEach { p ->
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.Cloud, null) },
                    text = { Text(p.label) },
                    onClick = { open = false; vm.prefs.provider = p; vm.restartAgent() },
                )
            }
            DropdownMenuItem(leadingIcon = { Icon(Icons.Default.Add, null) }, text = { Text("Manage models…") },
                onClick = { open = false; vm.screen.value = Screen.MODELS })
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Sidebar(vm: AppViewModel, onGo: (Screen) -> Unit, onClose: () -> Unit) {
    val chats by vm.index.chats.collectAsStateWithLifecycle()
    val projects by vm.index.projects.collectAsStateWithLifecycle()
    val engine by vm.engine.state.collectAsStateWithLifecycle()
    val currentChat by vm.currentChatId.collectAsStateWithLifecycle()
    val currentProject by vm.currentProjectId.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val sel = remember { dev.anclaw.ui.ChatSelection() }
    var newProject by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<dev.anclaw.agent.Project?>(null) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.width(310.dp)) {
        Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp)) {
            Row(Modifier.padding(start = 8.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("✻ ", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
                Text("an-claw", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = { vm.newChat(); onClose() }, shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), modifier = Modifier.weight(1f),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        }
                        Text("  New chat" + (vm.index.project(currentProject)?.let { " in ${it.name}" } ?: ""),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = { vm.newChat(incognito = true); onClose() }) {
                    Icon(Icons.Default.VisibilityOff, "Incognito chat")
                }
            }
            Spacer(Modifier.size(4.dp))
            NavRow(Icons.Default.Memory, "Models", onClick = { onGo(Screen.MODELS) })
            NavRow(Icons.Default.Extension, "MCP & tools", onClick = { onGo(Screen.MCP) })
            NavRow(Icons.Default.PhoneAndroid, "Phone access", onClick = { onGo(Screen.PHONE) })
            NavRow(Icons.Default.Settings, "Settings", onClick = { onGo(Screen.SETTINGS) })
            NavRow(Icons.AutoMirrored.Filled.Subject, "Logs", onClick = { onGo(Screen.LOGS) })
            LazyColumn(Modifier.weight(1f)) {
                item {
                    Row(Modifier.padding(start = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Projects", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f))
                        IconButton(onClick = { newProject = true }, Modifier.size(32.dp)) { Icon(Icons.Default.Add, "New project", Modifier.size(18.dp)) }
                    }
                }
                items(projects, key = { "p" + it.id }) { p ->
                    val active = p.id == currentProject
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                        .combinedClickable(
                            onClick = { vm.currentProjectId.value = if (active) null else p.id },
                            onLongClick = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); editing = p },
                        ).padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("  ${p.name}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${chats.count { it.projectId == p.id }}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (projects.isEmpty()) item {
                    Text("Group chats and give them their own folder and instructions.", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp))
                }
                item {
                    TextField(
                        query, { query = it }, Modifier.fillMaxWidth().padding(top = 10.dp), singleLine = true,
                        placeholder = { Text("Search chats") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                        shape = RoundedCornerShape(12.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    )
                }
                val shown = chats.filter {
                    (currentProject == null || it.projectId == currentProject) && (query.isBlank() || it.title.contains(query, ignoreCase = true))
                }
                item {
                    if (sel.active) dev.anclaw.ui.SelectionBar(vm, sel, shown)
                    else Text(vm.index.project(currentProject)?.let { "Chats in ${it.name}" } ?: "Recents",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 4.dp))
                }
                items(shown.take(60), key = { it.id }) { e ->
                    dev.anclaw.ui.ChatRow(e, e.id == currentChat, sel, null, onOpen = { vm.openChat(e); onClose() })
                }
                if (shown.isEmpty()) item {
                    Text(if (query.isBlank()) "No chats yet" else "No matches", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(10.dp))
                }
                item { NavRow(null, "All chats…", onClick = { onGo(Screen.SESSIONS) }) }
            }
            HorizontalDivider()
            // Engine status, like the account row at the bottom of Claude's sidebar.
            val (dot, text) = when (val e = engine) {
                is EngineState.Ready -> Color(0xFF4FA36B) to "${e.model.name.removeSuffix(".gguf")} · ready"
                is EngineState.Starting -> Color(0xFFD9A441) to "Loading ${e.model.name.removeSuffix(".gguf")}"
                is EngineState.Failed -> MaterialTheme.colorScheme.error to "Engine failed"
                EngineState.Stopped -> MaterialTheme.colorScheme.outline to
                    if (vm.prefs.provider == Provider.LOCAL) "Engine stopped" else vm.prefs.provider.label
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onGo(Screen.MODELS) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
                Text("  $text", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (newProject) dev.anclaw.ui.ProjectDialog(null, onDismiss = { newProject = false }) { name, folder, instructions ->
        vm.createProject(name, folder, instructions); newProject = false; onClose()
    }
    editing?.let { p ->
        dev.anclaw.ui.ProjectDialog(p, onDismiss = { editing = null }, onDelete = { vm.deleteProject(p.id); editing = null }) { name, _, instructions ->
            vm.updateProject(p.copy(name = name.trim(), instructions = instructions)); editing = null
        }
    }
}

@Composable
private fun NavRow(icon: ImageVector?, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.size(12.dp)) }
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
