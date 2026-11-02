package dev.anclaw.engine

import dev.anclaw.Paths
import dev.anclaw.Prefs
import dev.anclaw.model.ModelEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

sealed interface EngineState {
    data object Stopped : EngineState
    data class Starting(val model: ModelEntry, val detail: String = "loading model…") : EngineState
    data class Ready(val model: ModelEntry, val port: Int, val ctx: Int, val apiKey: String) : EngineState
    data class Failed(val message: String) : EngineState
}

/**
 * Runs llama-server (built from llama-abominated, shipped as
 * libllama_server.so) as a child process on 127.0.0.1, which claw reaches as
 * an OpenAI-compatible `local/<alias>` model.
 */
class LlamaServer(private val paths: Paths) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<EngineState>(EngineState.Stopped)
    val state: StateFlow<EngineState> = _state

    private val logLines = ArrayDeque<String>()
    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log

    @Volatile private var process: Process? = null
    private val slotsDir = File(paths.tmp.parentFile, "slots").apply { mkdirs() }
    @Volatile private var prefixSaved = false
    @Volatile private var persist = true
    private var watcher: Job? = null

    val isAvailable get() = paths.llamaServer.canExecute()

    fun start(model: ModelEntry, prefs: Prefs) {
        stop()
        if (!isAvailable) {
            _state.value = EngineState.Failed("libllama_server.so is missing from this build (run native/build-native.sh llama)")
            return
        }
        if (!model.exists) {
            _state.value = EngineState.Failed("Cannot read ${model.path}. Grant All-files access or import the model.")
            return
        }
        val port = prefs.port
        val ctx = prefs.contextSize
        val threads = if (prefs.threads > 0) prefs.threads else performanceCores()
        // Other apps can reach 127.0.0.1 too: only holders of this per-launch key may use the model.
        val apiKey = java.util.UUID.randomUUID().toString()
        val args = buildList {
            add(paths.llamaServer.path)
            addAll(listOf("-m", model.path, "--alias", model.alias))
            addAll(listOf("--host", "127.0.0.1", "--port", port.toString()))
            addAll(listOf("-c", ctx.toString(), "-np", "1"))
            // Prompt processing too: measured on a Snapdragon 8+ Gen 1, adding the little cores made it 26% slower.
            addAll(listOf("-t", threads.toString(), "-tb", threads.toString()))
            addAll(listOf("-b", prefs.batch.toString(), "-ub", prefs.ubatch.toString()))
            addAll(listOf("-fa", prefs.flashAttn))
            if (prefs.kvCacheType != "f16") addAll(listOf("-ctk", prefs.kvCacheType, "-ctv", prefs.kvCacheType))
            if (!prefs.mmap) add("--no-mmap")
            // Tool calling needs the model's chat template.
            add("--jinja")
            templateFile(model)?.let { addAll(listOf("--chat-template-file", it.path)) }
            add("--no-webui")
            // Reuse cached KV across claw's requests when only part of the prompt changed (after compaction).
            addAll(listOf("--cache-reuse", "256"))
            addAll(listOf("--slot-save-path", slotsDir.path + "/"))
            addAll(listOf("--api-key", apiKey))
            addAll(splitArgs(prefs.extraArgs))
        }
        persist = prefs.persistCache
        _state.value = EngineState.Starting(model)
        appendLog("$ " + args.joinToString(" ") { if (it == apiKey) "<key>" else if (it.contains(' ')) "'$it'" else it })
        val proc = try {
            ProcessBuilder(args)
                .directory(paths.tmp)
                .redirectErrorStream(true)
                .apply {
                    environment()["LD_LIBRARY_PATH"] = paths.nativeDir.path
                    environment()["TMPDIR"] = paths.tmp.path
                    environment()["HOME"] = paths.home.path
                }
                .start()
        } catch (e: Exception) {
            _state.value = EngineState.Failed("Could not start llama-server: ${e.message}")
            return
        }
        process = proc
        // Log reader; also turns "load progress" lines into status text.
        scope.launch {
            proc.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    appendLog(line)
                    val s = _state.value
                    if (s is EngineState.Starting && (line.contains("load_tensors") || line.contains("llama_context") || line.contains("warming up"))) {
                        _state.value = s.copy(detail = line.substringAfter(": ").take(80))
                    }
                }
            }
        }
        watcher = scope.launch {
            while (isActive) {
                if (!proc.isAlive) {
                    val code = proc.exitValue()
                    if (process === proc) {
                        process = null
                        _state.value = EngineState.Failed("llama-server exited ($code): " + lastError())
                    }
                    return@launch
                }
                if (_state.value is EngineState.Starting && healthy(port)) {
                    val ready = EngineState.Ready(model, port, ctx, apiKey)
                    if (persist) {
                        _state.value = EngineState.Starting(model, "restoring the prompt cache…")
                        restoreSlot(ready)
                    }
                    prefixSaved = false
                    _state.value = ready
                }
                delay(if (_state.value is EngineState.Ready) 3000 else 400)
            }
        }
    }

    fun stop() {
        watcher?.cancel()
        val proc = process ?: run { if (_state.value !is EngineState.Failed) _state.value = EngineState.Stopped; return }
        process = null
        proc.destroy()
        if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly()
        _state.value = EngineState.Stopped
        appendLog("— stopped —")
    }

    /** The chat template file to use instead of the GGUF's, if any. */
    private fun templateFile(model: ModelEntry): File? {
        val choice = when (model.template) {
            "auto" -> if (model.info?.needsToolTemplate == true) "qwen3-tools" else return null
            "own" -> return null
            else -> model.template
        }
        if (choice.startsWith("/")) return File(choice).takeIf { it.canRead() }
        val out = File(paths.tmp.parentFile, "templates/$choice.jinja")
        return runCatching {
            out.parentFile?.mkdirs()
            paths.assets.open("templates/$choice.jinja").use { input -> out.outputStream().use { input.copyTo(it) } }
            appendLog("chat template: bundled $choice")
            out
        }.getOrNull()
    }

    /** Slot cache file for a model + context size (a cache only fits the setup that made it). */
    private fun slotFile(r: EngineState.Ready) = "prefix-${r.model.alias}-${r.ctx}.bin"

    private fun slotAction(r: EngineState.Ready, action: String): Boolean = runCatching {
        val c = URL("http://127.0.0.1:${r.port}/slots/0?action=$action").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 2000; c.readTimeout = 120_000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Authorization", "Bearer ${r.apiKey}")
        c.outputStream.use { it.write("{\"filename\":\"${slotFile(r)}\"}".toByteArray()) }
        try { c.responseCode == 200 } finally { c.disconnect() }
    }.getOrDefault(false)

    private fun restoreSlot(r: EngineState.Ready) {
        if (!File(slotsDir, slotFile(r)).isFile) return
        val ok = slotAction(r, "restore")
        appendLog(if (ok) "prompt cache restored from ${slotFile(r)}" else "prompt cache restore failed")
        prefixSaved = ok
    }

    /** After the first finished turn: write the processed prompt (system + tools) to disk, once per engine run. */
    fun savePrefixCacheOnce() {
        val r = _state.value as? EngineState.Ready ?: return
        if (prefixSaved) return
        prefixSaved = true
        val ok = slotAction(r, "save")
        appendLog(if (ok) "prompt cache saved to ${slotFile(r)}" else "prompt cache save failed")
    }

    /** Deletes saved prompt caches (e.g. after changing the system prompt). */
    fun clearPromptCaches() {
        slotsDir.listFiles()?.forEach { it.delete() }
    }

    private fun healthy(port: Int): Boolean = runCatching {
        val c = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
        c.connectTimeout = 500; c.readTimeout = 1000
        try { c.responseCode == 200 } finally { c.disconnect() }
    }.getOrDefault(false)

    private fun lastError(): String =
        synchronized(logLines) {
            logLines.lastOrNull { it.contains("error", true) || it.contains("failed", true) }
                ?: logLines.lastOrNull() ?: "no output"
        }.take(300)

    private fun appendLog(line: String) {
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > 600) logLines.removeFirst()
            _log.value = logLines.joinToString("\n")
        }
    }

    companion object {
        /**
         * Cores outside the slowest cluster: decode on little cores mostly adds
         * contention. Falls back to half of all cores.
         */
        fun performanceCores(): Int {
            val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { cpu ->
                runCatching {
                    File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
                }.getOrNull()
            }
            if (freqs.isEmpty()) return (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
            val min = freqs.min()
            val big = freqs.count { it > min }
            return (if (big == 0) freqs.size else big).coerceIn(1, 8)
        }

        /** Splits a command line on spaces, honouring simple quotes. */
        fun splitArgs(text: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var quote: Char? = null
            for (ch in text) {
                when {
                    quote != null && ch == quote -> quote = null
                    quote == null && (ch == '"' || ch == '\'') -> quote = ch
                    quote == null && ch.isWhitespace() -> if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
                    else -> cur.append(ch)
                }
            }
            if (cur.isNotEmpty()) out += cur.toString()
            return out
        }
    }
}
