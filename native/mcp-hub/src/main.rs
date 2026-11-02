//! an-claw MCP hub: a stdio MCP server with Hugging Face Hub and GitHub tools.
//!
//! claw starts it as an ordinary stdio MCP server (the app writes the entry
//! into `~/.claw/settings.json`). Messages are newline-delimited JSON-RPC 2.0,
//! as in the MCP stdio spec.
//!
//! Environment:
//! - `HF_TOKEN`: Hugging Face token (private/gated repos, higher limits)
//! - `GITHUB_TOKEN`: GitHub token (needed for writes, raises rate limits)
//! - `ANCLAW_MODELS_DIR`: where `hf_download` puts `.gguf` files by default
//! - `ANCLAW_WORKSPACE`: default destination for other downloads

use std::collections::BTreeMap;
use std::fs;
use std::io::{self, BufRead, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use serde_json::{json, Value};

const HF: &str = "https://huggingface.co";
const GH: &str = "https://api.github.com";
const UA: &str = concat!("an-claw-mcp-hub/", env!("CARGO_PKG_VERSION"));
/// Longest text returned to the model from one call.
const MAX_TEXT: usize = 60_000;
/// Downloads bigger than this run in the background (see `hf_download_status`).
const BACKGROUND_BYTES: u64 = 64 * 1024 * 1024;

type ToolResult = Result<String, String>;

fn main() {
    // `--bridge <url> [--header "Name: value"]...`: stdio ↔ streamable-HTTP MCP proxy.
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.first().map(String::as_str) == Some("--bridge") {
        std::process::exit(bridge::run(&args[1..]));
    }
    let stdin = io::stdin();
    for line in stdin.lock().lines() {
        let Ok(line) = line else { break };
        if line.trim().is_empty() {
            continue;
        }
        let Ok(message) = serde_json::from_str::<Value>(&line) else {
            send(&json!({"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"parse error"}}));
            continue;
        };
        // Notifications (no id) need no answer.
        let Some(id) = message.get("id").cloned() else {
            continue;
        };
        let method = message.get("method").and_then(Value::as_str).unwrap_or("");
        let params = message.get("params").cloned().unwrap_or(Value::Null);
        let reply = match method {
            "initialize" => Ok(json!({
                "protocolVersion": params.get("protocolVersion").cloned()
                    .unwrap_or_else(|| json!("2025-03-26")),
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "anclaw-hub", "version": env!("CARGO_PKG_VERSION")},
                "instructions": "Hugging Face Hub (hf_*) and GitHub (gh_*) tools. \
                    There is no git on the phone: use gh_download_repo to get a repository's files."
            })),
            "ping" => Ok(json!({})),
            "tools/list" => Ok(json!({"tools": tool_list()})),
            "tools/call" => {
                let name = params.get("name").and_then(Value::as_str).unwrap_or("");
                let args = params.get("arguments").cloned().unwrap_or_else(|| json!({}));
                let (text, is_error) = match call_tool(name, &args) {
                    Ok(text) => (text, false),
                    Err(text) => (text, true),
                };
                Ok(json!({"content": [{"type": "text", "text": clip(&text)}], "isError": is_error}))
            }
            "resources/list" => Ok(json!({"resources": []})),
            "prompts/list" => Ok(json!({"prompts": []})),
            other => Err(json!({"code": -32601, "message": format!("unknown method {other}")})),
        };
        match reply {
            Ok(result) => send(&json!({"jsonrpc": "2.0", "id": id, "result": result})),
            Err(error) => send(&json!({"jsonrpc": "2.0", "id": id, "error": error})),
        }
    }
}

fn send(value: &Value) {
    let mut out = io::stdout().lock();
    let _ = writeln!(out, "{value}");
    let _ = out.flush();
}

fn clip(text: &str) -> String {
    if text.len() <= MAX_TEXT {
        return text.to_string();
    }
    let mut end = MAX_TEXT;
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    format!("{}\n… (cut at {MAX_TEXT} bytes)", &text[..end])
}

// ---------------------------------------------------------------- schema

fn prop(kind: &str, description: &str) -> Value {
    json!({"type": kind, "description": description})
}

fn tool(name: &str, description: &str, props: Value, required: &[&str]) -> Value {
    // MCP annotations: claw derives the permission a tool needs from them, so
    // lookups run in read-only mode and only the writers ask.
    let writes = matches!(name, "hf_download" | "gh_download_repo");
    let remote_write = matches!(name, "gh_create_issue" | "gh_comment");
    json!({
        "name": name,
        "description": description,
        "inputSchema": {"type": "object", "properties": props, "required": required},
        "annotations": {
            "readOnlyHint": !(writes || remote_write),
            "destructiveHint": remote_write,
            "openWorldHint": true,
        },
    })
}

fn tool_list() -> Vec<Value> {
    let hf_kind = prop("string", "models (default), datasets or spaces");
    vec![
        tool("hf_search", "Search the Hugging Face Hub for models, datasets or spaces.", json!({
            "query": prop("string", "search text"),
            "kind": hf_kind,
            "author": prop("string", "only this user or org"),
            "filter": prop("string", "tag filter, e.g. gguf, text-generation, license:mit"),
            "sort": prop("string", "downloads (default), likes, trendingScore, lastModified, createdAt"),
            "limit": prop("integer", "max results (default 20, max 100)"),
        }), &[]),
        tool("hf_repo_info", "Details of a Hub repo: downloads, likes, tags, license, card summary and files with sizes.", json!({
            "repo_id": prop("string", "owner/name"),
            "kind": hf_kind,
            "revision": prop("string", "branch, tag or commit (default main)"),
        }), &["repo_id"]),
        tool("hf_list_files", "List files of a Hub repo (recursive), with sizes. Use to pick a GGUF quant.", json!({
            "repo_id": prop("string", "owner/name"),
            "kind": hf_kind,
            "revision": prop("string", "default main"),
            "path": prop("string", "subfolder"),
        }), &["repo_id"]),
        tool("hf_read_file", "Read a text file from a Hub repo (README.md, config.json, …).", json!({
            "repo_id": prop("string", "owner/name"),
            "path": prop("string", "file path in the repo"),
            "kind": hf_kind,
            "revision": prop("string", "default main"),
            "max_bytes": prop("integer", "default 60000"),
        }), &["repo_id", "path"]),
        tool("hf_download", "Download a file from a Hub repo to the device. .gguf files go to the app's models folder (so the app can load them) unless dest is given. Large files continue in the background; check with hf_download_status. Partial downloads resume.", json!({
            "repo_id": prop("string", "owner/name"),
            "path": prop("string", "file path in the repo"),
            "kind": hf_kind,
            "revision": prop("string", "default main"),
            "dest": prop("string", "destination directory"),
        }), &["repo_id", "path"]),
        tool("hf_download_status", "Progress of background Hugging Face downloads.", json!({}), &[]),
        tool("hf_daily_papers", "Today's Hugging Face daily papers (title, arXiv id, upvotes, summary).", json!({
            "limit": prop("integer", "default 15"),
        }), &[]),
        tool("hf_whoami", "The Hugging Face account of HF_TOKEN.", json!({}), &[]),
        tool("gh_search", "Search GitHub: repositories, code, issues (also PRs) or users. Uses GitHub search syntax (e.g. 'llama language:c++ stars:>100').", json!({
            "query": prop("string", "search query"),
            "kind": prop("string", "repositories (default), code, issues, users"),
            "sort": prop("string", "e.g. stars, updated (repositories)"),
            "limit": prop("integer", "default 15, max 100"),
        }), &["query"]),
        tool("gh_repo", "Details of a GitHub repository.", json!({"repo": prop("string", "owner/name")}), &["repo"]),
        tool("gh_ls", "List a directory of a GitHub repository.", json!({
            "repo": prop("string", "owner/name"),
            "path": prop("string", "directory (default root)"),
            "ref": prop("string", "branch, tag or commit"),
        }), &["repo"]),
        tool("gh_read", "Read a file from a GitHub repository.", json!({
            "repo": prop("string", "owner/name"),
            "path": prop("string", "file path"),
            "ref": prop("string", "branch, tag or commit"),
        }), &["repo", "path"]),
        tool("gh_issues", "List issues or pull requests of a repository.", json!({
            "repo": prop("string", "owner/name"),
            "kind": prop("string", "issues (default) or pulls"),
            "state": prop("string", "open (default), closed, all"),
            "limit": prop("integer", "default 20"),
        }), &["repo"]),
        tool("gh_issue", "One issue or pull request with its comments.", json!({
            "repo": prop("string", "owner/name"),
            "number": prop("integer", "issue or PR number"),
        }), &["repo", "number"]),
        tool("gh_pr_diff", "The diff of a pull request.", json!({
            "repo": prop("string", "owner/name"),
            "number": prop("integer", "PR number"),
        }), &["repo", "number"]),
        tool("gh_commits", "Recent commits of a repository, optionally for one path.", json!({
            "repo": prop("string", "owner/name"),
            "path": prop("string", "only commits touching this path"),
            "ref": prop("string", "branch or tag"),
            "limit": prop("integer", "default 20"),
        }), &["repo"]),
        tool("gh_releases", "Releases of a repository with their assets.", json!({
            "repo": prop("string", "owner/name"),
            "limit": prop("integer", "default 10"),
        }), &["repo"]),
        tool("gh_download_repo", "Download a repository snapshot (tarball) and unpack it: the phone has no git, so this stands in for a clone.", json!({
            "repo": prop("string", "owner/name"),
            "ref": prop("string", "branch, tag or commit (default: default branch)"),
            "dest": prop("string", "directory to unpack into (default: workspace/<name>)"),
        }), &["repo"]),
        tool("gh_create_issue", "Open an issue. Needs GITHUB_TOKEN.", json!({
            "repo": prop("string", "owner/name"),
            "title": prop("string", "title"),
            "body": prop("string", "markdown body"),
        }), &["repo", "title"]),
        tool("gh_comment", "Comment on an issue or pull request. Needs GITHUB_TOKEN.", json!({
            "repo": prop("string", "owner/name"),
            "number": prop("integer", "issue or PR number"),
            "body": prop("string", "markdown body"),
        }), &["repo", "number", "body"]),
    ]
}

fn call_tool(name: &str, args: &Value) -> ToolResult {
    match name {
        "hf_search" => hf_search(args),
        "hf_repo_info" => hf_repo_info(args),
        "hf_list_files" => hf_list_files(args),
        "hf_read_file" => hf_read_file(args),
        "hf_download" => hf_download(args),
        "hf_download_status" => Ok(download_status()),
        "hf_daily_papers" => hf_daily_papers(args),
        "hf_whoami" => hf_get(&format!("{HF}/api/whoami-v2")).map(|v| pretty(&v)),
        "gh_search" => gh_search(args),
        "gh_repo" => gh_repo(args),
        "gh_ls" => gh_ls(args),
        "gh_read" => gh_read(args),
        "gh_issues" => gh_issues(args),
        "gh_issue" => gh_issue(args),
        "gh_pr_diff" => gh_pr_diff(args),
        "gh_commits" => gh_commits(args),
        "gh_releases" => gh_releases(args),
        "gh_download_repo" => gh_download_repo(args),
        "gh_create_issue" => gh_create_issue(args),
        "gh_comment" => gh_comment(args),
        other => Err(format!("unknown tool {other}")),
    }
}

// ---------------------------------------------------------------- helpers

fn s<'a>(args: &'a Value, key: &str) -> Option<&'a str> {
    args.get(key).and_then(Value::as_str).map(str::trim).filter(|v| !v.is_empty())
}

fn req_s<'a>(args: &'a Value, key: &str) -> Result<&'a str, String> {
    s(args, key).ok_or_else(|| format!("missing `{key}`"))
}

fn n(args: &Value, key: &str, default: u64, max: u64) -> u64 {
    args.get(key)
        .and_then(|v| v.as_u64().or_else(|| v.as_str().and_then(|s| s.parse().ok())))
        .unwrap_or(default)
        .clamp(1, max)
}

fn pretty(value: &Value) -> String {
    serde_json::to_string_pretty(value).unwrap_or_default()
}

fn enc(text: &str) -> String {
    let mut out = String::new();
    for byte in text.bytes() {
        if byte.is_ascii_alphanumeric() || b"-_.~".contains(&byte) {
            out.push(byte as char);
        } else {
            out.push_str(&format!("%{byte:02X}"));
        }
    }
    out
}

/// Encodes each segment of a repo path, keeping the slashes.
fn enc_path(path: &str) -> String {
    path.trim_matches('/').split('/').map(enc).collect::<Vec<_>>().join("/")
}

fn valid_repo(repo: &str) -> Result<&str, String> {
    let ok = repo.split('/').count() == 2
        && repo.split('/').all(|part| {
            !part.is_empty()
                && part != "."
                && part != ".."
                && part.chars().all(|c| c.is_ascii_alphanumeric() || "-_.".contains(c))
        });
    if ok {
        Ok(repo)
    } else {
        Err(format!("`{repo}` is not an owner/name repository id"))
    }
}

fn agent() -> &'static ureq::Agent {
    static AGENT: OnceLock<ureq::Agent> = OnceLock::new();
    AGENT.get_or_init(|| {
        ureq::AgentBuilder::new()
            .timeout_connect(Duration::from_secs(20))
            .timeout_read(Duration::from_secs(120))
            .user_agent(UA)
            .build()
    })
}

fn token(var: &str) -> Option<String> {
    std::env::var(var).ok().map(|t| t.trim().to_string()).filter(|t| !t.is_empty())
}

fn describe_error(error: ureq::Error) -> String {
    match error {
        ureq::Error::Status(code, response) => {
            let url = response.get_url().to_string();
            let body = response.into_string().unwrap_or_default();
            let hint = match code {
                401 | 403 => " (check the token in an-claw Settings)",
                404 => " (not found, or private without a token)",
                429 => " (rate limited; a token raises the limit)",
                _ => "",
            };
            format!("HTTP {code}{hint} for {url}: {}", body.chars().take(800).collect::<String>())
        }
        ureq::Error::Transport(transport) => format!("network error: {transport}"),
    }
}

fn hf_request(method: &str, url: &str) -> ureq::Request {
    let mut request = agent().request(method, url);
    if let Some(token) = token("HF_TOKEN") {
        request = request.set("Authorization", &format!("Bearer {token}"));
    }
    request
}

fn hf_get(url: &str) -> Result<Value, String> {
    hf_request("GET", url)
        .call()
        .map_err(describe_error)?
        .into_json::<Value>()
        .map_err(|e| e.to_string())
}

fn gh_request(method: &str, url: &str) -> ureq::Request {
    let mut request = agent()
        .request(method, url)
        .set("Accept", "application/vnd.github+json")
        .set("X-GitHub-Api-Version", "2022-11-28");
    if let Some(token) = token("GITHUB_TOKEN").or_else(|| token("GH_TOKEN")) {
        request = request.set("Authorization", &format!("Bearer {token}"));
    }
    request
}

fn gh_get(url: &str) -> Result<Value, String> {
    gh_request("GET", url)
        .call()
        .map_err(describe_error)?
        .into_json::<Value>()
        .map_err(|e| e.to_string())
}

fn need_gh_token() -> Result<(), String> {
    if token("GITHUB_TOKEN").or_else(|| token("GH_TOKEN")).is_some() {
        Ok(())
    } else {
        Err("this needs a GitHub token: add one in an-claw Settings → Tokens".into())
    }
}

fn human(bytes: u64) -> String {
    const UNITS: [&str; 5] = ["B", "KB", "MB", "GB", "TB"];
    let mut size = bytes as f64;
    let mut unit = 0;
    while size >= 1024.0 && unit < UNITS.len() - 1 {
        size /= 1024.0;
        unit += 1;
    }
    if unit == 0 {
        format!("{bytes} B")
    } else {
        format!("{size:.1} {}", UNITS[unit])
    }
}

fn hf_kind(args: &Value) -> Result<&'static str, String> {
    match s(args, "kind").unwrap_or("models") {
        "model" | "models" => Ok("models"),
        "dataset" | "datasets" => Ok("datasets"),
        "space" | "spaces" => Ok("spaces"),
        other => Err(format!("unknown kind `{other}`: models, datasets or spaces")),
    }
}

/// URL prefix for files of a repo: `/` for models, `/datasets/`, `/spaces/`.
fn hf_file_prefix(kind: &str) -> &'static str {
    match kind {
        "datasets" => "/datasets",
        "spaces" => "/spaces",
        _ => "",
    }
}

// ---------------------------------------------------------------- Hugging Face

fn hf_search(args: &Value) -> ToolResult {
    let kind = hf_kind(args)?;
    let mut url = format!(
        "{HF}/api/{kind}?limit={}&sort={}&direction=-1",
        n(args, "limit", 20, 100),
        enc(s(args, "sort").unwrap_or("downloads"))
    );
    for key in ["search", "author", "filter"] {
        let value = if key == "search" { s(args, "query") } else { s(args, key) };
        if let Some(value) = value {
            url.push_str(&format!("&{key}={}", enc(value)));
        }
    }
    let results = hf_get(&url)?;
    let rows: Vec<String> = results
        .as_array()
        .map(|items| {
            items
                .iter()
                .map(|item| {
                    let id = item.get("id").and_then(Value::as_str).unwrap_or("?");
                    let downloads = item.get("downloads").and_then(Value::as_u64).unwrap_or(0);
                    let likes = item.get("likes").and_then(Value::as_u64).unwrap_or(0);
                    let task = item.get("pipeline_tag").and_then(Value::as_str).unwrap_or("");
                    format!("{id}  ↓{downloads} ♥{likes} {task}")
                })
                .collect()
        })
        .unwrap_or_default();
    if rows.is_empty() {
        Ok("no results".into())
    } else {
        Ok(rows.join("\n"))
    }
}

fn hf_repo_info(args: &Value) -> ToolResult {
    let kind = hf_kind(args)?;
    let repo = valid_repo(req_s(args, "repo_id")?)?;
    let revision = s(args, "revision").unwrap_or("main");
    let info = hf_get(&format!("{HF}/api/{kind}/{repo}/revision/{}?blobs=true", enc(revision)))?;
    let mut out = BTreeMap::new();
    for key in [
        "id", "author", "sha", "lastModified", "downloads", "likes", "pipeline_tag",
        "library_name", "tags", "gated", "private", "disabled",
    ] {
        if let Some(value) = info.get(key) {
            out.insert(key.to_string(), value.clone());
        }
    }
    if let Some(card) = info.get("cardData") {
        out.insert("cardData".into(), card.clone());
    }
    if let Some(gguf) = info.get("gguf") {
        out.insert("gguf".into(), gguf.clone());
    }
    let files: Vec<String> = info
        .get("siblings")
        .and_then(Value::as_array)
        .map(|files| {
            files
                .iter()
                .map(|file| {
                    let name = file.get("rfilename").and_then(Value::as_str).unwrap_or("?");
                    match file.get("size").and_then(Value::as_u64) {
                        Some(size) => format!("{name} ({})", human(size)),
                        None => name.to_string(),
                    }
                })
                .collect()
        })
        .unwrap_or_default();
    Ok(format!(
        "{}\n\nfiles ({}):\n{}",
        pretty(&Value::Object(out.into_iter().collect())),
        files.len(),
        files.join("\n")
    ))
}

fn hf_list_files(args: &Value) -> ToolResult {
    let kind = hf_kind(args)?;
    let repo = valid_repo(req_s(args, "repo_id")?)?;
    let revision = s(args, "revision").unwrap_or("main");
    let path = s(args, "path").map(enc_path).unwrap_or_default();
    let url = format!("{HF}/api/{kind}/{repo}/tree/{}/{path}?recursive=true", enc(revision));
    let tree = hf_get(&url)?;
    let mut total = 0u64;
    let rows: Vec<String> = tree
        .as_array()
        .map(|entries| {
            entries
                .iter()
                .filter(|e| e.get("type").and_then(Value::as_str) == Some("file"))
                .map(|e| {
                    let size = e
                        .get("lfs")
                        .and_then(|l| l.get("size"))
                        .or_else(|| e.get("size"))
                        .and_then(Value::as_u64)
                        .unwrap_or(0);
                    total += size;
                    format!("{}  {}", e.get("path").and_then(Value::as_str).unwrap_or("?"), human(size))
                })
                .collect()
        })
        .unwrap_or_default();
    Ok(format!("{}\n\n{} files, {} total", rows.join("\n"), rows.len(), human(total)))
}

fn hf_resolve_url(args: &Value) -> Result<(String, String), String> {
    let kind = hf_kind(args)?;
    let repo = valid_repo(req_s(args, "repo_id")?)?;
    let path = req_s(args, "path")?;
    let revision = s(args, "revision").unwrap_or("main");
    Ok((
        format!("{HF}{}/{repo}/resolve/{}/{}", hf_file_prefix(kind), enc(revision), enc_path(path)),
        path.to_string(),
    ))
}

fn hf_read_file(args: &Value) -> ToolResult {
    let (url, _) = hf_resolve_url(args)?;
    let max = n(args, "max_bytes", MAX_TEXT as u64, 4 * MAX_TEXT as u64);
    let response = hf_request("GET", &url)
        .set("Range", &format!("bytes=0-{}", max - 1))
        .call()
        .map_err(describe_error)?;
    let mut bytes = Vec::new();
    response
        .into_reader()
        .take(max)
        .read_to_end(&mut bytes)
        .map_err(|e| e.to_string())?;
    if bytes.iter().take(8000).any(|&b| b == 0) {
        return Err("binary file; use hf_download instead".into());
    }
    Ok(String::from_utf8_lossy(&bytes).into_owned())
}

struct Download {
    id: u64,
    name: String,
    dest: PathBuf,
    done: AtomicU64,
    total: AtomicU64,
    state: Mutex<String>,
}

fn downloads() -> &'static Mutex<Vec<Arc<Download>>> {
    static DOWNLOADS: OnceLock<Mutex<Vec<Arc<Download>>>> = OnceLock::new();
    DOWNLOADS.get_or_init(|| Mutex::new(Vec::new()))
}

fn download_status() -> String {
    let list = downloads().lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    if list.is_empty() {
        return "no downloads in this session".into();
    }
    list.iter()
        .map(|d| {
            let done = d.done.load(Ordering::Relaxed);
            let total = d.total.load(Ordering::Relaxed);
            let pct = if total > 0 { done * 100 / total } else { 0 };
            format!(
                "#{} {} → {}: {} ({} / {}, {pct}%)",
                d.id,
                d.name,
                d.dest.display(),
                d.state.lock().map(|s| s.clone()).unwrap_or_default(),
                human(done),
                human(total)
            )
        })
        .collect::<Vec<_>>()
        .join("\n")
}

fn default_dir(var: &str) -> PathBuf {
    std::env::var_os(var)
        .map(PathBuf::from)
        .or_else(|| std::env::current_dir().ok())
        .unwrap_or_else(|| PathBuf::from("."))
}

fn hf_download(args: &Value) -> ToolResult {
    let (url, path) = hf_resolve_url(args)?;
    let file_name = Path::new(&path)
        .file_name()
        .and_then(|n| n.to_str())
        .filter(|n| !n.starts_with('.'))
        .ok_or("bad file path")?
        .to_string();
    let dir = match s(args, "dest") {
        Some(dest) => PathBuf::from(dest),
        None if file_name.to_ascii_lowercase().ends_with(".gguf") => default_dir("ANCLAW_MODELS_DIR"),
        None => default_dir("ANCLAW_WORKSPACE"),
    };
    fs::create_dir_all(&dir).map_err(|e| format!("cannot create {}: {e}", dir.display()))?;
    let dest = dir.join(&file_name);

    // Size first, so small files finish in the call and big ones go to the background.
    let head = hf_request("HEAD", &url).call().map_err(describe_error)?;
    let total = head
        .header("x-linked-size")
        .or_else(|| head.header("content-length"))
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or(0);

    let job = Arc::new(Download {
        id: downloads().lock().map(|l| l.len() as u64 + 1).unwrap_or(1),
        name: format!("{}/{}", s(args, "repo_id").unwrap_or("?"), path),
        dest: dest.clone(),
        done: AtomicU64::new(0),
        total: AtomicU64::new(total),
        state: Mutex::new("running".into()),
    });
    if let Ok(mut list) = downloads().lock() {
        list.push(job.clone());
    }
    if total > BACKGROUND_BYTES {
        let worker = job.clone();
        std::thread::spawn(move || {
            let state = match fetch_to(&url, &worker) {
                Ok(()) => "done".to_string(),
                Err(e) => format!("failed: {e}"),
            };
            if let Ok(mut s) = worker.state.lock() {
                *s = state;
            }
        });
        Ok(format!(
            "Downloading {} ({}) to {} in the background as #{}. Check progress with hf_download_status.",
            job.name,
            human(total),
            dest.display(),
            job.id
        ))
    } else {
        fetch_to(&url, &job)?;
        if let Ok(mut s) = job.state.lock() {
            *s = "done".into();
        }
        Ok(format!("Saved {} ({}) to {}", job.name, human(job.done.load(Ordering::Relaxed)), dest.display()))
    }
}

/// Streams `url` into `job.dest`, resuming a `.part` file left by an earlier try.
fn fetch_to(url: &str, job: &Download) -> Result<(), String> {
    let part = job.dest.with_extension(format!(
        "{}.part",
        job.dest.extension().and_then(|e| e.to_str()).unwrap_or("")
    ));
    let have = fs::metadata(&part).map(|m| m.len()).unwrap_or(0);
    let mut request = hf_request("GET", url);
    if have > 0 {
        request = request.set("Range", &format!("bytes={have}-"));
    }
    let response = request.call().map_err(describe_error)?;
    let resumed = response.status() == 206;
    let mut file = fs::OpenOptions::new()
        .create(true)
        .append(resumed)
        .write(true)
        .truncate(!resumed)
        .open(&part)
        .map_err(|e| e.to_string())?;
    let mut done = if resumed { have } else { 0 };
    job.done.store(done, Ordering::Relaxed);
    let mut reader = response.into_reader();
    let mut buffer = vec![0u8; 1 << 20];
    loop {
        let read = reader.read(&mut buffer).map_err(|e| e.to_string())?;
        if read == 0 {
            break;
        }
        file.write_all(&buffer[..read]).map_err(|e| e.to_string())?;
        done += read as u64;
        job.done.store(done, Ordering::Relaxed);
    }
    file.flush().map_err(|e| e.to_string())?;
    drop(file);
    let total = job.total.load(Ordering::Relaxed);
    if total > 0 && done < total {
        return Err(format!("connection closed at {} of {}; call hf_download again to resume", human(done), human(total)));
    }
    fs::rename(&part, &job.dest).map_err(|e| e.to_string())
}

fn hf_daily_papers(args: &Value) -> ToolResult {
    let papers = hf_get(&format!("{HF}/api/daily_papers?limit={}", n(args, "limit", 15, 50)))?;
    let rows: Vec<String> = papers
        .as_array()
        .map(|items| {
            items
                .iter()
                .map(|item| {
                    let paper = item.get("paper").unwrap_or(item);
                    let title = paper.get("title").and_then(Value::as_str).unwrap_or("?");
                    let id = paper.get("id").and_then(Value::as_str).unwrap_or("?");
                    let votes = paper.get("upvotes").and_then(Value::as_u64).unwrap_or(0);
                    let summary: String = paper
                        .get("summary")
                        .and_then(Value::as_str)
                        .unwrap_or("")
                        .chars()
                        .take(300)
                        .collect();
                    format!("## {title}\narXiv {id} · ▲{votes} · {HF}/papers/{id}\n{summary}…")
                })
                .collect()
        })
        .unwrap_or_default();
    Ok(rows.join("\n\n"))
}

// ---------------------------------------------------------------- GitHub

fn gh_search(args: &Value) -> ToolResult {
    let kind = s(args, "kind").unwrap_or("repositories");
    if !["repositories", "code", "issues", "users"].contains(&kind) {
        return Err(format!("unknown kind `{kind}`"));
    }
    if kind == "code" {
        need_gh_token()?; // GitHub requires auth for code search
    }
    let mut url = format!(
        "{GH}/search/{kind}?q={}&per_page={}",
        enc(req_s(args, "query")?),
        n(args, "limit", 15, 100)
    );
    if let Some(sort) = s(args, "sort") {
        url.push_str(&format!("&sort={}", enc(sort)));
    }
    let results = gh_get(&url)?;
    let total = results.get("total_count").and_then(Value::as_u64).unwrap_or(0);
    let items = results.get("items").and_then(Value::as_array).cloned().unwrap_or_default();
    let rows: Vec<String> = items
        .iter()
        .map(|item| match kind {
            "repositories" => format!(
                "{}  ★{} {} — {}",
                item["full_name"].as_str().unwrap_or("?"),
                item["stargazers_count"].as_u64().unwrap_or(0),
                item["language"].as_str().unwrap_or(""),
                item["description"].as_str().unwrap_or("")
            ),
            "code" => format!(
                "{}:{}  {}",
                item["repository"]["full_name"].as_str().unwrap_or("?"),
                item["path"].as_str().unwrap_or("?"),
                item["html_url"].as_str().unwrap_or("")
            ),
            "issues" => format!(
                "{}#{} [{}] {}",
                item["repository_url"].as_str().unwrap_or("").trim_start_matches("https://api.github.com/repos/"),
                item["number"].as_u64().unwrap_or(0),
                item["state"].as_str().unwrap_or("?"),
                item["title"].as_str().unwrap_or("")
            ),
            _ => format!("{}  {}", item["login"].as_str().unwrap_or("?"), item["html_url"].as_str().unwrap_or("")),
        })
        .collect();
    Ok(format!("{total} results\n{}", rows.join("\n")))
}

fn gh_repo(args: &Value) -> ToolResult {
    let repo = valid_repo(req_s(args, "repo")?)?;
    let info = gh_get(&format!("{GH}/repos/{repo}"))?;
    let mut out = serde_json::Map::new();
    for key in [
        "full_name", "description", "homepage", "language", "stargazers_count", "forks_count",
        "open_issues_count", "default_branch", "topics", "license", "archived", "pushed_at", "html_url",
    ] {
        if let Some(value) = info.get(key) {
            let value = if key == "license" { value.get("spdx_id").cloned().unwrap_or(Value::Null) } else { value.clone() };
            out.insert(key.into(), value);
        }
    }
    Ok(pretty(&Value::Object(out)))
}

fn ref_query(args: &Value) -> String {
    s(args, "ref").map(|r| format!("?ref={}", enc(r))).unwrap_or_default()
}

fn gh_ls(args: &Value) -> ToolResult {
    let repo = valid_repo(req_s(args, "repo")?)?;
    let path = s(args, "path").map(enc_path).unwrap_or_default();
    let listing = gh_get(&format!("{GH}/repos/{repo}/contents/{path}{}", ref_query(args)))?;
    let Some(entries) = listing.as_array() else {
        return Err("that path is a file; use gh_read".into());
    };
    Ok(entries
        .iter()
        .map(|e| {
            let name = e["name"].as_str().unwrap_or("?");
            if e["type"] == "dir" {
                format!("{name}/")
            } else {
                format!("{name}  {}", human(e["size"].as_u64().unwrap_or(0)))
            }
        })
        .collect::<Vec<_>>()
        .join("\n"))
}

fn gh_read(args: &Value) -> ToolResult {
    let repo = valid_repo(req_s(args, "repo")?)?;
    let path = enc_path(req_s(args, "path")?);
    gh_request("GET", &format!("{GH}/repos/{repo}/contents/{path}{}", ref_query(args)))
        .set("Accept", "application/vnd.github.raw")
        .call()
        .map_err(describe_error)?
        .into_string()
        .map_err(|e| e.to_string())
}

fn gh_issues(args: &Value) -> ToolResult {
    let repo = valid_repo(req_s(args, "repo")?)?;
    let pulls = s(args, "kind") == Some("pulls");
    let state = s(args, "state").unwrap_or("open");
    let url = format!(
        "{GH}/repos/{repo}/{}?state={}&per_page={}",
        if pulls { "pulls" } else { "issues" },
        enc(state),
        n(args, "limit", 20, 100)
    );
    let items = gh_get(&url)?;
    Ok(items
        .as_array()
        .map(|items| {
            items
                .iter()
                // The issues endpoint also returns PRs; keep them apart.
                .filter(|i| pulls || i.get("pull_request").is_none())
                .map(|i| {
                    format!(
                        "#{} [{}] {} — @{} ({} comments)",
                        i["number"].as_u64().unwrap_or(0),
                        i["state"].as_str().unwrap_or("?"),
                        i["title"].as_str().unwrap_or(""),
                        i["user"]["login"].as_str().unwrap_or("?"),
                        i["comments"].as_u64().unwrap_or(0)
                    )
                })
                .collect::<Vec<_>>()
                .join("\n")
        })
        .unwrap_or_default())
}

fn gh_issue(args: &Value) -> ToolResult {
    let repo = valid_repo(req_s(args, "repo")?)?;
    let number = n(args, "number", 0, u64::MAX);
    let issue = gh_get(&format!("{GH}/repos/{repo}/issues/{number}"))?;
    let comments = gh_get(&format!("{GH}/repos/{repo}/issues/{number}/comments?per_page=100"))?;
    let mut out = format!(
        "# {} (#{number}, {})\n@{} · {}\n\n{}\n",
        issue["title"].as_str().unwrap_or(""),
        issue["state"].as_str().unwrap_or("?"),
        issue["user"]["login"].as_str().unwrap_or("?"),
        issue["html_url"].as_str().unwrap_or(""),
        issue["body"].as_str().unwrap_or("")
    );
    for c in comments.as_array().into_iter().flatten() {
        out.push_str(&format!(
            "\n---\n@{} ({}):\n{}\n",
            c["user"]["login"].as_str().unwrap_or("?"),
            c["created_at"].as_str().unwrap_or(""),
            c["body"].as_str().unwrap_or("")
        ));
    }
    Ok(out)
}

fn gh_pr_diff(args: &Value) -> ToolResult {
    let repo = valid_repo(req_s(args, "repo")?)?;
    let number = n(args, "number", 0, u64::MAX);
    gh_request("GET", &format!("{GH}/repos/{repo}/pulls/{number}"))
        .set("Accept", "application/vnd.github.diff")
        .call()
        .map_err(describe_error)?
        .into_string()
        .map_err(|e| e.to_string())
}

fn gh_commits(args: &Value) -> ToolResult {
    let repo = valid_repo(req_s(args, "repo")?)?;
    let mut url = format!("{GH}/repos/{repo}/commits?per_page={}", n(args, "limit", 20, 100));
    if let Some(path) = s(args, "path") {
        url.push_str(&format!("&path={}", enc(path)));
    }
    if let Some(r) = s(args, "ref") {
        url.push_str(&format!("&sha={}", enc(r)));
    }
    let commits = gh_get(&url)?;
    Ok(commits
        .as_array()
        .map(|items| {
            items
                .iter()
                .map(|c| {
                    format!(
                        "{} {} — {} ({})",
                        c["sha"].as_str().unwrap_or("?").get(..10).unwrap_or("?"),
                        c["commit"]["message"].as_str().unwrap_or("").lines().next().unwrap_or(""),
                        c["commit"]["author"]["name"].as_str().unwrap_or("?"),
                        c["commit"]["author"]["date"].as_str().unwrap_or("")
                    )
                })
                .collect::<Vec<_>>()
                .join("\n")
        })
        .unwrap_or_default())
}

fn gh_releases(args: &Value) -> ToolResult {
    let repo = valid_repo(req_s(args, "repo")?)?;
    let releases = gh_get(&format!("{GH}/repos/{repo}/releases?per_page={}", n(args, "limit", 10, 50)))?;
    let mut out = String::new();
    for r in releases.as_array().into_iter().flatten() {
        out.push_str(&format!(
            "## {} ({}){}\n",
            r["name"].as_str().filter(|n| !n.is_empty()).or(r["tag_name"].as_str()).unwrap_or("?"),
            r["published_at"].as_str().unwrap_or(""),
            if r["prerelease"].as_bool() == Some(true) { " pre-release" } else { "" }
        ));
        for a in r["assets"].as_array().into_iter().flatten() {
            out.push_str(&format!(
                "- {} ({}) {}\n",
                a["name"].as_str().unwrap_or("?"),
                human(a["size"].as_u64().unwrap_or(0)),
                a["browser_download_url"].as_str().unwrap_or("")
            ));
        }
    }
    Ok(if out.is_empty() { "no releases".into() } else { out })
}

fn gh_download_repo(args: &Value) -> ToolResult {
    let repo = valid_repo(req_s(args, "repo")?)?;
    let name = repo.split('/').nth(1).unwrap_or("repo");
    let dest = match s(args, "dest") {
        Some(dest) => PathBuf::from(dest),
        None => default_dir("ANCLAW_WORKSPACE").join(name),
    };
    let reference = s(args, "ref").map(|r| format!("/{}", enc(r))).unwrap_or_default();
    let response = gh_request("GET", &format!("{GH}/repos/{repo}/tarball{reference}"))
        .call()
        .map_err(describe_error)?;
    fs::create_dir_all(&dest).map_err(|e| e.to_string())?;
    let mut archive = tar::Archive::new(flate2::read::GzDecoder::new(response.into_reader()));
    let mut count = 0usize;
    for entry in archive.entries().map_err(|e| e.to_string())? {
        let mut entry = entry.map_err(|e| e.to_string())?;
        let path = entry.path().map_err(|e| e.to_string())?.into_owned();
        // GitHub wraps everything in `<owner>-<repo>-<sha>/`: drop that level.
        let inner: PathBuf = path.components().skip(1).collect();
        if inner.as_os_str().is_empty() {
            continue;
        }
        let kind = entry.header().entry_type();
        if !(kind.is_file() || kind.is_dir()) {
            continue; // no symlinks or devices from an archive
        }
        let target = dest.join(&inner);
        // Refuse anything that would leave `dest`.
        if inner.components().any(|c| !matches!(c, std::path::Component::Normal(_))) {
            continue;
        }
        if kind.is_dir() {
            fs::create_dir_all(&target).map_err(|e| e.to_string())?;
        } else {
            if let Some(parent) = target.parent() {
                fs::create_dir_all(parent).map_err(|e| e.to_string())?;
            }
            let mut file = fs::File::create(&target).map_err(|e| e.to_string())?;
            io::copy(&mut entry, &mut file).map_err(|e| e.to_string())?;
            count += 1;
        }
    }
    Ok(format!("Unpacked {count} files of {repo} into {}", dest.display()))
}

fn gh_create_issue(args: &Value) -> ToolResult {
    need_gh_token()?;
    let repo = valid_repo(req_s(args, "repo")?)?;
    let issue = gh_request("POST", &format!("{GH}/repos/{repo}/issues"))
        .send_json(json!({"title": req_s(args, "title")?, "body": s(args, "body").unwrap_or("")}))
        .map_err(describe_error)?
        .into_json::<Value>()
        .map_err(|e| e.to_string())?;
    Ok(format!("Opened #{}: {}", issue["number"], issue["html_url"].as_str().unwrap_or("")))
}

fn gh_comment(args: &Value) -> ToolResult {
    need_gh_token()?;
    let repo = valid_repo(req_s(args, "repo")?)?;
    let number = n(args, "number", 0, u64::MAX);
    let comment = gh_request("POST", &format!("{GH}/repos/{repo}/issues/{number}/comments"))
        .send_json(json!({"body": req_s(args, "body")?}))
        .map_err(describe_error)?
        .into_json::<Value>()
        .map_err(|e| e.to_string())?;
    Ok(format!("Commented: {}", comment["html_url"].as_str().unwrap_or("")))
}

/// claw runs only stdio MCP servers. This mode lets it use remote
/// streamable-HTTP MCP servers (Hugging Face, GitHub, …) and an-claw's own
/// on-device Android tools server: each JSON-RPC line from stdin is POSTed to
/// the URL, and the JSON or SSE reply is written back as lines.
mod bridge {
    use std::io::{self, BufRead, BufReader, Write};
    use std::time::Duration;

    pub fn run(args: &[String]) -> i32 {
        let Some(url) = args.first() else {
            eprintln!("usage: --bridge <url> [--header 'Name: value']...");
            return 2;
        };
        let mut headers = Vec::new();
        let mut i = 1;
        while i < args.len() {
            if args[i] == "--header" && i + 1 < args.len() {
                if let Some((k, v)) = args[i + 1].split_once(':') {
                    headers.push((k.trim().to_string(), v.trim().to_string()));
                }
                i += 2;
            } else {
                i += 1;
            }
        }
        let agent = ureq::AgentBuilder::new()
            .timeout_connect(Duration::from_secs(20))
            .timeout_read(Duration::from_secs(600))
            .user_agent(super::UA)
            .build();
        let mut session: Option<String> = None;
        let stdin = io::stdin();
        for line in stdin.lock().lines() {
            let Ok(line) = line else { break };
            if line.trim().is_empty() {
                continue;
            }
            let id = serde_json::from_str::<serde_json::Value>(&line)
                .ok()
                .and_then(|v| v.get("id").cloned());
            let mut request = agent
                .post(url)
                .set("Content-Type", "application/json")
                .set("Accept", "application/json, text/event-stream");
            for (k, v) in &headers {
                request = request.set(k, v);
            }
            if let Some(s) = &session {
                request = request.set("Mcp-Session-Id", s);
            }
            match request.send_string(&line) {
                Ok(response) => {
                    if let Some(s) = response.header("mcp-session-id") {
                        session = Some(s.to_string());
                    }
                    let sse = response
                        .header("content-type")
                        .is_some_and(|t| t.contains("text/event-stream"));
                    if sse {
                        // Forward every `data:` event (results, notifications, server requests).
                        let reader = BufReader::new(response.into_reader());
                        let mut data = String::new();
                        for l in reader.lines() {
                            let Ok(l) = l else { break };
                            if let Some(d) = l.strip_prefix("data:") {
                                data.push_str(d.trim_start());
                            } else if l.is_empty() && !data.is_empty() {
                                let done = emit(&data, id.as_ref());
                                data.clear();
                                if done {
                                    break;
                                }
                            }
                        }
                        if !data.is_empty() {
                            emit(&data, id.as_ref());
                        }
                    } else {
                        let body = response.into_string().unwrap_or_default();
                        if !body.trim().is_empty() {
                            emit(body.trim(), id.as_ref());
                        }
                    }
                }
                Err(error) => {
                    // Notifications have no id and need no reply.
                    if let Some(id) = id {
                        let message = super::describe_error(error);
                        let reply = serde_json::json!({"jsonrpc": "2.0", "id": id,
                            "error": {"code": -32000, "message": message}});
                        emit(&reply.to_string(), None);
                    }
                }
            }
        }
        0
    }

    /// Writes one JSON-RPC message as a line; true when it answers `id`.
    fn emit(data: &str, id: Option<&serde_json::Value>) -> bool {
        let Ok(value) = serde_json::from_str::<serde_json::Value>(data) else {
            return false;
        };
        // Some servers answer a notification with an id-less result: not a message.
        let has_id = value.get("id").is_some_and(|v| !v.is_null());
        if !has_id && value.get("method").is_none() {
            return false;
        }
        let mut out = io::stdout().lock();
        let _ = writeln!(out, "{value}");
        let _ = out.flush();
        id.is_some_and(|id| value.get("id") == Some(id))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn repo_ids_are_checked() {
        assert!(valid_repo("ggml-org/llama.cpp").is_ok());
        assert!(valid_repo("../etc").is_err());
        assert!(valid_repo("a/b/c").is_err());
        assert!(valid_repo("a/..").is_err());
    }

    #[test]
    fn paths_are_encoded_per_segment() {
        assert_eq!(enc_path("/a b/c.gguf"), "a%20b/c.gguf");
        assert_eq!(enc("x&y=z"), "x%26y%3Dz");
    }

    #[test]
    fn tool_list_has_unique_names() {
        let tools = tool_list();
        let mut names: Vec<_> = tools.iter().map(|t| t["name"].as_str().unwrap()).collect();
        names.sort_unstable();
        names.dedup();
        assert_eq!(names.len(), tools.len());
    }
}
