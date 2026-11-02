package dev.anclaw

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.anclaw.agent.AgentLaunch
import dev.anclaw.agent.ChatItem
import dev.anclaw.agent.ChatReducer
import dev.anclaw.agent.ChatState
import dev.anclaw.agent.ClawAgent
import dev.anclaw.agent.SessionSummary
import dev.anclaw.agent.Sessions
import dev.anclaw.engine.EngineService
import dev.anclaw.engine.EngineState
import dev.anclaw.model.ModelEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen(val title: String) {
    CHAT("Chat"), SESSIONS("All chats"), MODELS("Models"), MCP("MCP & tools"), PHONE("Phone access"), SETTINGS("Settings"), LOGS("Logs")
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AnClawApp
    val prefs = app.prefs
    val paths = app.paths
    val models = app.models
    val engine = app.engine
    val clawSettings = app.clawSettings
    val ui = app.ui

    val screen = MutableStateFlow(Screen.CHAT)
    private val _chat = MutableStateFlow(ChatState())
    val chat: StateFlow<ChatState> = _chat
    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions
    /** One-line status messages (snackbar). */
    val toast = MutableStateFlow<String?>(null)

    /** Chats and projects for the sidebar. */
    val index = dev.anclaw.agent.ChatIndex(File(app.filesDir, "chats.json"), File(app.filesDir, "projects"))
    /** The open chat's index id (null until its first message, and always for incognito chats). */
    val currentChatId = MutableStateFlow<String?>(null)
    /** Project new chats go into, and whose folder is the working directory. */
    val currentProjectId = MutableStateFlow<String?>(null)
    /** The open chat keeps nothing on disk. */
    val incognito = MutableStateFlow(false)

    /** The running claw process; a new instance per start, so a stopped one's late events are dropped. */
    private var agent = ClawAgent(paths) {}
    /** Prompt typed before the agent was up; sent on `ready`. */
    private var pendingPrompt: String? = null
    /** Launch settings the running agent was started with. */
    private var agentModel: String? = null
    private var resumeNext: String? = null

    val hasNativeBinaries get() = paths.claw.canExecute()

    init {
        viewModelScope.launch { models.refreshInfo() }
        syncSettings()
        // Engine ready → start (or re-point) the agent; keep the service in step.
        viewModelScope.launch {
            // Only distinct notification texts (null = no service): chat state changes per token.
            combine(engine.state, _chat) { e, c ->
                when (e) {
                    is EngineState.Starting -> "Loading ${e.model.name}…"
                    is EngineState.Ready -> if (c.busy) "Working…" else "${e.model.name} ready"
                    else -> if (c.busy) "Working…" else null
                }
            }.distinctUntilChanged().collect { text ->
                if (text != null) EngineService.update(app, text) else EngineService.stop(app)
            }
        }
        viewModelScope.launch {
            engine.state.collect { e ->
                if (e is EngineState.Ready && prefs.provider == Provider.LOCAL) ensureAgent()
                // The local agent cannot work without its server; the next prompt restarts both.
                if ((e is EngineState.Stopped || e is EngineState.Failed) && prefs.provider == Provider.LOCAL && agentModel != null) {
                    stopAgent()
                }
                if (e is EngineState.Failed) {
                    note("Engine: ${e.message}", ChatItem.Notice.Kind.ERROR)
                    pendingPrompt = null
                }
            }
        }
    }

    // ------------------------------------------------------------------ agent

    private fun onAgentEvent(source: ClawAgent, ev: org.json.JSONObject) {
        if (source !== agent) return // from an agent that was stopped
        synchronized(this) { _chat.value = ChatReducer.reduce(_chat.value, ev) }
        when (ev.optString("ev")) {
            "ready" -> {
                val path = ev.optString("session_path").takeIf { it.isNotEmpty() && it != "null" }
                if (path != null) {
                    val id = currentChatId.value
                    // A session the index already has (adopted from disk a moment ago) is this chat: keep one entry.
                    val other = index.bySession(path)
                    if (id != null && other != null && other.id != id) index.delete(setOf(other.id), keepFiles = true)
                    if (id != null) index.update(id) { it.copy(sessionPath = path) }
                }
                pendingPrompt?.let { pendingPrompt = null; source.prompt(it) }
            }
            // The first finished turn has the system prompt + tools processed: keep that KV cache on disk.
            "turn_end" -> {
                currentChatId.value?.let { id -> index.update(id) { it.copy(updatedMs = System.currentTimeMillis()) } }
                if (ev.optString("status") == "done" && prefs.persistCache && prefs.provider == Provider.LOCAL) {
                    viewModelScope.launch(Dispatchers.IO) { engine.savePrefixCacheOnce() }
                }
            }
            "exit" -> {
                agentModel = null
                val tail = source.logTail(12)
                note("claw exited unexpectedly" + if (tail.isNotBlank()) ":\n```\n$tail\n```" else "", ChatItem.Notice.Kind.ERROR)
            }
        }
    }

    private fun workspace(): File =
        index.project(currentProjectId.value)?.workspace?.let(::File)
            ?: prefs.workspace.takeIf { it.isNotBlank() }?.let(::File) ?: paths.defaultWorkspace

    /** The claw model id and env for the current provider; null when it cannot run yet. */
    private fun launchConfig(): Pair<String, Map<String, String>>? {
        val env = mutableMapOf(
            "HF_TOKEN" to prefs.hfToken,
            "GITHUB_TOKEN" to prefs.githubToken,
            "ANCLAW_MODELS_DIR" to paths.modelsDir.path,
            "ANCLAW_WORKSPACE" to workspace().path,
            "CLAW_APP_PROMPT" to appPrompt(),
            "CLAW_HIDE_TOOLS" to prefs.hiddenTools,
            "CLAW_AUTO_MEMORY" to if (prefs.autoMemory) "" else "0",
            "CLAW_LEARNING" to if (prefs.autoMemory) "" else "0",
            "CLAW_MEMORY_REFLECT" to if (prefs.autoMemory) "" else "0",
        ).filterValues { it.isNotBlank() }.toMutableMap()
        val model = when (prefs.provider) {
            Provider.LOCAL -> {
                val ready = engine.state.value as? EngineState.Ready ?: return null
                env["CLAW_LOCAL_BASE_URL"] = "http://127.0.0.1:${ready.port}/v1"
                env["CLAW_LOCAL_API_KEY"] = ready.apiKey
                env["CLAW_CONTEXT_WINDOW"] = ready.ctx.toString()
                "local/${ready.model.alias}"
            }
            Provider.OPENAI_COMPAT -> {
                if (prefs.remoteBaseUrl.isBlank() || prefs.remoteModel.isBlank()) return null
                env["CLAW_LOCAL_BASE_URL"] = prefs.remoteBaseUrl.trim().trimEnd('/')
                if (prefs.remoteApiKey.isNotBlank()) env["CLAW_LOCAL_API_KEY"] = prefs.remoteApiKey.trim()
                "local/${prefs.remoteModel.trim()}"
            }
            Provider.ANTHROPIC -> {
                if (prefs.anthropicKey.isBlank()) return null
                env["ANTHROPIC_API_KEY"] = prefs.anthropicKey.trim()
                prefs.anthropicModel.trim()
            }
        }
        return model to env
    }

    private fun appPrompt() = """
        # Android phone
        You run inside an-claw, an agent app on an Android phone. Commands run in /system/bin/sh (mksh) with toybox
        utilities; bash, git, python, compilers and package managers are normally not installed, so check with
        `command -v` before relying on one. The workspace is ${workspace().path}. GGUF models live in ${paths.modelsDir.path}.
        For GitHub and Hugging Face use the anclaw-hub MCP tools (gh_*, hf_*): gh_download_repo replaces git clone.
        The screen is small: keep replies short and put long output in files.
    """.trimIndent() + ui.settings.value.personalPrompt.trim().let { if (it.isEmpty()) "" else "\n\n# The user's preferences\n$it" }

    /** Starts the agent if it is not running with the current model. */
    private fun ensureAgent(): Boolean {
        if (!hasNativeBinaries) {
            note("This build has no claw binary. Build it with native/build-native.sh and reinstall.", ChatItem.Notice.Kind.ERROR)
            return false
        }
        val (model, env) = launchConfig() ?: return false
        if (agent.running && agentModel == model) return true
        // A restart (model switch, settings change, engine reload) continues the open conversation.
        val resume = resumeNext ?: _chat.value.sessionPath.takeIf { _chat.value.items.isNotEmpty() }
        resumeNext = null
        agent.stop()
        syncSettings()
        agentModel = model
        lateinit var fresh: ClawAgent
        fresh = ClawAgent(paths) { ev -> onAgentEvent(fresh, ev) }
        agent = fresh
        fresh.start(AgentLaunch(model, workspace(), env, resume = resume, incognito = incognito.value))
        return true
    }

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        if (handleAppCommand(text)) return
        // The chat is listed from its first message on, before claw has saved anything.
        if (currentChatId.value == null && !incognito.value && !text.startsWith("/")) {
            currentChatId.value = index.create(text, currentProjectId.value).id.also { id ->
                _chat.value.sessionPath?.let { path -> index.update(id) { it.copy(sessionPath = path) } }
            }
        }
        synchronized(this) { _chat.value = ChatReducer.userPrompt(_chat.value, text) }
        if (agent.running && _chat.value.ready) {
            agent.prompt(text)
            return
        }
        pendingPrompt = text
        if (ensureAgent()) return
        // Not startable yet: for the local engine, start the active model first.
        when (prefs.provider) {
            Provider.LOCAL -> {
                val model = models.byId(prefs.activeModelId) ?: models.models.value.firstOrNull()
                if (model == null) {
                    pendingPrompt = null
                    note("No model yet. Add a GGUF model on the Models screen.", ChatItem.Notice.Kind.ERROR)
                    screen.value = Screen.MODELS
                } else if (engine.state.value !is EngineState.Starting) {
                    startEngine(model)
                }
            }
            else -> {
                pendingPrompt = null
                note("Set up the ${prefs.provider.label} provider in Settings first.", ChatItem.Notice.Kind.ERROR)
            }
        }
    }

    private fun handleAppCommand(text: String): Boolean {
        val word = text.removePrefix("/").substringBefore(' ')
        if (!text.startsWith("/")) return false
        when (word) {
            "new", "clear" -> newChat()
            "resume" -> { refreshSessions(); screen.value = Screen.SESSIONS }
            "models" -> screen.value = Screen.MODELS
            "model" -> {
                val arg = text.substringAfter(' ', "").trim()
                if (arg.isEmpty()) screen.value = Screen.MODELS
                else {
                    val m = models.models.value.firstOrNull { it.alias == arg.removePrefix("local/") || it.name == arg }
                    if (m != null) startEngine(m) else if (agent.running) agent.setModel(arg) else note("No model $arg", ChatItem.Notice.Kind.ERROR)
                }
            }
            "mcp-servers" -> screen.value = Screen.MCP
            "settings" -> screen.value = Screen.SETTINGS
            "logs" -> screen.value = Screen.LOGS
            "exit", "quit" -> { stopAgent(); }
            else -> return false
        }
        return true
    }

    fun cancel() = agent.cancel()

    fun answerPermission(decision: String) {
        val p = _chat.value.permission ?: return
        _chat.value = _chat.value.copy(permission = null)
        agent.permission(p.id, decision)
    }

    fun answerQuestion(text: String) {
        val q = _chat.value.question ?: return
        _chat.value = _chat.value.copy(question = null)
        agent.answer(q.id, text)
    }

    fun stopBackground(id: String?) = agent.stopBackground(id)

    /** A fresh chat, in [projectId] (default: the current project), optionally incognito. */
    fun newChat(projectId: String? = currentProjectId.value, incognito: Boolean = false) {
        stopAgent()
        _chat.value = ChatState()
        currentChatId.value = null
        currentProjectId.value = projectId
        this.incognito.value = incognito
        screen.value = Screen.CHAT
        ensureAgent()
    }

    /** Opens a chat from the sidebar: its history from the session file, then claw resumes it. */
    fun openChat(entry: dev.anclaw.agent.ChatEntry) {
        if (entry.id == currentChatId.value) { screen.value = Screen.CHAT; return }
        if (_chat.value.busy) toast.value = "Stopped the reply that was running in the previous chat"
        viewModelScope.launch {
            val path = entry.sessionPath?.takeIf { File(it).isFile }
            val restored = if (path != null) withContext(Dispatchers.IO) { Sessions.load(path) } else ChatState()
            stopAgent()
            incognito.value = false
            currentChatId.value = entry.id
            currentProjectId.value = entry.projectId
            _chat.value = restored.copy(sessionPath = path)
            resumeNext = path
            screen.value = Screen.CHAT
            // Never restart a model that is loading or loaded: that killed the session on slow phones.
            if (!ensureAgent() && prefs.provider == Provider.LOCAL &&
                (engine.state.value is EngineState.Stopped || engine.state.value is EngineState.Failed)) {
                models.byId(prefs.activeModelId)?.let { startEngine(it) }
            }
        }
    }

    fun deleteChats(ids: Set<String>) {
        if (currentChatId.value in ids) newChat()
        index.delete(ids)
        toast.value = if (ids.size == 1) "Chat deleted" else "${ids.size} chats deleted"
    }

    fun moveChats(ids: Set<String>, projectId: String?) {
        index.move(ids, projectId)
        if (currentChatId.value in ids) currentProjectId.value = projectId
        toast.value = "Moved to " + (index.project(projectId)?.name ?: "no project")
    }

    fun renameChat(id: String, title: String) = index.rename(id, title)

    fun createProject(name: String, workspace: String?, instructions: String) {
        val p = index.createProject(name, workspace, instructions)
        newChat(projectId = p.id)
    }

    fun updateProject(p: dev.anclaw.agent.Project) = index.updateProject(p)

    fun deleteProject(id: String) {
        index.deleteProject(id)
        if (currentProjectId.value == id) currentProjectId.value = null
    }

    /** settings.json from the app's state, including the phone-tools server when on. */
    private fun syncSettings() {
        val tools = app.phoneTools
        clawSettings.extraServers = if (prefs.phoneTools && tools.port > 0) mapOf(
            "android" to dev.anclaw.agent.McpServer("android", "http", url = "http://127.0.0.1:${tools.port}/mcp",
                headers = mapOf("Authorization" to "Bearer ${tools.token}"), builtin = true)
        ) else emptyMap()
        if (!prefs.phoneTools) clawSettings.removeEntry("android")
        clawSettings.sync(prefs, workspace())
    }

    fun stopAgent() {
        val old = agent
        agent = ClawAgent(paths) {} // detach: events of the old process are ignored from here on
        old.stop()
        agentModel = null
        _chat.value = _chat.value.copy(ready = false, busy = false, permission = null, question = null)
    }

    /** Restarts the agent so settings/MCP/provider changes apply; the conversation is kept. */
    fun restartAgent() {
        val session = _chat.value.sessionPath
        stopAgent()
        resumeNext = session
        _chat.value = _chat.value.copy(ready = false)
        ensureAgent()
    }

    fun refreshSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            val workspaces = listOf(workspace(), paths.defaultWorkspace) +
                index.projects.value.map { File(it.workspace) } + listOfNotNull(prefs.workspace.takeIf { it.isNotBlank() }?.let(::File))
            val roots = listOf(File(paths.clawHome, "sessions")) + workspaces.map { File(it, ".claw/sessions") }
            val found = Sessions.list(roots.distinct().filter { it.isDirectory })
            _sessions.value = found
            index.adopt(found)
        }
    }

    fun resume(summary: SessionSummary) {
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) { Sessions.load(summary.path) }
            stopAgent()
            _chat.value = restored.copy(sessionPath = summary.path)
            resumeNext = summary.path
            screen.value = Screen.CHAT
            if (!ensureAgent() && prefs.provider == Provider.LOCAL) {
                (models.byId(prefs.activeModelId))?.let { startEngine(it) }
            }
        }
    }

    fun deleteSession(summary: SessionSummary) {
        File(summary.path).delete()
        refreshSessions()
    }

    // ------------------------------------------------------------------ engine / models

    fun startEngine(model: ModelEntry) {
        prefs.activeModelId = model.id
        viewModelScope.launch(Dispatchers.IO) { engine.start(model, prefs) }
    }

    fun stopEngine() {
        stopAgent()
        viewModelScope.launch(Dispatchers.IO) { engine.stop() }
    }

    fun addModelUri(uri: Uri, copy: Boolean) = viewModelScope.launch {
        models.addFromUri(uri, copy)
            .onSuccess { toast.value = if (it.imported) "Imported ${it.name}" else "Added ${it.name} (used in place)" }
            .onFailure { toast.value = it.message }
    }

    fun addModelPath(path: String) = viewModelScope.launch {
        models.addPath(path).onSuccess { toast.value = "Added ${it.name}" }.onFailure { toast.value = it.message }
    }

    fun downloadModel(repo: String, file: String) = viewModelScope.launch {
        models.downloadFromHf(repo.trim(), file.trim(), prefs.hfToken)
            .onSuccess { toast.value = "Downloaded ${it.name}" }
            .onFailure { toast.value = it.message }
    }

    fun refreshModels() = viewModelScope.launch { models.refreshInfo() }

    fun optimizeModel(model: ModelEntry) = viewModelScope.launch {
        models.optimizeQ40(model, dev.anclaw.engine.LlamaServer.performanceCores())
            .onSuccess { toast.value = "Created ${it.name}: load it for faster prompts" }
            .onFailure { toast.value = it.message }
    }

    /** Copies a picked file into `<workspace>/attachments` and returns the `@path` to mention. */
    suspend fun attach(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = app.contentResolver
            val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?.replace(Regex("[^A-Za-z0-9._-]"), "_") ?: "file-${System.currentTimeMillis()}"
            val dir = File(workspace(), "attachments").apply { mkdirs() }
            val dest = File(dir, name)
            resolver.openInputStream(uri)!!.use { input -> dest.outputStream().use { input.copyTo(it) } }
            "@attachments/$name"
        }.onFailure { toast.value = "Could not attach: ${it.message}" }.getOrNull()
    }

    /** Switches claw's permission mode for this and later chats. */
    fun setPermissionMode(mode: String) {
        if (prefs.permissionMode == mode) return
        prefs.permissionMode = mode
        if (agent.running) restartAgent()
    }

    /** Title of the open chat: its first prompt. */
    val chatTitle: String
        get() = _chat.value.items.firstOrNull { it is ChatItem.User }?.let { (it as ChatItem.User).text.lineSequence().first().take(60) } ?: "New chat"

    private fun note(text: String, kind: ChatItem.Notice.Kind) {
        synchronized(this) {
            val s = _chat.value
            _chat.value = s.copy(items = s.items + ChatItem.Notice(s.nextKey, text, kind), nextKey = s.nextKey + 1)
        }
    }

    override fun onCleared() {
        agent.stop()
        super.onCleared()
    }
}
