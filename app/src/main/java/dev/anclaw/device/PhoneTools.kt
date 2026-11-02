package dev.anclaw.device

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import dev.anclaw.AnClawApp
import dev.anclaw.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * "android" MCP server: phone capabilities for the agent (location, contacts,
 * calls, gallery, apps, screen control, lock, settings). Serves streamable
 * HTTP on 127.0.0.1 with a per-run bearer token; claw reaches it through the
 * hub's `--bridge` mode. Tools that act are annotated non-read-only, so claw
 * asks before running them; tools whose permission is missing say how to
 * grant it.
 */
class PhoneTools(private val context: Context) {
    val token: String = UUID.randomUUID().toString()
    @Volatile var port: Int = 0
        private set

    fun start() {
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        port = server.localPort
        thread(name = "phone-tools", isDaemon = true) {
            while (true) {
                val socket = runCatching { server.accept() }.getOrNull() ?: continue
                thread(isDaemon = true) { runCatching { handle(socket) }; runCatching { socket.close() } }
            }
        }
    }

    // ------------------------------------------------------------ HTTP / JSON-RPC

    private fun handle(socket: Socket) {
        socket.soTimeout = 30_000
        val input = socket.getInputStream().buffered()
        val headers = HashMap<String, String>()
        val requestLine = readLine(input) ?: return
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length).also { var r = 0; while (r < length) { val n = input.read(it, r, length - r); if (n < 0) break; r += n } }
        val out = socket.getOutputStream()
        fun respond(code: Int, text: String) {
            val bytes = text.toByteArray()
            out.write(("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
            out.write(bytes); out.flush()
        }
        if (!requestLine.startsWith("POST")) return respond(405, "{}")
        if (headers["authorization"] != "Bearer $token") return respond(401, """{"error":"unauthorized"}""")
        val msg = runCatching { JSONObject(String(body)) }.getOrNull() ?: return respond(400, "{}")
        if (!msg.has("id") || msg.isNull("id")) return respond(202, "")
        val id = msg.get("id")
        val params = msg.optJSONObject("params") ?: JSONObject()
        val result: Any = when (msg.optString("method")) {
            "initialize" -> JSONObject()
                .put("protocolVersion", params.optString("protocolVersion", "2025-03-26"))
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("serverInfo", JSONObject().put("name", "android").put("version", "0.1"))
                .put("instructions", "Phone tools. screen_* need the accessibility service; call screen_read before tapping.")
            "ping" -> JSONObject()
            "tools/list" -> JSONObject().put("tools", JSONArray(TOOLS.map { it.json() }))
            "tools/call" -> {
                val name = params.optString("name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                val (text, error) = try { call(name, args) to false } catch (e: Exception) { (e.message ?: e.toString()) to true }
                JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))).put("isError", error)
            }
            else -> return respond(200, JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("error", JSONObject().put("code", -32601).put("message", "unknown method")).toString())
        }
        respond(200, JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString())
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    // ------------------------------------------------------------ tools

    private data class Tool(val name: String, val description: String, val props: Map<String, String>,
                            val required: List<String> = emptyList(), val readOnly: Boolean = true, val destructive: Boolean = false) {
        fun json(): JSONObject = JSONObject()
            .put("name", name).put("description", description)
            .put("inputSchema", JSONObject().put("type", "object")
                .put("properties", JSONObject().apply {
                    props.forEach { (k, d) ->
                        val (type, text) = d.split('|', limit = 2).let { if (it.size == 2) it[0] to it[1] else "string" to d }
                        put(k, JSONObject().put("type", type).put("description", text))
                    }
                })
                .put("required", JSONArray(required)))
            .put("annotations", JSONObject().put("readOnlyHint", readOnly).put("destructiveHint", destructive))
    }

    private companion object {
        val TOOLS = listOf(
            Tool("device_info", "Phone model, Android version, battery, storage, network and which an-claw permissions are granted.", emptyMap()),
            Tool("location_get", "Current GPS/network location (latitude, longitude, accuracy).", emptyMap()),
            Tool("contacts_search", "Search contacts by name or number.", mapOf("query" to "name or number", "limit" to "integer|default 20")),
            Tool("call_log", "Recent calls.", mapOf("limit" to "integer|default 20")),
            Tool("phone_call", "Place a phone call (dials immediately).", mapOf("number" to "phone number"), listOf("number"), readOnly = false, destructive = true),
            Tool("gallery_list", "Newest photos/videos in the gallery with id, name, date, size, path.", mapOf("kind" to "images (default) or videos", "limit" to "integer|default 20")),
            Tool("gallery_export", "Copy a gallery item (by id from gallery_list) into the workspace so other tools can read it.", mapOf("id" to "integer|media id", "kind" to "images or videos"), listOf("id"), readOnly = false),
            Tool("apps_list", "Installed launchable apps (label and package).", mapOf("query" to "filter by label/package")),
            Tool("app_open", "Open an app by package name.", mapOf("package" to "e.g. com.android.settings"), listOf("package"), readOnly = false),
            Tool("open_url", "Open a URL or intent URI (https:, tel:, mailto:, geo:, …) in the matching app.", mapOf("url" to "the URI"), listOf("url"), readOnly = false),
            Tool("screen_read", "The current screen as text: elements with text, ids, [click]/[edit] flags and their tap coordinates (@x,y).", emptyMap()),
            Tool("screen_tap", "Tap (or long-press) at screen coordinates from screen_read.", mapOf("x" to "number|x", "y" to "number|y", "long" to "boolean|long press"), listOf("x", "y"), readOnly = false, destructive = true),
            Tool("screen_click_text", "Click the element showing this text.", mapOf("text" to "visible text or description"), listOf("text"), readOnly = false, destructive = true),
            Tool("screen_swipe", "Swipe from (x1,y1) to (x2,y2).", mapOf("x1" to "number|", "y1" to "number|", "x2" to "number|", "y2" to "number|", "ms" to "integer|duration, default 300"), listOf("x1", "y1", "x2", "y2"), readOnly = false, destructive = true),
            Tool("screen_type", "Type text into the focused field.", mapOf("text" to "text to type", "append" to "boolean|keep existing text"), listOf("text"), readOnly = false, destructive = true),
            Tool("screen_action", "Global action: back, home, recents, notifications, quick_settings, power_dialog, lock_screen, screenshot.", mapOf("action" to "the action"), listOf("action"), readOnly = false, destructive = true),
            Tool("device_lock", "Lock the screen now (device admin).", emptyMap(), readOnly = false),
            Tool("setting_get", "Read a system/secure/global setting.", mapOf("namespace" to "system, secure or global", "key" to "setting name"), listOf("namespace", "key")),
            Tool("setting_put", "Change a system/secure/global setting (secure/global need WRITE_SECURE_SETTINGS, granted with adb).", mapOf("namespace" to "system, secure or global", "key" to "setting name", "value" to "new value"), listOf("namespace", "key", "value"), readOnly = false, destructive = true),
            Tool("notify", "Show a notification to the user.", mapOf("title" to "title", "text" to "body"), listOf("text"), readOnly = false),
        )
    }

    private fun has(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun need(permission: String, what: String) {
        if (!has(permission)) error("$what needs the ${permission.substringAfterLast('.')} permission: grant it in an-claw → Settings → Phone access.")
    }
    private fun a11y(): ClawAccessibilityService = ClawAccessibilityService.instance
        ?: error("Screen control is off: enable the an-claw accessibility service in an-claw → Settings → Phone access.")

    private fun call(name: String, a: JSONObject): String = when (name) {
        "device_info" -> deviceInfo()
        "location_get" -> location()
        "contacts_search" -> contacts(a.optString("query"), a.optInt("limit", 20))
        "call_log" -> callLog(a.optInt("limit", 20))
        "phone_call" -> {
            need(Manifest.permission.CALL_PHONE, "Calling")
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(a.getString("number")))))
            "calling ${a.getString("number")}"
        }
        "gallery_list" -> gallery(a.optString("kind", "images"), a.optInt("limit", 20))
        "gallery_export" -> galleryExport(a.getLong("id"), a.optString("kind", "images"))
        "apps_list" -> apps(a.optString("query"))
        "app_open" -> {
            val intent = context.packageManager.getLaunchIntentForPackage(a.getString("package")) ?: error("no launchable app ${a.getString("package")}")
            startActivity(intent); "opened ${a.getString("package")}"
        }
        "open_url" -> { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.getString("url")))); "opened ${a.getString("url")}" }
        "screen_read" -> a11y().dump()
        "screen_tap" -> if (a11y().tap(a.getDouble("x").toFloat(), a.getDouble("y").toFloat(), a.optBoolean("long"))) "tapped" else "tap failed"
        "screen_click_text" -> a11y().clickText(a.getString("text"))
        "screen_swipe" -> if (a11y().swipe(a.getDouble("x1").toFloat(), a.getDouble("y1").toFloat(), a.getDouble("x2").toFloat(),
                a.getDouble("y2").toFloat(), a.optLong("ms", 300))) "swiped" else "swipe failed"
        "screen_type" -> a11y().type(a.getString("text"), a.optBoolean("append"))
        "screen_action" -> if (a11y().global(a.getString("action"))) "done: ${a.getString("action")}" else "unknown or refused action"
        "device_lock" -> {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            if (!dpm.isAdminActive(adminComponent(context))) error("Device admin is off: enable it in an-claw → Settings → Phone access.")
            dpm.lockNow(); "locked"
        }
        "setting_get" -> settingGet(a.getString("namespace"), a.getString("key"))
        "setting_put" -> settingPut(a.getString("namespace"), a.getString("key"), a.getString("value"))
        "notify" -> {
            context.getSystemService(NotificationManager::class.java).notify(
                (System.currentTimeMillis() % 100000).toInt() + 10,
                android.app.Notification.Builder(context, AnClawApp.CHANNEL_ENGINE).setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(a.optString("title", "an-claw")).setContentText(a.getString("text"))
                    .setStyle(android.app.Notification.BigTextStyle().bigText(a.getString("text"))).build(),
            ); "notified"
        }
        else -> error("unknown tool $name")
    }

    private fun startActivity(intent: Intent) {
        // From the background, Android only lets this through with the accessibility service or "display over other apps".
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun deviceInfo(): String {
        val bm = context.getSystemService(BatteryManager::class.java)
        val stat = StatFs(context.filesDir.path)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
        val mem = android.app.ActivityManager.MemoryInfo().also { context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }
        return JSONObject()
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}").put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
            .put("soc", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE)
            .put("battery_percent", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)).put("charging", bm.isCharging)
            .put("ram_free_mb", mem.availMem / 1048576).put("ram_total_mb", mem.totalMem / 1048576)
            .put("storage_free_gb", stat.availableBytes / 1_073_741_824.0).put("network", net)
            .put("permissions", JSONObject(PhoneAccess.status(context).associate { it.title to it.granted }))
            .toString(2)
    }

    @SuppressLint("MissingPermission")
    private fun location(): String {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION) && !has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            need(Manifest.permission.ACCESS_FINE_LOCATION, "Location")
        }
        val lm = context.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) error("location is turned off on the phone")
        var loc: android.location.Location? = null
        val latch = CountDownLatch(1)
        lm.getCurrentLocation(providers.first(), null, context.mainExecutor) { loc = it; latch.countDown() }
        latch.await(20, TimeUnit.SECONDS)
        val l = loc ?: providers.firstNotNullOfOrNull { lm.getLastKnownLocation(it) } ?: error("no location fix yet")
        return JSONObject().put("latitude", l.latitude).put("longitude", l.longitude).put("accuracy_m", l.accuracy)
            .put("provider", l.provider).put("age_s", (System.currentTimeMillis() - l.time) / 1000)
            .put("map", "https://www.openstreetmap.org/?mlat=${l.latitude}&mlon=${l.longitude}").toString(2)
    }

    private fun contacts(query: String, limit: Int): String {
        need(Manifest.permission.READ_CONTACTS, "Contacts")
        val uri = if (query.isBlank()) ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        else Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query))
        val rows = mutableListOf<String>()
        context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
            while (c.moveToNext() && rows.size < limit) rows += "${c.getString(0)}: ${c.getString(1)}"
        }
        return rows.joinToString("\n").ifEmpty { "no contacts found" }
    }

    private fun callLog(limit: Int): String {
        need(Manifest.permission.READ_CALL_LOG, "The call log")
        val rows = mutableListOf<String>()
        context.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION), null, null, "${CallLog.Calls.DATE} DESC")?.use { c ->
            while (c.moveToNext() && rows.size < limit) {
                val type = when (c.getInt(2)) { CallLog.Calls.INCOMING_TYPE -> "in"; CallLog.Calls.OUTGOING_TYPE -> "out"; CallLog.Calls.MISSED_TYPE -> "missed"; else -> "other" }
                rows += "${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(c.getLong(3)))} $type ${c.getString(1) ?: ""} ${c.getString(0)} ${c.getLong(4)}s"
            }
        }
        return rows.joinToString("\n").ifEmpty { "no calls" }
    }

    private fun mediaUri(kind: String) = if (kind.startsWith("video")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    private fun needMedia(kind: String) {
        val p = if (kind.startsWith("video")) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_IMAGES
        if (Build.VERSION.SDK_INT >= 33 && !has(p) && !android.os.Environment.isExternalStorageManager()) need(p, "The gallery")
    }

    private fun gallery(kind: String, limit: Int): String {
        needMedia(kind)
        val rows = JSONArray()
        context.contentResolver.query(mediaUri(kind), arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.RELATIVE_PATH),
            null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext() && rows.length() < limit) rows.put(JSONObject().put("id", c.getLong(0)).put("name", c.getString(1))
                .put("date", java.util.Date(c.getLong(2) * 1000).toString()).put("size_kb", c.getLong(3) / 1024).put("folder", c.getString(4)))
        }
        return rows.toString(1)
    }

    private fun galleryExport(id: Long, kind: String): String {
        needMedia(kind)
        val uri = ContentUris.withAppendedId(mediaUri(kind), id)
        val name = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "media-$id"
        val app = context.applicationContext as AnClawApp
        val dir = File(app.prefs.workspace.ifBlank { app.paths.defaultWorkspace.path }, "gallery").apply { mkdirs() }
        val dest = File(dir, name.replace('/', '_'))
        context.contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { i.copyTo(it) } }
        return "copied to ${dest.path}"
    }

    private fun apps(query: String): String {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { query.isBlank() || it.first.contains(query, true) || it.second.contains(query, true) }
            .sortedBy { it.first.lowercase() }
            .joinToString("\n") { "${it.first}: ${it.second}" }.ifEmpty { "no matching apps" }
    }

    private fun settingGet(ns: String, key: String): String = when (ns) {
        "system" -> Settings.System.getString(context.contentResolver, key)
        "secure" -> Settings.Secure.getString(context.contentResolver, key)
        "global" -> Settings.Global.getString(context.contentResolver, key)
        else -> error("namespace must be system, secure or global")
    } ?: "(unset)"

    private fun settingPut(ns: String, key: String, value: String): String {
        val ok = when (ns) {
            "system" -> {
                if (!Settings.System.canWrite(context)) error("Changing system settings needs \"Modify system settings\": grant it in an-claw → Settings → Phone access.")
                Settings.System.putString(context.contentResolver, key, value)
            }
            "secure", "global" -> {
                need(Manifest.permission.WRITE_SECURE_SETTINGS, "Changing $ns settings")
                if (ns == "secure") Settings.Secure.putString(context.contentResolver, key, value)
                else Settings.Global.putString(context.contentResolver, key, value)
            }
            else -> error("namespace must be system, secure or global")
        }
        return if (ok) "$ns/$key = $value" else "the setting was not changed"
    }
}

fun adminComponent(context: Context) = ComponentName(context, ClawAdminReceiver::class.java)
