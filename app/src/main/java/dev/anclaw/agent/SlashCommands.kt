package dev.anclaw.agent

/**
 * Slash commands offered in the input box. All of them are claw's own: the
 * "session" ones run inside claw as in the terminal REPL, the "prompt" ones
 * expand to claw's built-in prompts (an-claw's desktop-agent patch), and the
 * "app" ones are handled by an-claw itself.
 */
object SlashCommands {
    enum class Kind { SESSION, PROMPT, APP }
    data class Command(val name: String, val args: String, val help: String, val kind: Kind)

    private fun s(name: String, args: String, help: String) = Command(name, args, help, Kind.SESSION)
    private fun p(name: String, args: String, help: String) = Command(name, args, help, Kind.PROMPT)
    private fun a(name: String, args: String, help: String) = Command(name, args, help, Kind.APP)

    val all: List<Command> = listOf(
        // app
        a("new", "", "Start a new chat"),
        a("resume", "", "Open a saved session"),
        a("model", "[model]", "Switch the model (picker when empty)"),
        a("models", "", "Manage GGUF models and the engine"),
        a("mcp-servers", "", "Configure MCP servers and tokens"),
        a("settings", "", "App settings"),
        a("logs", "", "Engine and agent logs"),
        // session (claw REPL commands)
        s("help", "", "claw's command help"),
        s("status", "", "Session status: model, permissions, context"),
        s("compact", "[instructions]", "Summarize the conversation to free context"),
        s("cost", "", "Token usage and cost of this session"),
        s("usage", "", "Usage details"),
        s("context", "[all]", "What fills the context window"),
        s("memory", "", "Auto-memory status and files"),
        s("diff", "", "Changes made in the workspace"),
        s("undo", "", "Undo the last request's file changes"),
        s("rewind", "", "Rewind the conversation and files"),
        s("files", "", "Files read or edited this session"),
        s("doctor", "", "Check claw's setup"),
        s("skills", "", "List skills"),
        s("agents", "", "List sub-agents"),
        s("mcp", "[reconnect]", "MCP servers and their tools"),
        s("config", "[key=value]", "Show or change settings"),
        s("plan", "[description]", "Plan mode (read-only)"),
        s("effort", "[low|medium|high]", "Reasoning effort"),
        s("fast", "[on|off]", "Low effort / fast model"),
        s("autocompact", "[auto|N|off]", "Automatic compaction threshold"),
        s("fallback", "[model|off]", "Fallback model"),
        s("recap", "", "Recap of the session so far"),
        s("goal", "<condition>", "Keep working until a condition holds"),
        s("export", "[file]", "Export the conversation"),
        s("sandbox", "", "Sandbox status"),
        s("permissions", "", "Permission rules"),
        s("background", "", "Background shells and monitors"),
        s("stop", "", "Stop background jobs"),
        s("stats", "", "Session statistics"),
        s("version", "", "claw version"),
        s("reload-skills", "", "Reload skills"),
        s("reload-plugins", "", "Reload plugins"),
        s("btw", "<question>", "Side question, answered without entering the chat"),
        // prompt-backed
        p("review", "[scope]", "Review the current changes"),
        p("security-review", "[scope]", "Security review of the changes"),
        p("simplify", "[focus]", "Simplify the changed code"),
        p("debug", "<problem>", "Structured debugging"),
        p("verify", "[what]", "Verify the work actually runs"),
        p("run", "", "Launch the project to see it working"),
        p("deep-research", "<question>", "Multi-source research report"),
        p("subtask", "<task>", "Do a task in a sub-agent"),
        p("batch", "<instruction>", "Fan out a change over many files"),
        p("scope-check", "<idea>", "Adversarial scope check before coding"),
        p("diverge", "<problem>", "Several different approaches"),
        p("reflect", "", "Save durable memories from this session"),
        p("release-notes", "", "Write release notes"),
        p("pr-comments", "[PR]", "Address PR review comments"),
        p("dataviz", "<request>", "Make a chart"),
        p("insights", "", "Report on how you use claw"),
        p("team-onboarding", "", "Onboarding guide for this project"),
        p("update-config", "<request>", "Change claw's settings"),
        p("fewer-permission-prompts", "", "Allowlist common safe commands"),
        p("statusline", "", "Configure the status line"),
        p("run-skill-generator", "", "Write a project /run skill"),
        p("summary", "", "Summarize the session"),
        p("import", "[codex|cursor]", "Import config from another agent"),
    )

    fun matching(input: String): List<Command> {
        if (!input.startsWith("/") || input.contains(' ') || input.contains('\n')) return emptyList()
        val q = input.drop(1).lowercase()
        return all.filter { it.name.startsWith(q) } + all.filter { !it.name.startsWith(q) && q.length > 1 && it.name.contains(q) }
    }
}
