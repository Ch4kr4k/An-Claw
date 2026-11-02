package dev.anclaw.agent

import org.json.JSONObject
import java.io.File

data class SessionSummary(val path: String, val title: String, val workspace: String, val model: String?, val updatedMs: Long)

/** claw session files: `<workspace>/.claw/sessions/<hash>/<id>.jsonl` and `~/.claw/sessions`. */
/** User text without claw's injected `<system-reminder>` blocks. */
fun cleanPrompt(text: String): String =
    text.replace(Regex("<system-reminder>.*?</system-reminder>", RegexOption.DOT_MATCHES_ALL), "").trim()

object Sessions {
    fun list(roots: List<File>): List<SessionSummary> =
        roots.flatMap { root -> root.walkTopDown().maxDepth(3).filter { it.isFile && it.extension == "jsonl" }.toList() }
            .distinctBy { it.canonicalPath }
            .mapNotNull { summarize(it) }
            .sortedByDescending { it.updatedMs }

    private fun summarize(file: File): SessionSummary? {
        var workspace: String? = null
        var model: String? = null
        var title: String? = null
        runCatching {
            file.bufferedReader().useLines { lines ->
                for (line in lines.take(400)) {
                    val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    when (o.optString("type")) {
                        "session_meta" -> {
                            workspace = o.optString("workspace_root").ifEmpty { null }
                            model = o.optString("model").ifEmpty { null }
                        }
                        "prompt_history" -> if (title == null) title = o.optString("text").ifBlank { null }
                        "message" -> if (title == null) {
                            val m = o.optJSONObject("message")
                            if (m?.optString("role") == "user") {
                                val blocks = m.optJSONArray("blocks")
                                title = (0 until (blocks?.length() ?: 0)).asSequence()
                                    .mapNotNull { blocks!!.optJSONObject(it) }
                                    .firstOrNull { it.optString("type") == "text" }
                                    ?.optString("text")?.ifBlank { null }
                            }
                        }
                    }
                    if (title != null && workspace != null) break
                }
            }
        }
        val t = title?.let(::cleanPrompt)?.ifBlank { null } ?: return null
        return SessionSummary(
            file.path,
            t.lineSequence().first().take(90),
            workspace ?: file.parentFile?.parentFile?.parentFile?.parentFile?.path.orEmpty(),
            model,
            file.lastModified(),
        )
    }

    fun load(path: String): ChatState =
        runCatching { File(path).bufferedReader().useLines { ChatReducer.fromSession(it) } }.getOrDefault(ChatState())
}
