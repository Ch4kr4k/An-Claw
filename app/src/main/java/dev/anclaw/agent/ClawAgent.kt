package dev.anclaw.agent

import dev.anclaw.Paths
import org.json.JSONObject
import java.io.File
import java.io.Writer
import java.util.concurrent.TimeUnit

/** How to launch claw. */
data class AgentLaunch(
    val model: String,
    val workspace: File,
    val env: Map<String, String>,
    val resume: String? = null,
    val incognito: Boolean = false,
)

/**
 * One `claw desktop-agent` process: the same session engine as claw in a
 * terminal (tools, slash commands, MCP, skills, memory, checkpoints), talking
 * JSON lines on stdin/stdout. See claw-code's desktop_agent.rs for the protocol.
 */
class ClawAgent(
    private val paths: Paths,
    private val onEvent: (JSONObject) -> Unit,
) {
    @Volatile private var process: Process? = null
    private var stdin: Writer? = null

    val running get() = process?.isAlive == true

    fun start(launch: AgentLaunch) {
        stop()
        launch.workspace.mkdirs()
        val args = buildList {
            add(paths.claw.path)
            addAll(listOf("--model", launch.model))
            add("desktop-agent")
            launch.resume?.let { addAll(listOf("--resume", it)) }
            if (launch.incognito) add("--incognito")
        }
        val stderr = File(paths.logs, "claw-agent.log")
        if (stderr.length() > 4 shl 20) stderr.delete()
        val proc = ProcessBuilder(args)
            .directory(launch.workspace)
            .redirectError(ProcessBuilder.Redirect.appendTo(stderr))
            .apply {
                val env = environment()
                env["HOME"] = paths.home.path
                env["TMPDIR"] = paths.tmp.path
                // Toybox/mksh from the system, plus our executables.
                env["PATH"] = listOf(paths.nativeDir.path, "/system/bin", "/system/xbin", "/vendor/bin").joinToString(":")
                env["SHELL"] = "/system/bin/sh"
                env["TERM"] = "dumb"
                env["NO_COLOR"] = "1"
                env["LD_LIBRARY_PATH"] = paths.nativeDir.path
                // The app runs llama-server itself.
                env["CLAW_MANAGE_SERVER"] = "0"
                env.putAll(launch.env)
            }
            .start()
        process = proc
        stdin = proc.outputStream.bufferedWriter()
        Thread({
            proc.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    // claw's own notices are plain text; events are JSON objects with "ev".
                    if (!line.startsWith("{")) return@forEach
                    val ev = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                    if (ev.has("ev")) onEvent(ev)
                }
            }
            onEvent(JSONObject().put("ev", "exit").put("code", runCatching { proc.waitFor() }.getOrDefault(-1)))
        }, "claw-events").apply { isDaemon = true }.start()
    }

    @Synchronized
    private fun send(cmd: JSONObject) {
        val w = stdin ?: return
        runCatching {
            w.write(cmd.toString())
            w.write("\n")
            w.flush()
        }
    }

    fun prompt(text: String) = send(JSONObject().put("cmd", "prompt").put("text", text))
    fun cancel() = send(JSONObject().put("cmd", "cancel"))
    fun permission(id: Long, decision: String) =
        send(JSONObject().put("cmd", "permission").put("id", id).put("decision", decision))
    fun answer(id: Long, text: String) = send(JSONObject().put("cmd", "answer").put("id", id).put("text", text))
    fun setModel(model: String) = send(JSONObject().put("cmd", "model").put("model", model))
    fun rewind(prompt: Int) = send(JSONObject().put("cmd", "rewind").put("prompt", prompt))
    fun stopBackground(id: String?) = send(JSONObject().put("cmd", "stop_background").put("id", id ?: JSONObject.NULL))

    fun stop() {
        val proc = process ?: return
        send(JSONObject().put("cmd", "shutdown"))
        runCatching { stdin?.close() }
        stdin = null
        process = null
        Thread {
            if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly()
        }.start()
    }

    /** Last lines of claw's stderr log, for error reports. */
    fun logTail(lines: Int = 40): String =
        runCatching { File(paths.logs, "claw-agent.log").readLines().takeLast(lines).joinToString("\n") }.getOrDefault("")
}
