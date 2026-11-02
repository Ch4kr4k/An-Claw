package dev.anclaw

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.anclaw.agent.ClawSettings
import dev.anclaw.engine.LlamaServer
import dev.anclaw.model.ModelStore
import java.io.File

/** Where everything lives on the device. */
class Paths(context: Context) {
    /** Executables are shipped as lib*.so and extracted here by the installer. */
    val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    val assets: android.content.res.AssetManager = context.assets
    val claw = File(nativeDir, "libclaw.so")
    val llamaServer = File(nativeDir, "libllama_server.so")
    val mcpHub = File(nativeDir, "libanclaw_hub.so")

    /** `$HOME` for claw: holds `.claw/` (settings, sessions, memory, skills). */
    val home = File(context.filesDir, "home").apply { mkdirs() }
    val clawHome = File(home, ".claw").apply { mkdirs() }
    val settingsJson = File(clawHome, "settings.json")
    val defaultWorkspace = File(context.filesDir, "workspace").apply { mkdirs() }
    val tmp = File(context.cacheDir, "tmp").apply { mkdirs() }
    val logs = File(context.filesDir, "logs").apply { mkdirs() }

    /** Imported models: app-specific external storage (large, no permission needed). */
    val modelsDir: File = (context.getExternalFilesDir("models") ?: File(context.filesDir, "models"))
        .apply { mkdirs() }
    val modelsIndex = File(context.filesDir, "models.json")
}

class AnClawApp : Application() {
    lateinit var paths: Paths
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var models: ModelStore
        private set
    lateinit var engine: LlamaServer
        private set
    lateinit var clawSettings: ClawSettings
        private set
    lateinit var ui: dev.anclaw.ui.UiSettingsStore
        private set
    lateinit var phoneTools: dev.anclaw.device.PhoneTools
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        paths = Paths(this)
        prefs = Prefs(this)
        models = ModelStore(this, paths)
        engine = LlamaServer(paths)
        clawSettings = ClawSettings(paths)
        ui = dev.anclaw.ui.UiSettingsStore(this)
        phoneTools = dev.anclaw.device.PhoneTools(this).also { runCatching { it.start() } }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ENGINE, "Model engine", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL_ENGINE = "engine"
        lateinit var instance: AnClawApp
            private set
    }
}
