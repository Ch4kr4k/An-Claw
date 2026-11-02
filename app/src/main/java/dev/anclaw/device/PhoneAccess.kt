package dev.anclaw.device

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/** One capability the agent can be given, with how to check and grant it. */
data class Access(
    val key: String,
    val title: String,
    val description: String,
    val granted: Boolean,
    /** Runtime permissions to request (empty when granted through a Settings screen). */
    val runtime: List<String> = emptyList(),
    /** Settings screen to open instead. */
    val settingsIntent: Intent? = null,
    /** Shown when the user must do something outside the app (adb). */
    val manual: String? = null,
)

object PhoneAccess {
    private fun granted(c: Context, vararg p: String) = p.all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun a11yEnabled(c: Context): Boolean {
        val enabled = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(c, ClawAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, true) }
    }

    fun status(c: Context): List<Access> {
        val pkg = Uri.parse("package:${c.packageName}")
        val media = if (Build.VERSION.SDK_INT >= 33)
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        return listOf(
            Access("files", "All files", "Load models and work on files anywhere in shared storage.",
                Environment.isExternalStorageManager(),
                settingsIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)),
            Access("gallery", "Photos & videos", "gallery_list / gallery_export.", granted(c, *media.toTypedArray()), runtime = media),
            Access("location", "Location", "location_get.",
                granted(c, Manifest.permission.ACCESS_FINE_LOCATION),
                runtime = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
            Access("location_bg", "Location in background", "Lets location_get work while the app is in the background (choose \"Allow all the time\").",
                granted(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                runtime = listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)),
            Access("phone", "Phone & calls", "phone_call, call_log, phone state.",
                granted(c, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE),
                runtime = listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE)),
            Access("contacts", "Contacts", "contacts_search.", granted(c, Manifest.permission.READ_CONTACTS),
                runtime = listOf(Manifest.permission.READ_CONTACTS)),
            Access("accessibility", "Accessibility (screen control)",
                "screen_read / tap / swipe / type / global actions. On Android 13+, a sideloaded app first needs " +
                    "App info → ⋮ → \"Allow restricted settings\".",
                a11yEnabled(c), settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
            Access("admin", "Device admin", "device_lock.",
                c.getSystemService(DevicePolicyManager::class.java).isAdminActive(adminComponent(c)),
                settingsIntent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(c))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Lets the an-claw agent lock the screen when you ask it to.")),
            Access("overlay", "Display over other apps", "Lets the agent open apps, calls and links while an-claw is in the background.",
                Settings.canDrawOverlays(c), settingsIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)),
            Access("write_settings", "Modify system settings", "setting_put for the system namespace (brightness, timeouts…).",
                Settings.System.canWrite(c), settingsIntent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg)),
            Access("secure_settings", "Secure settings (system level)",
                "setting_put for secure/global settings. Android grants this only over adb, once.",
                granted(c, Manifest.permission.WRITE_SECURE_SETTINGS),
                manual = "adb shell pm grant ${c.packageName} android.permission.WRITE_SECURE_SETTINGS"),
            Access("battery", "Unrestricted battery", "Keeps long agent runs from being stopped by battery optimization.",
                c.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName),
                settingsIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)),
        )
    }
}
