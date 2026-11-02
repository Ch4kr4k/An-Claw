package dev.anclaw.agent

import dev.anclaw.Paths
import dev.anclaw.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One MCP server as configured in claw's settings.json. */
data class McpServer(
    val name: String,
    /** stdio | http | sse | ws */
    val type: String,
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    /** Managed by the app (the bundled hub); not editable. */
    val builtin: Boolean = false,
) {
    /**
     * claw runs only stdio MCP servers, so a remote (streamable HTTP) server is
     * stored as the bundled hub in `--bridge` mode pointing at its URL.
     */
    fun toJson(hub: String): JSONObject = JSONObject().apply {
        if (type == "stdio") {
            put("command", command)
            if (args.isNotEmpty()) put("args", JSONArray(args))
            if (env.isNotEmpty()) put("env", JSONObject(env))
        } else {
            put("command", hub)
            put("args", JSONArray(listOf("--bridge", url) + headers.flatMap { (k, v) -> listOf("--header", "$k: $v") }))
        }
    }

    companion object {
        fun fromJson(name: String, o: JSONObject, enabled: Boolean, builtin: Boolean): McpServer {
            val args = o.optJSONArray("args")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            if (o.optString("command").endsWith("/${ClawSettings.HUB_EXE}") && args.firstOrNull() == "--bridge") {
                val headers = args.drop(2).chunked(2).filter { it.size == 2 && it[0] == "--header" }
                    .mapNotNull { it[1].split(':', limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0].trim() to p[1].trim() } }
                    .toMap()
                return McpServer(name, "http", url = args.getOrElse(1) { "" }, headers = headers, enabled = enabled, builtin = builtin)
            }
            return fromRaw(name, o, enabled, builtin)
        }

        private fun fromRaw(name: String, o: JSONObject, enabled: Boolean, builtin: Boolean) = McpServer(
            name = name,
            type = o.optString("type").ifEmpty { if (o.has("url")) "http" else "stdio" },
            command = o.optString("command"),
            args = o.optJSONArray("args")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            env = o.optJSONObject("env")?.toStringMap() ?: emptyMap(),
            url = o.optString("url"),
            headers = o.optJSONObject("headers")?.toStringMap() ?: emptyMap(),
            enabled = enabled,
            builtin = builtin,
        )
    }
}

fun JSONObject.toStringMap(): Map<String, String> = keys().asSequence().associateWith { optString(it) }

/** Remote MCP servers offered with one tap. */
object McpPresets {
    data class Preset(val name: String, val title: String, val url: String, val tokenKind: String?)

    val all = listOf(
        Preset("huggingface", "Hugging Face (official remote MCP)", "https://huggingface.co/mcp", "hf"),
        Preset("github", "GitHub (official remote MCP)", "https://api.githubcopilot.com/mcp/", "github"),
        Preset("deepwiki", "DeepWiki (docs for any GitHub repo)", "https://mcp.deepwiki.com/mcp", null),
        Preset("context7", "Context7 (library docs)", "https://mcp.context7.com/mcp", null),
    )
}

/**
 * Keeps `~/.claw/settings.json` (claw's user settings, which it trusts fully)
 * in step with the app: sandbox off (no user namespaces on Android), the
 * permission mode, and the bundled MCP hub. Everything else in the file is
 * left as the user or claw wrote it.
 */
class ClawSettings(private val paths: Paths) {
    private val disabledFile = File(paths.home.parentFile, "mcp-disabled.json")
    private val _servers = MutableStateFlow<List<McpServer>>(emptyList())
    val servers: StateFlow<List<McpServer>> = _servers

    fun read(): JSONObject = runCatching { JSONObject(paths.settingsJson.readText()) }.getOrDefault(JSONObject())

    private fun write(root: JSONObject) {
        val tmp = File(paths.settingsJson.path + ".tmp")
        tmp.writeText(root.toString(2))
        tmp.renameTo(paths.settingsJson)
    }

    /** Raw text for the settings editor. */
    fun rawText(): String = if (paths.settingsJson.exists()) paths.settingsJson.readText() else "{}"

    fun saveRaw(text: String): Result<Unit> = runCatching {
        write(JSONObject(text)) // validates JSON
        reloadServers()
    }

    private fun disabled(): MutableMap<String, JSONObject> = runCatching {
        val o = JSONObject(disabledFile.readText())
        o.keys().asSequence().associateWith { o.getJSONObject(it) }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun saveDisabled(map: Map<String, JSONObject>) {
        disabledFile.writeText(JSONObject().apply { map.forEach { (k, v) -> put(k, v) } }.toString(2))
    }

    /** Writes the app-managed parts. Call before starting an agent. */
    fun sync(prefs: Prefs, workspace: File) {
        val root = read()
        root.put("sandbox", (root.optJSONObject("sandbox") ?: JSONObject()).put("enabled", false))
        val perms = root.optJSONObject("permissions") ?: JSONObject()
        perms.put("defaultMode", prefs.permissionMode)
        root.put("permissions", perms)
        val mcp = root.optJSONObject("mcpServers") ?: JSONObject()
        if (paths.mcpHub.canExecute() && HUB !in disabled()) {
            mcp.put(HUB, JSONObject()
                .put("command", paths.mcpHub.path)
                .put("env", JSONObject()
                    .put("ANCLAW_MODELS_DIR", paths.modelsDir.path)
                    .put("ANCLAW_WORKSPACE", workspace.path)))
        } else {
            mcp.remove(HUB)
        }
        // The native library dir changes on every app update: re-point bridge entries at the current hub.
        for (key in mcp.keys().asSequence().toList()) {
            val o = mcp.optJSONObject(key) ?: continue
            if (o.optString("command").endsWith("/$HUB_EXE")) o.put("command", paths.mcpHub.path)
        }
        extraServers.forEach { (name, server) -> mcp.put(name, server.toJson(paths.mcpHub.path)) }
        root.put("mcpServers", mcp)
        write(root)
        reloadServers()
    }

    /** App-provided servers (the on-device Android tools), refreshed on every sync. */
    var extraServers: Map<String, McpServer> = emptyMap()

    fun reloadServers() {
        val mcp = read().optJSONObject("mcpServers") ?: JSONObject()
        val active = mcp.keys().asSequence().map { McpServer.fromJson(it, mcp.getJSONObject(it), true, it == HUB || it in extraServers) }
        val off = disabled().map { (k, v) -> McpServer.fromJson(k, v, false, k == HUB) }
        _servers.value = (active.toList() + off).sortedWith(compareBy({ !it.builtin }, { it.name }))
    }

    fun upsert(server: McpServer) {
        val root = read()
        val mcp = root.optJSONObject("mcpServers") ?: JSONObject()
        mcp.put(server.name, server.toJson(paths.mcpHub.path))
        root.put("mcpServers", mcp)
        write(root)
        reloadServers()
    }

    /** Drops an entry from settings.json only (not the disabled list). */
    fun removeEntry(name: String) {
        val root = read()
        root.optJSONObject("mcpServers")?.remove(name) ?: return
        write(root)
    }

    fun remove(name: String) {
        val root = read()
        root.optJSONObject("mcpServers")?.remove(name)
        write(root)
        saveDisabled(disabled().apply { remove(name) })
        reloadServers()
    }

    fun setEnabled(name: String, enabled: Boolean) {
        val root = read()
        val mcp = root.optJSONObject("mcpServers") ?: JSONObject()
        val off = disabled()
        if (enabled) {
            val config = off.remove(name)
            if (config != null && name != HUB) mcp.put(name, config)
        } else {
            val config = mcp.optJSONObject(name) ?: JSONObject()
            mcp.remove(name)
            off[name] = config
        }
        root.put("mcpServers", mcp)
        write(root)
        saveDisabled(off)
        reloadServers() // the hub entry itself is rewritten by the next sync()
    }

    companion object {
        const val HUB = "anclaw-hub"
        const val HUB_EXE = "libanclaw_hub.so"
    }
}
