package dev.anclaw.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em

/** The current [UiSettings], for composables deep in the tree. */
val LocalUi = staticCompositionLocalOf { UiSettings() }
val LocalIsDark = staticCompositionLocalOf { true }

/** Claude-desktop-like warm neutrals. */
private fun darkScheme(accent: Color, black: Boolean): ColorScheme {
    val bg = if (black) Color.Black else Color(0xFF262624)
    val surface = if (black) Color(0xFF0E0E0E) else Color(0xFF30302E)
    return darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF1A1A18),
        primaryContainer = accent.copy(alpha = 0.22f).compositeOver(bg),
        onPrimaryContainer = Color(0xFFF5F4EE),
        secondary = Color(0xFF9EB8D0),
        secondaryContainer = accent.copy(alpha = 0.28f).compositeOver(bg),
        onSecondaryContainer = Color(0xFFF5F4EE),
        tertiaryContainer = accent.copy(alpha = 0.28f).compositeOver(bg),
        background = bg,
        surface = bg,
        surfaceVariant = surface,
        surfaceContainerLowest = bg,
        surfaceContainerLow = if (black) Color(0xFF080808) else Color(0xFF2B2B29),
        surfaceContainer = if (black) Color(0xFF0B0B0B) else Color(0xFF1F1E1D),
        surfaceContainerHigh = surface,
        surfaceContainerHighest = if (black) Color(0xFF161616) else Color(0xFF3A3A37),
        onBackground = Color(0xFFF5F4EE),
        onSurface = Color(0xFFF5F4EE),
        onSurfaceVariant = Color(0xFFB7B5A9),
        outline = Color(0xFF4A4945),
        outlineVariant = Color(0xFF3A3936),
        error = Color(0xFFFF8A80),
    )
}

private fun lightScheme(accent: Color): ColorScheme = lightColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = accent.copy(alpha = 0.16f).compositeOver(Color(0xFFFAF9F5)),
    onPrimaryContainer = Color(0xFF1F1E1D),
    secondary = Color(0xFF3B6A8F),
    secondaryContainer = accent.copy(alpha = 0.20f).compositeOver(Color(0xFFFAF9F5)),
    onSecondaryContainer = Color(0xFF1F1E1D),
    tertiaryContainer = accent.copy(alpha = 0.20f).compositeOver(Color(0xFFFAF9F5)),
    background = Color(0xFFFAF9F5),
    surface = Color(0xFFFAF9F5),
    surfaceVariant = Color(0xFFF0EEE6),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F4EE),
    surfaceContainer = Color(0xFFF0EEE6),
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFE8E6DC),
    onSurface = Color(0xFF1F1E1D),
    onSurfaceVariant = Color(0xFF73726C),
    outline = Color(0xFFD1CFC5),
    outlineVariant = Color(0xFFE3E1D8),
)

/** Monospace/code colours for the current scheme. */
object CodeColors {
    @Composable fun background() = if (LocalIsDark.current) Color(0xFF1A1A18) else Color(0xFFF0EEE6)
    @Composable fun inline() = if (LocalIsDark.current) Color(0xFF3A3A37) else Color(0xFFE8E6DC)
}

fun ChatFont.family(): FontFamily = when (this) {
    ChatFont.SANS -> FontFamily.SansSerif
    ChatFont.SERIF -> FontFamily.Serif
    ChatFont.MONO -> FontFamily.Monospace
}

private fun TextStyle.tuned(ui: UiSettings, family: FontFamily?): TextStyle {
    fun TextUnit.scaled() = if (this == TextUnit.Unspecified) this else this * ui.fontScale
    return copy(
        fontFamily = family ?: fontFamily,
        fontSize = fontSize.scaled(),
        lineHeight = if (lineHeight == TextUnit.Unspecified) 1.45.em * ui.lineSpacing else lineHeight.scaled() * ui.lineSpacing,
    )
}

@Composable
fun AnClawTheme(ui: UiSettings, content: @Composable () -> Unit) {
    val dark = when (ui.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.BLACK -> true
    }
    val accent = Color(ui.accent)
    val scheme = when {
        ui.dynamicColor && Build.VERSION.SDK_INT >= 31 -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx).let { if (ui.theme == ThemeMode.BLACK) it.copy(background = Color.Black, surface = Color.Black) else it }
            else dynamicLightColorScheme(ctx)
        }
        dark -> darkScheme(accent, ui.theme == ThemeMode.BLACK)
        else -> lightScheme(accent)
    }
    val base = Typography()
    // Body text (the conversation) uses the chosen chat font; UI chrome stays sans.
    val chat = ui.font.family()
    val typography = base.copy(
        bodyLarge = base.bodyLarge.tuned(ui, chat),
        bodyMedium = base.bodyMedium.tuned(ui, chat),
        bodySmall = base.bodySmall.tuned(ui, null),
        titleLarge = base.titleLarge.tuned(ui, null),
        titleMedium = base.titleMedium.tuned(ui, null),
        titleSmall = base.titleSmall.tuned(ui, null),
        labelLarge = base.labelLarge.tuned(ui, null),
        labelMedium = base.labelMedium.tuned(ui, null),
        labelSmall = base.labelSmall.tuned(ui, null),
        headlineMedium = base.headlineMedium.tuned(ui, if (ui.font == ChatFont.MONO) null else FontFamily.Serif),
    )
    CompositionLocalProvider(LocalUi provides ui, LocalIsDark provides dark) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
