package dev.anclaw.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark"), BLACK("AMOLED black") }
enum class ChatFont(val label: String) { SANS("Sans"), SERIF("Serif"), MONO("Mono") }
enum class Density(val label: String) { COMPACT("Compact"), COMFORTABLE("Comfortable"), SPACIOUS("Spacious") }
enum class ToolDisplay(val label: String) { COLLAPSED("Collapsed"), EXPANDED("Expanded"), MINIMAL("One line"), HIDDEN("Hidden") }
enum class UserStyle(val label: String) { BUBBLE("Bubble"), PLAIN("Plain") }

/** Accent presets (name → ARGB). Clay is Claude's orange. */
val ACCENTS = linkedMapOf(
    "Clay" to 0xFFD97757, "Ocean" to 0xFF4F8FD6, "Forest" to 0xFF4FA36B, "Violet" to 0xFF9B7BE0,
    "Rose" to 0xFFE0708F, "Amber" to 0xFFD9A441, "Teal" to 0xFF3AAFA9, "Mono" to 0xFF9AA0A6,
)

/** Everything the user can tune about the look and feel. Changes apply live. */
data class UiSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val accent: Long = ACCENTS.getValue("Clay"),
    /** Material You colours from the wallpaper instead of the accent. */
    val dynamicColor: Boolean = false,
    val font: ChatFont = ChatFont.SANS,
    val fontScale: Float = 1.0f,
    val lineSpacing: Float = 1.0f,
    val density: Density = Density.COMFORTABLE,
    val userStyle: UserStyle = UserStyle.BUBBLE,
    val toolDisplay: ToolDisplay = ToolDisplay.COLLAPSED,
    val showThinking: Boolean = true,
    val wrapCode: Boolean = false,
    val showUsage: Boolean = true,
    val enterSends: Boolean = false,
    val haptics: Boolean = true,
    /** Extra instructions for every chat (like Claude's personal preferences). */
    val personalPrompt: String = "",
    /** Starter prompts on an empty chat. */
    val starters: List<String> = listOf(
        "Find a GGUF of a small coding model on Hugging Face and download it",
        "Summarize the open issues of ggml-org/llama.cpp",
        "Explain what this phone's /proc/cpuinfo says about its CPU",
        "Write a shell script that backs up my workspace",
    ),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("theme", theme.name).put("accent", accent).put("dynamicColor", dynamicColor)
        .put("font", font.name).put("fontScale", fontScale.toDouble()).put("lineSpacing", lineSpacing.toDouble())
        .put("density", density.name).put("userStyle", userStyle.name).put("toolDisplay", toolDisplay.name)
        .put("showThinking", showThinking).put("wrapCode", wrapCode).put("showUsage", showUsage)
        .put("enterSends", enterSends).put("haptics", haptics).put("personalPrompt", personalPrompt)
        .put("starters", JSONArray(starters))

    companion object {
        private inline fun <reified E : Enum<E>> JSONObject.enum(key: String, default: E): E =
            runCatching { enumValueOf<E>(getString(key)) }.getOrDefault(default)

        fun fromJson(o: JSONObject): UiSettings {
            val d = UiSettings()
            return UiSettings(
                theme = o.enum("theme", d.theme),
                accent = o.optLong("accent", d.accent),
                dynamicColor = o.optBoolean("dynamicColor", d.dynamicColor),
                font = o.enum("font", d.font),
                fontScale = o.optDouble("fontScale", 1.0).toFloat(),
                lineSpacing = o.optDouble("lineSpacing", 1.0).toFloat(),
                density = o.enum("density", d.density),
                userStyle = o.enum("userStyle", d.userStyle),
                toolDisplay = o.enum("toolDisplay", d.toolDisplay),
                showThinking = o.optBoolean("showThinking", d.showThinking),
                wrapCode = o.optBoolean("wrapCode", d.wrapCode),
                showUsage = o.optBoolean("showUsage", d.showUsage),
                enterSends = o.optBoolean("enterSends", d.enterSends),
                haptics = o.optBoolean("haptics", d.haptics),
                personalPrompt = o.optString("personalPrompt", ""),
                starters = o.optJSONArray("starters")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: d.starters,
            )
        }
    }
}

class UiSettingsStore(context: Context) {
    private val sp = context.getSharedPreferences("anclaw-ui", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(
        runCatching { UiSettings.fromJson(JSONObject(sp.getString("ui", "{}")!!)) }.getOrDefault(UiSettings())
    )
    val settings: StateFlow<UiSettings> = _settings

    fun update(change: (UiSettings) -> UiSettings) {
        val next = change(_settings.value)
        _settings.value = next
        sp.edit().putString("ui", next.toJson().toString()).apply()
    }

    fun reset() = update { UiSettings() }
}
