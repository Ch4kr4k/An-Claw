package dev.anclaw.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A chat as the sidebar lists it. Created when the first message is sent, before claw saves anything. */
data class ChatEntry(
    val id: String,
    val title: String,
    val sessionPath: String? = null,
    val projectId: String? = null,
    val updatedMs: Long = System.currentTimeMillis(),
)

/** A project: its own working folder (claw's cwd) and standing instructions (written to CLAW.md there). */
data class Project(
    val id: String,
    val name: String,
    val workspace: String,
    val instructions: String = "",
)

/** The app's list of chats and projects (`files/chats.json`). Incognito chats are never added. */
class ChatIndex(private val file: File, private val projectsRoot: File) {
    private val _chats = MutableStateFlow<List<ChatEntry>>(emptyList())
    val chats: StateFlow<List<ChatEntry>> = _chats
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects

    init { load() }

    @Synchronized
    private fun load() = runCatching {
        val root = JSONObject(file.readText())
        _chats.value = root.optJSONArray("chats").objects().map {
            ChatEntry(it.getString("id"), it.optString("title"), it.optString("session").ifEmpty { null },
                it.optString("project").ifEmpty { null }, it.optLong("updated"))
        }
        // Drop duplicates left by an older version (same session listed twice).
        _chats.value = _chats.value.let { list ->
            val keep = HashSet<String>()
            list.filter { e -> e.sessionPath == null || keep.add(canon(e.sessionPath)!!) }
        }
        _projects.value = root.optJSONArray("projects").objects().map {
            Project(it.getString("id"), it.getString("name"), it.getString("workspace"), it.optString("instructions"))
        }
    }

    /** One spelling per file: /data/user/0/… and /data/data/… are the same directory. */
    private fun canon(path: String?): String? = path?.let { runCatching { File(it).canonicalPath }.getOrDefault(it) }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    @Synchronized
    private fun save() {
        val root = JSONObject()
            .put("chats", JSONArray(_chats.value.map {
                JSONObject().put("id", it.id).put("title", it.title).put("session", it.sessionPath ?: "")
                    .put("project", it.projectId ?: "").put("updated", it.updatedMs)
            }))
            .put("projects", JSONArray(_projects.value.map {
                JSONObject().put("id", it.id).put("name", it.name).put("workspace", it.workspace).put("instructions", it.instructions)
            }))
        val tmp = File(file.path + ".tmp")
        tmp.writeText(root.toString(1))
        tmp.renameTo(file)
    }

    fun get(id: String?) = _chats.value.firstOrNull { it.id == id }
    fun project(id: String?) = _projects.value.firstOrNull { it.id == id }
    fun bySession(path: String) = canon(path).let { p -> _chats.value.firstOrNull { canon(it.sessionPath) == p } }

    // ---------------------------------------------------------------- chats

    @Synchronized
    fun create(title: String, projectId: String?): ChatEntry {
        val entry = ChatEntry(UUID.randomUUID().toString(), title.lineSequence().first().take(80), projectId = projectId)
        _chats.value = listOf(entry) + _chats.value
        save()
        return entry
    }

    @Synchronized
    fun update(id: String, change: (ChatEntry) -> ChatEntry) {
        _chats.value = _chats.value.map { if (it.id == id) change(it) else it }.sortedByDescending { it.updatedMs }
        save()
    }

    fun rename(id: String, title: String) = update(id) { it.copy(title = title.trim().ifEmpty { it.title }) }

    @Synchronized
    fun move(ids: Set<String>, projectId: String?) {
        _chats.value = _chats.value.map { if (it.id in ids) it.copy(projectId = projectId) else it }
        save()
    }

    /** Removes chats and deletes their session files. */
    @Synchronized
    fun delete(ids: Set<String>, keepFiles: Boolean = false) {
        if (!keepFiles) _chats.value.filter { it.id in ids }.forEach { e -> e.sessionPath?.let { File(it).delete() } }
        _chats.value = _chats.value.filterNot { it.id in ids }
        save()
    }

    /** Adds sessions found on disk that the index does not know (from before the index, or from the terminal). */
    @Synchronized
    fun adopt(found: List<SessionSummary>) {
        val known = _chats.value.mapNotNull { canon(it.sessionPath) }.toSet()
        val fresh = found.filter { canon(it.path) !in known }.map {
            ChatEntry(UUID.randomUUID().toString(), it.title, it.path,
                _projects.value.firstOrNull { p -> File(p.workspace).canonicalPath == File(it.workspace).canonicalPath }?.id,
                it.updatedMs)
        }
        if (fresh.isEmpty()) return
        _chats.value = (_chats.value + fresh).sortedByDescending { it.updatedMs }
        save()
    }

    // ---------------------------------------------------------------- projects

    @Synchronized
    fun createProject(name: String, workspace: String?, instructions: String): Project {
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "project" }
        val dir = workspace?.takeIf { it.isNotBlank() } ?: File(projectsRoot, slug + "-" + UUID.randomUUID().toString().take(4)).path
        val p = Project(UUID.randomUUID().toString(), name.trim(), dir, instructions)
        writeInstructions(p)
        _projects.value = _projects.value + p
        save()
        return p
    }

    @Synchronized
    fun updateProject(p: Project) {
        writeInstructions(p)
        _projects.value = _projects.value.map { if (it.id == p.id) p else it }
        save()
    }

    /** Deletes the project; its chats move to "no project" (the folder is kept). */
    @Synchronized
    fun deleteProject(id: String) {
        _projects.value = _projects.value.filterNot { it.id == id }
        _chats.value = _chats.value.map { if (it.projectId == id) it.copy(projectId = null) else it }
        save()
    }

    private fun writeInstructions(p: Project) {
        val dir = File(p.workspace).apply { mkdirs() }
        val md = File(dir, "CLAW.md")
        if (p.instructions.isBlank()) {
            if (md.isFile && md.readText().startsWith(MARK)) md.delete()
        } else if (!md.exists() || md.readText().startsWith(MARK)) {
            // Only files the app wrote are replaced; a user's own CLAW.md is left alone.
            md.writeText("$MARK\n# ${p.name}\n\n${p.instructions.trim()}\n")
        }
    }

    private companion object {
        const val MARK = "<!-- written by an-claw: project instructions -->"
    }
}
