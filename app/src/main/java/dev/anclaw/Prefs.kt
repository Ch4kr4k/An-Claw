package dev.anclaw

import android.content.Context

/** Which model backend claw talks to. */
enum class Provider(val label: String) {
    LOCAL("On-device (llama.cpp)"),
    OPENAI_COMPAT("OpenAI-compatible server"),
    ANTHROPIC("Anthropic API"),
}

const val DEFAULT_HIDDEN_TOOLS =
    "X11,Browser,SocialMedia,vision_analyze,COM,PowerShell,REPL,ExecuteCode,ExternalAgent,HawkScan," +
    "WorkerCreate,WorkerGet,WorkerObserve,WorkerResolveTrust,WorkerAwaitReady,WorkerSendPrompt,WorkerRestart," +
    "WorkerTerminate,WorkerObserveCompletion,TeamCreate,TeamDelete,RunTaskPacket,GitStatus,GitDiff,GitLog,GitShow," +
    "GitBlame,EnterWorktree,ExitWorktree,LSP,Diagnostics,image_generate,text_to_speech,PushNotification,SendFeedback," +
    "RemoteTrigger,GoogleDork,McpAuth,Pty"

/** App preferences (SharedPreferences). Tokens stay in app-private storage. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("anclaw", Context.MODE_PRIVATE)

    private fun str(key: String, default: String) = sp.getString(key, default) ?: default
    private fun put(key: String, value: String) = sp.edit().putString(key, value).apply()
    private fun int(key: String, default: Int) = sp.getInt(key, default)
    private fun putInt(key: String, value: Int) = sp.edit().putInt(key, value).apply()

    var activeModelId: String
        get() = str("activeModel", "")
        set(v) = put("activeModel", v)

    // ---- llama-server parameters
    var contextSize: Int
        get() = int("ctx", 16384)
        set(v) = putInt("ctx", v)
    /** 0 = automatic (big cores only). */
    var threads: Int
        get() = int("threads", 0)
        set(v) = putInt("threads", v)
    var batch: Int
        get() = int("batch", 512)
        set(v) = putInt("batch", v)
    var ubatch: Int
        get() = int("ubatch", 512)
        set(v) = putInt("ubatch", v)
    /** on | off | auto */
    var flashAttn: String
        get() = str("fa", "auto")
        set(v) = put("fa", v)
    /** f16 | q8_0 | q4_0 */
    var kvCacheType: String
        get() = str("kv", "q8_0")
        set(v) = put("kv", v)
    var port: Int
        get() = int("port", 8086)
        set(v) = putInt("port", v)
    /** Extra llama-server arguments (e.g. llama-abominated's --moe-n-expert 6). */
    var extraArgs: String
        get() = str("extraArgs", "")
        set(v) = put("extraArgs", v)
    var mmap: Boolean
        get() = sp.getBoolean("mmap", true)
        set(v) = sp.edit().putBoolean("mmap", v).apply()

    // ---- agent
    var provider: Provider
        get() = runCatching { Provider.valueOf(str("provider", Provider.LOCAL.name)) }.getOrDefault(Provider.LOCAL)
        set(v) = put("provider", v.name)
    var remoteBaseUrl: String
        get() = str("remoteBaseUrl", "")
        set(v) = put("remoteBaseUrl", v)
    var remoteApiKey: String
        get() = str("remoteApiKey", "")
        set(v) = put("remoteApiKey", v)
    var remoteModel: String
        get() = str("remoteModel", "")
        set(v) = put("remoteModel", v)
    var anthropicKey: String
        get() = str("anthropicKey", "")
        set(v) = put("anthropicKey", v)
    var anthropicModel: String
        get() = str("anthropicModel", "claude-sonnet-5-5")
        set(v) = put("anthropicModel", v)
    /** read-only | workspace-write | danger-full-access | prompt */
    var permissionMode: String
        get() = str("permissionMode", "workspace-write")
        set(v) = put("permissionMode", v)
    var workspace: String
        get() = str("workspace", "")
        set(v) = put("workspace", v)
    /**
     * Tools hidden from the model (CLAW_HIDE_TOOLS): desktop-only or needing
     * programs a phone lacks. Each one dropped saves prompt tokens, which a
     * phone CPU reads at ~20–40 tokens/s.
     */
    var hiddenTools: String
        get() = str("hiddenTools", DEFAULT_HIDDEN_TOOLS)
        set(v) = put("hiddenTools", v)
    /**
     * claw's auto memory and after-turn learning review. Off by default: on a
     * phone the review is a second full model request after every turn, and the
     * memory instructions add ~900 prompt tokens.
     */
    var autoMemory: Boolean
        get() = sp.getBoolean("autoMemory", false)
        set(v) = sp.edit().putBoolean("autoMemory", v).apply()
    /** Save/restore the processed system prompt (KV cache) across chats and restarts. */
    var persistCache: Boolean
        get() = sp.getBoolean("persistCache", true)
        set(v) = sp.edit().putBoolean("persistCache", v).apply()
    /** Expose the on-device "android" MCP server (phone tools) to the agent. */
    var phoneTools: Boolean
        get() = sp.getBoolean("phoneTools", true)
        set(v) = sp.edit().putBoolean("phoneTools", v).apply()
    var showThinking: Boolean
        get() = sp.getBoolean("showThinking", true)
        set(v) = sp.edit().putBoolean("showThinking", v).apply()

    // ---- tokens (passed to claw + the MCP hub as env)
    var hfToken: String
        get() = str("hfToken", "")
        set(v) = put("hfToken", v)
    var githubToken: String
        get() = str("githubToken", "")
        set(v) = put("githubToken", v)
}
