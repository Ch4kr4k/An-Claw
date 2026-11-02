package dev.anclaw.model

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dev.anclaw.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class ModelEntry(
    val id: String,
    val name: String,
    val path: String,
    /** True when the file was copied into app storage (deleting the entry deletes it). */
    val imported: Boolean,
    val sizeBytes: Long,
    val info: GgufInfo? = null,
    /**
     * Chat template: "auto" (bundled Qwen tool template when the model's own
     * cannot call tools), "own" (the GGUF's), "qwen3-tools", or a .jinja path.
     */
    val template: String = "auto",
) {
    val file get() = File(path)
    val exists get() = file.canRead()

    /** Model id claw uses: `local/<alias>` (llama-server is started with `--alias <alias>`). */
    val alias: String
        get() = name.substringBeforeLast(".gguf").lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifEmpty { "model" }
}

/** Progress of an import or download, shown on the Models screen. */
data class Transfer(val label: String, val done: Long, val total: Long, val error: String? = null) {
    val fraction: Float get() = if (total > 0) done.toFloat() / total else 0f
}

/**
 * Registered GGUF models. A model is either used in place (a path in shared
 * storage, needs All-files access) or imported (copied to app storage).
 */
class ModelStore(private val context: Context, private val paths: Paths) {
    private val _models = MutableStateFlow(load())
    val models: StateFlow<List<ModelEntry>> = _models

    private val _transfer = MutableStateFlow<Transfer?>(null)
    val transfer: StateFlow<Transfer?> = _transfer

    fun byId(id: String) = _models.value.firstOrNull { it.id == id }

    private fun load(): List<ModelEntry> = runCatching {
        val array = JSONArray(paths.modelsIndex.readText())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            ModelEntry(
                id = o.getString("id"),
                name = o.getString("name"),
                path = o.getString("path"),
                imported = o.optBoolean("imported"),
                sizeBytes = o.optLong("size"),
                template = o.optString("template", "auto"),
            )
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<ModelEntry>) {
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name).put("path", it.path)
                .put("imported", it.imported).put("size", it.sizeBytes).put("template", it.template))
        }
        paths.modelsIndex.writeText(array.toString(2))
        _models.value = list
    }

    /** Reads GGUF headers of entries that lack them (off the main thread). */
    suspend fun refreshInfo() = withContext(Dispatchers.IO) {
        // Also pick up files dropped into the models folder (e.g. by the agent's hf_download).
        val known = _models.value.map { it.path }.toSet()
        val found = paths.modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", true) }
            .orEmpty().filter { it.path !in known }
            .map { ModelEntry(UUID.randomUUID().toString(), it.name, it.path, true, it.length()) }
        if (found.isNotEmpty()) save(_models.value + found)
        _models.value = _models.value.map { m -> if (m.info == null && m.exists) m.copy(info = Gguf.read(m.file)) else m }
    }

    /** Adds a GGUF file by absolute path, used where it is. */
    suspend fun addPath(path: String): Result<ModelEntry> = withContext(Dispatchers.IO) {
        val file = File(path.trim())
        when {
            !file.isFile -> Result.failure(IllegalArgumentException("No such file: $file"))
            !file.canRead() -> Result.failure(SecurityException(
                "Cannot read $file. Grant All-files access (Settings → Storage access) or import the model instead."))
            Gguf.read(file) == null -> Result.failure(IllegalArgumentException("${file.name} is not a GGUF file"))
            else -> {
                _models.value.firstOrNull { it.path == file.path }?.let { return@withContext Result.success(it) }
                val entry = ModelEntry(UUID.randomUUID().toString(), file.name, file.path, false, file.length(), Gguf.read(file))
                save(_models.value + entry)
                Result.success(entry)
            }
        }
    }

    /**
     * Adds a file picked with the system picker. When the document maps to a
     * real path we can read (primary/SD storage + All-files access), it is used
     * in place; otherwise it is imported (copied).
     */
    suspend fun addFromUri(uri: Uri, copy: Boolean): Result<ModelEntry> {
        if (!copy) {
            val path = resolvePath(uri)
            if (path != null && File(path).canRead()) return addPath(path)
        }
        return import(uri)
    }

    suspend fun import(uri: Uri): Result<ModelEntry> = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: "model-${System.currentTimeMillis()}.gguf"
        val total = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L
        val dest = uniqueFile(name)
        val part = File(dest.path + ".part")
        runCatching {
            _transfer.value = Transfer("Importing $name", 0, total)
            context.contentResolver.openInputStream(uri)!!.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(4 shl 20)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - last > (32 shl 20)) {
                            last = done; _transfer.value = Transfer("Importing $name", done, total)
                        }
                    }
                }
            }
            check(Gguf.read(part) != null) { "$name is not a GGUF file" }
            check(part.renameTo(dest)) { "could not move the file into place" }
            val entry = ModelEntry(UUID.randomUUID().toString(), dest.name, dest.path, true, dest.length(), Gguf.read(dest))
            save(_models.value + entry)
            _transfer.value = null
            entry
        }.onFailure {
            part.delete()
            _transfer.value = Transfer("Import of $name failed", 0, total, it.message)
        }
    }

    /**
     * Downloads `file` from Hugging Face repo `repo` into the models folder,
     * resuming a partial download.
     */
    suspend fun downloadFromHf(repo: String, file: String, token: String): Result<ModelEntry> = withContext(Dispatchers.IO) {
        val name = file.substringAfterLast('/')
        val dest = File(paths.modelsDir, name)
        val part = File(dest.path + ".part")
        runCatching {
            require(Regex("[\\w.-]+/[\\w.-]+").matches(repo)) { "repo must look like owner/name" }
            val url = "https://huggingface.co/$repo/resolve/main/" +
                file.split('/').joinToString("/") { Uri.encode(it) }
            val have = if (part.exists()) part.length() else 0L
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000; readTimeout = 120_000
                setRequestProperty("User-Agent", "an-claw")
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            val code = conn.responseCode
            check(code == 200 || code == 206) { "HTTP $code from Hugging Face" + if (code == 401 || code == 403) " (gated model? add an HF token)" else "" }
            val resumed = code == 206
            val total = (if (resumed) have else 0L) + conn.contentLengthLong
            var done = if (resumed) have else 0L
            java.io.FileOutputStream(part, resumed).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(1 shl 20)
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - last > (16 shl 20)) {
                            last = done; _transfer.value = Transfer("Downloading $name", done, total)
                        }
                    }
                }
            }
            check(total <= 0 || done >= total) { "download stopped early; tap download again to resume" }
            check(part.renameTo(dest)) { "could not move the file into place" }
            _models.value.firstOrNull { it.path == dest.path }?.let { _transfer.value = null; return@runCatching it }
            val entry = ModelEntry(UUID.randomUUID().toString(), dest.name, dest.path, true, dest.length(), Gguf.read(dest))
            save(_models.value + entry)
            _transfer.value = null
            entry
        }.onFailure { _transfer.value = Transfer("Download of $name failed", 0, 0, it.message) }
    }

    /**
     * Writes a Q4_0 copy of [model] with the bundled llama-quantize. On ARM,
     * llama.cpp repacks Q4_0 for i8mm/dotprod and KleidiAI, so prompts are read
     * ~1.6x faster than Q4_K_M (measured on a Snapdragon 8+ Gen 1); requantizing
     * an already-quantized file costs a little quality.
     */
    suspend fun optimizeQ40(model: ModelEntry, threads: Int): Result<ModelEntry> = withContext(Dispatchers.IO) {
        val exe = File(paths.nativeDir, "libllama_quantize.so")
        val dest = uniqueFile(model.name.substringBeforeLast(".gguf").replace(Regex("(?i)[.-]?(q\\d_k(_[sml])?|iq\\d\\w*|q\\d_\\d)$"), "") + ".Q4_0.gguf")
        runCatching {
            check(exe.canExecute()) { "llama-quantize is not in this build" }
            _transfer.value = Transfer("Optimizing ${model.name} → ${dest.name}", 0, 0)
            val proc = ProcessBuilder(exe.path, "--allow-requantize", model.path, dest.path, "Q4_0", threads.toString())
                .redirectErrorStream(true)
                .apply { environment()["LD_LIBRARY_PATH"] = paths.nativeDir.path }
                .start()
            val log = StringBuilder()
            proc.inputStream.bufferedReader().forEachLine { line ->
                log.appendLine(line)
                // "[  12/ 398] blk.0.attn_q.weight ..." → progress
                Regex("\\[\\s*(\\d+)/\\s*(\\d+)]").find(line)?.let {
                    _transfer.value = Transfer("Optimizing ${model.name}", it.groupValues[1].toLong(), it.groupValues[2].toLong())
                }
            }
            val code = proc.waitFor()
            check(code == 0 && dest.isFile) { "llama-quantize failed ($code): " + log.lines().lastOrNull { it.isNotBlank() }.orEmpty() }
            val entry = ModelEntry(UUID.randomUUID().toString(), dest.name, dest.path, true, dest.length(), Gguf.read(dest), model.template)
            save(_models.value + entry)
            _transfer.value = null
            entry
        }.onFailure {
            dest.delete()
            _transfer.value = Transfer("Optimizing ${model.name} failed", 0, 0, it.message)
        }
    }

    fun clearTransferError() {
        if (_transfer.value?.error != null) _transfer.value = null
    }

    /** Forgets an entry; an imported model's file is deleted too. */
    fun remove(id: String) {
        val entry = byId(id) ?: return
        if (entry.imported) entry.file.delete()
        save(_models.value.filterNot { it.id == id })
    }

    fun setTemplate(id: String, template: String) {
        save(_models.value.map { if (it.id == id) it.copy(template = template) else it })
    }

    fun rename(id: String, name: String) {
        save(_models.value.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it })
    }

    private fun uniqueFile(name: String): File {
        var file = File(paths.modelsDir, name)
        var i = 1
        while (file.exists()) file = File(paths.modelsDir, name.substringBeforeLast(".gguf") + "-${i++}.gguf")
        return file
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?.replace('/', '_')

    /**
     * Maps a document picked with the system picker to a filesystem path, for
     * the external-storage provider (`primary:Download/x.gguf` or
     * `XXXX-XXXX:models/x.gguf`) and raw-path documents. Null when unknown.
     */
    fun resolvePath(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        return when {
            docId.startsWith("raw:") -> docId.removePrefix("raw:")
            uri.authority == "com.android.externalstorage.documents" -> {
                val volume = docId.substringBefore(':')
                val rel = docId.substringAfter(':', "")
                val root = if (volume.equals("primary", true)) Environment.getExternalStorageDirectory().path
                else "/storage/$volume"
                "$root/$rel"
            }
            else -> null
        }
    }
}
