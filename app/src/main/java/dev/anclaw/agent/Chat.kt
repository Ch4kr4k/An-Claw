package dev.anclaw.agent

import org.json.JSONArray
import org.json.JSONObject

/** One row of the conversation as the app shows it. */
sealed interface ChatItem {
    val key: Long

    data class User(override val key: Long, val text: String, val queued: Boolean = false) : ChatItem
    data class Assistant(override val key: Long, val text: String) : ChatItem
    data class Thinking(override val key: Long, val text: String) : ChatItem
    data class Tool(
        override val key: Long,
        val id: String,
        val name: String,
        val input: String,
        val output: String? = null,
        val isError: Boolean = false,
    ) : ChatItem
    data class Notice(override val key: Long, val text: String, val kind: Kind) : ChatItem {
        enum class Kind { INFO, ERROR, BTW }
    }
}

data class PermissionRequest(
    val id: Long, val tool: String, val input: String, val command: String?, val reason: String?, val host: Boolean,
)

data class QuestionRequest(
    val id: Long, val question: String, val header: String?, val options: List<String>, val multiSelect: Boolean,
)

data class BackgroundJob(val id: String, val command: String, val seconds: Long)

data class ChatState(
    val items: List<ChatItem> = emptyList(),
    val ready: Boolean = false,
    val busy: Boolean = false,
    val exited: Boolean = false,
    val model: String = "",
    val cwd: String = "",
    val sessionPath: String? = null,
    val permissionMode: String = "",
    val contextTokens: Long = 0,
    val contextWindow: Long = 0,
    /** Prompt tokens the server has read, during a long prompt. */
    val progress: Pair<Long, Long>? = null,
    val permission: PermissionRequest? = null,
    val question: QuestionRequest? = null,
    val background: List<BackgroundJob> = emptyList(),
    val lastUsage: Pair<Long, Long>? = null,
    val nextKey: Long = 1,
)

/**
 * Folds claw `desktop-agent` events (one JSON object per stdout line, field
 * `ev`) into [ChatState]. Pure: no Android APIs, unit-tested.
 */
object ChatReducer {
    private fun ChatState.add(make: (Long) -> ChatItem) = copy(items = items + make(nextKey), nextKey = nextKey + 1)

    private fun ChatState.replaceLast(item: ChatItem) = copy(items = items.dropLast(1) + item)

    fun userPrompt(state: ChatState, text: String, queued: Boolean = false) =
        state.add { ChatItem.User(it, text, queued) }

    fun reduce(state: ChatState, ev: JSONObject): ChatState = when (ev.optString("ev")) {
        "ready" -> state.copy(
            ready = true,
            exited = false,
            model = ev.optString("model"),
            cwd = ev.optString("cwd"),
            sessionPath = ev.optString("session_path").takeIf { it.isNotEmpty() && it != "null" },
            permissionMode = ev.optString("permission_mode"),
        )
        "turn_start" -> state.copy(busy = true, progress = null)
        "text" -> {
            val text = ev.optString("text")
            when (val last = state.items.lastOrNull()) {
                is ChatItem.Assistant -> state.replaceLast(last.copy(text = last.text + text))
                else -> state.add { ChatItem.Assistant(it, text) }
            }.copy(progress = null)
        }
        "thinking" -> {
            val text = ev.optString("thinking").ifEmpty { ev.optString("text") }
            when (val last = state.items.lastOrNull()) {
                is ChatItem.Thinking -> state.replaceLast(last.copy(text = last.text + text))
                else -> state.add { ChatItem.Thinking(it, text) }
            }.copy(progress = null)
        }
        "tool_call" -> state.add {
            ChatItem.Tool(it, ev.optString("id"), ev.optString("name"), ev.optString("input"))
        }.copy(progress = null)
        "tool_result" -> {
            // Results arrive in call order: fill the oldest open call of that name.
            val name = ev.optString("name")
            val index = state.items.indexOfFirst { it is ChatItem.Tool && it.output == null && it.name == name }
                .takeIf { it >= 0 }
                ?: state.items.indexOfFirst { it is ChatItem.Tool && it.output == null }
            if (index < 0) {
                state.add { ChatItem.Tool(it, "", name, "", ev.optString("output"), ev.optBoolean("is_error")) }
            } else {
                val tool = state.items[index] as ChatItem.Tool
                val updated = tool.copy(output = ev.optString("output"), isError = ev.optBoolean("is_error"))
                state.copy(items = state.items.toMutableList().also { it[index] = updated })
            }
        }
        "permission" -> state.copy(permission = PermissionRequest(
            ev.optLong("id"), ev.optString("tool"), ev.optString("input"),
            ev.optStringOrNull("command"), ev.optStringOrNull("reason"), ev.optBoolean("host"),
        ))
        "question" -> state.copy(question = QuestionRequest(
            ev.optLong("id"), ev.optString("question"), ev.optStringOrNull("header"),
            ev.optJSONArray("options")?.strings() ?: emptyList(), ev.optBoolean("multi_select"),
        ))
        "turn_end" -> state.copy(busy = false, progress = null, permission = null, question = null).let { s ->
            if (ev.optString("status") == "stopped") s.add { ChatItem.Notice(it, "Stopped.", ChatItem.Notice.Kind.INFO) } else s
        }
        "context" -> state.copy(contextTokens = ev.optLong("tokens"), contextWindow = ev.optLong("window"))
        "model" -> state.copy(model = ev.optString("model"))
        "progress" -> state.copy(progress = ev.optLong("done") to ev.optLong("total"))
        "usage" -> state.copy(lastUsage = ev.optLong("input_tokens") to ev.optLong("output_tokens"))
        "error" -> state.add { ChatItem.Notice(it, ev.optString("message"), ChatItem.Notice.Kind.ERROR) }
        "notice", "info" -> state.add { ChatItem.Notice(it, ev.optString("text"), ChatItem.Notice.Kind.INFO) }
        "btw" -> state.add {
            ChatItem.Notice(it, "**/btw** ${ev.optString("question")}\n\n${ev.optString("answer")}", ChatItem.Notice.Kind.BTW)
        }
        "queued" -> {
            // The prompt was already shown when sent; mark it queued.
            val text = ev.optString("text")
            val index = state.items.indexOfLast { it is ChatItem.User && it.text == text }
            if (index < 0) state.add { ChatItem.User(it, text, queued = true) }
            else state.copy(items = state.items.toMutableList().also { it[index] = (it[index] as ChatItem.User).copy(queued = true) })
        }
        "background" -> state.copy(background = ev.optJSONArray("jobs")?.let { a ->
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                BackgroundJob(o.optString("id"), o.optString("command"), o.optLong("seconds"))
            }
        } ?: emptyList())
        "wake" -> state.add { ChatItem.Notice(it, "A background job reported; claw is looking at it.", ChatItem.Notice.Kind.INFO) }
        "exit" -> state.copy(ready = false, busy = false, exited = true, permission = null, question = null)
        else -> state
    }

    /**
     * Rebuilds the visible conversation from a claw session file (JSONL with
     * `{"type":"message","message":{"role":…,"blocks":[…]}}` lines), for resume.
     */
    fun fromSession(lines: Sequence<String>): ChatState {
        var state = ChatState()
        val toolNames = HashMap<String, String>()
        for (line in lines) {
            val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (o.optString("type") != "message") continue
            val msg = o.optJSONObject("message") ?: continue
            val role = msg.optString("role")
            val blocks = msg.optJSONArray("blocks") ?: continue
            for (i in 0 until blocks.length()) {
                val b = blocks.optJSONObject(i) ?: continue
                state = when (b.optString("type")) {
                    "text" -> {
                        val text = if (role == "user") cleanPrompt(b.optString("text")) else b.optString("text")
                        if (text.isBlank()) state
                        else if (role == "user") userPrompt(state, text)
                        else state.add { ChatItem.Assistant(it, text) }
                    }
                    "thinking" -> state.add { ChatItem.Thinking(it, b.optString("thinking").ifEmpty { b.optString("text") }) }
                    "tool_use" -> {
                        val id = b.optString("id")
                        toolNames[id] = b.optString("name")
                        state.add { ChatItem.Tool(it, id, b.optString("name"), b.optString("input")) }
                    }
                    "tool_result" -> {
                        val id = b.optString("tool_use_id")
                        val output = b.optString("output").ifEmpty { b.optJSONArray("content")?.toString() ?: "" }
                        val index = state.items.indexOfLast { it is ChatItem.Tool && it.id == id }
                        if (index < 0) state
                        else state.copy(items = state.items.toMutableList().also {
                            it[index] = (it[index] as ChatItem.Tool).copy(output = output, isError = b.optBoolean("is_error"))
                        })
                    }
                    else -> state
                }
            }
        }
        return state
    }
}

fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }
