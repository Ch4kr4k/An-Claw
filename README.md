# an-claw

An Android AI agent harness: **claw** (from `~/ws/src/rust/claw-code`) running on the phone, backed by
**llama-abominated** (`~/ws/src/cpp/nn/llama-abominated`, a llama.cpp fork) serving a GGUF model you keep in storage.

```
┌──────────────── an-claw (Kotlin / Compose) ─────────────────┐
│ Chat · Sessions · Models · MCP & tools · Settings · Logs    │
└──────┬───────────────────────────────┬──────────────────────┘
       │ JSON lines (stdin/stdout)     │ spawn, /health
       ▼                               ▼
 libclaw.so desktop-agent        libllama_server.so  ──  your .gguf
 (claw's full session engine)    127.0.0.1:8086, OpenAI API, --jinja
       │   ▲ local/<alias> over HTTP ─┘
       │ stdio MCP
       ▼
 libanclaw_hub.so (Hugging Face + GitHub tools) · your other MCP servers
```

The app does not reimplement claw. It cross-compiles the real `claw` binary for Android and drives it through
`claw desktop-agent`, the JSON-lines protocol that claw-desktop also uses. Tools, slash commands, MCP, skills,
memory, permission rules, hooks, sessions and checkpoints are the same code that runs in the terminal.

## Features

- **Agent**: everything claw does that makes sense on a phone. Its tools include bash (via `/system/bin/sh` and
  toybox), read/write/edit, glob/grep, WebFetch/WebSearch, Agent sub-agents, Todo/Task tools, Cron/Monitor,
  LSP, NotebookEdit, CodeSearch and ToolSearch. Permission prompts, AskUserQuestion, `/btw` side questions,
  queued messages while a turn runs, background jobs (shown as chips you can stop), the context meter and
  prompt-read progress are all supported.
- **Slash commands**: `/` opens a picker.
  - Session commands run inside claw: `/compact /cost /context /undo /rewind /diff /memory /mcp /skills /plan /effort /goal /export /doctor`…
  - Prompt-backed commands: `/review /security-review /simplify /debug /verify /deep-research /subtask /batch`…
  - App commands: `/new /resume /model /models /mcp-servers /settings /logs`.
- **MCP**: stdio, http, sse and ws servers, kept in claw's `~/.claw/settings.json`. Add, edit or toggle them in
  the app. One-tap presets: Hugging Face, GitHub, DeepWiki, Context7.
- **Built-in Hugging Face and GitHub tools** (`anclaw-hub` MCP server, `native/mcp-hub`):
  - `hf_search`, `hf_repo_info`, `hf_list_files`, `hf_read_file`, `hf_download` (resumable; runs in the
    background for big files; GGUF files land in the models folder), `hf_download_status`,
    `hf_daily_papers`, `hf_whoami`
  - `gh_search`, `gh_repo`, `gh_ls`, `gh_read`, `gh_issues`, `gh_issue`, `gh_pr_diff`, `gh_commits`,
    `gh_releases`, `gh_create_issue`, `gh_comment`
  - `gh_download_repo`: unpacks a tarball snapshot. There is no git on the phone, so this replaces `git clone`.
- **Models**:
  - **Load from storage**: uses the GGUF where it is, with no copy (needs All-files access).
  - **Import (copy)**: copies the file into app storage.
  - Or enter a path, or download from Hugging Face.
  - The app reads GGUF metadata (architecture, quant, context length, MoE experts, chat template).
  - Engine parameters: context size, threads (auto picks performance cores), batch/ubatch, flash
    attention, KV cache type, mmap, and extra args such as the fork's `--moe-n-expert` and `-ser`.
- **Providers**: the on-device engine, any OpenAI-compatible server (for example llama-server on your PC), or
  the Anthropic API.
- A foreground service with a wake lock keeps the model and agent running in the background.

## Build

Requirements: Android SDK (platform 36), NDK r29 (`~/Android/Sdk/ndk/29.0.14206865`), Rust with the
`aarch64-linux-android` target (and `x86_64-linux-android` for the emulator), CMake + Ninja, and JDK 21.

```sh
native/build-native.sh all                 # arm64-v8a: claw, MCP hub, llama-server -> app/src/main/jniLibs
native/build-native.sh all x86_64          # also for the emulator
./gradlew :app:assembleRelease             # app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

`build-native.sh` copies both source trees into `native/build/src` and applies `native/patches/<project>/*.patch`
there, so your checkouts are never modified. It picks up uncommitted changes. Override the source locations
with `CLAW_CODE_DIR` and `LLAMA_DIR`.

**claw-code patches** (`native/patches/claw-code/`):
- `0002`: `CLAW_HIDE_TOOLS` hides the named tools from the model (see Performance).

`0001`:
- In desktop-agent mode, prompt-backed slash commands (`/review`, `/debug`, …) run their prompt instead of
  reaching the model as literal text.
- A host app can add a system-prompt section via `CLAW_APP_PROMPT`. an-claw uses it to describe the phone.

**llama-abominated on Android**:
- Built with `GGML_BACKEND_DL` + `GGML_CPU_ALL_VARIANTS`: ggml picks the best CPU backend at runtime, from
  armv8.0 up to armv9.2 (dotprod, i8mm, SVE/SME), with KleidiAI and OpenMP.
- The fork's AVX2 iqk kernels are x86-only. On ARM, the stock NEON/KleidiAI kernels run.
- `--moe-n-expert`/`-ser`, the offload policy and the upstream fixes all apply.

## First run

1. Models → **Load from storage** → pick your `.gguf`. Grant All-files access when asked, or use **Import**.
2. Tap ▶ on the model. The status strip shows loading; then ask anything in Chat.
3. Optional: MCP & tools → add HF / GitHub tokens (raise limits, gated models, write access).

Choosing a model: tool calling needs an instruct model whose GGUF has a chat template, for example Qwen3 /
Qwen3.5 / Qwen3.6, Llama 3.x Instruct or Gemma 3. Small models (≤ 2B) work but make poor agents. For a phone
with 12–16 GB of RAM, 4–8B models at Q4_K_M or MoE models with few active parameters are the sweet spot.

## Performance on a phone (measured)

On a Nothing Phone (2) (Snapdragon 8+ Gen 1, 12 GB, Android 16) with CyberSecQwen-4B, CPU only:

| | prompt (pp512) | generation |
|---|---|---|
| Q4_K_M, 4 threads | 22–27 tok/s | ~7 tok/s |
| Q4_0 (requantized), 4 threads | 36 tok/s | ~8 tok/s |
| Q4_K_M, 8 threads (with the little cores) | 20 tok/s | — |

These were measured with the screen off and the phone warm, with the big cores capped at ~1.5 GHz. Expect
more with the screen on and a cool phone.

What the app does about it:
- **Threads**: by default only the performance cores, for both generation and prompt processing.
- **Short prompts**: claw's first request was ~10k tokens (system prompt + tool schemas). an-claw cuts it:
  - desktop-only tools are hidden through `CLAW_HIDE_TOOLS` (claw patch 0002); MCP tools always stay;
  - auto memory and the after-turn learning review are off by default (an extra model request per turn).
- **Persistent prompt cache**: after the first finished turn, the processed system prompt and tools (the
  slot's KV cache) are saved to disk and restored whenever the engine starts. New chats then skip re-reading
  that prefix. Turns within a chat already reuse the cache, plus `--cache-reuse 256`.
- **⚡ Optimize for this phone**: the Models screen can write a Q4_0 copy of a K-quant model with the bundled
  `llama-quantize`. ARM repacks Q4_0 for i8mm/dotprod and KleidiAI, which gives ~1.6× faster prompts. It costs
  a little quality, because it requantizes an already-quantized file.
- **Foreground service**: on this phone the `background` cpuset is CPUs 0–1 (little cores only). The service
  keeps the engine in the foreground set, on all cores, while the app is in the background.

## Chat templates and tool calling

claw needs the model to emit tool calls. llama-server parses them from the chat template, which needs `--jinja`
and a template that renders `tools`.
- Some fine-tunes ship a minimal template without tools. **CyberSecQwen-4B** is one: its template also turns
  system messages into user messages.
- For Qwen-family models in that state, the app automatically uses the bundled Qwen3-Instruct-2507 template
  (`assets/templates/qwen3-tools.jinja`).
- You can change this per model on the Models screen: Auto / Qwen3 tools / Model's own.

## Layout

| Path | What |
|---|---|
| `app/src/main/java/dev/anclaw/agent` | claw process + protocol (`ClawAgent`, `Chat` reducer), settings.json sync, sessions, slash commands |
| `app/src/main/java/dev/anclaw/engine` | llama-server process, foreground service |
| `app/src/main/java/dev/anclaw/model` | model registry, SAF import/in-place paths, HF download, GGUF reader |
| `app/src/main/java/dev/anclaw/ui` | Compose screens, Markdown renderer |
| `native/build-native.sh` | cross-compiles the three executables |
| `native/mcp-hub` | Hugging Face + GitHub MCP server (Rust) |
| `native/patches` | patches applied to the copied sources |

On the device: claw's `$HOME` is `files/home`, so claw's config is `files/home/.claw` (settings, sessions,
memory, skills). The default workspace is `files/workspace`; you can point it at shared storage in Settings.
Imported models go to `Android/data/dev.anclaw/files/models`.

## Limits on Android

- There is no namespace sandbox: Android blocks user namespaces for apps. claw's sandbox is turned off, so the
  permission mode (Settings → Agent) is the guard.
- There is no bash, git, python or compiler unless you put them on the device. Commands run in mksh with
  toybox. Tools that shell out to them (LSP servers, Diagnostics, git checkpoints) report that the program is
  missing.
- No git means no shadow-git checkpoints: `/undo` and `/rewind` of *file* changes are unavailable. Conversation
  rewind still works.
- Desktop-only claw features (terminal UI, notifications via notify-send, browser automation, `/chrome`,
  desktop image viewers) do not apply.
