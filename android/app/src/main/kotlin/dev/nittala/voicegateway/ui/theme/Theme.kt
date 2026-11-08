package dev.nittala.voicegateway.ui.theme

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF8A4B2A),
    secondary = Color(0xFF5D5F7A),
    tertiary = Color(0xFF2E6A5A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF0B48F),
    secondary = Color(0xFFC5C6E6),
    tertiary = Color(0xFF94D4BF),
)

/** Material 3 theme: the phone's dynamic colours (minSdk 31), dark or light with the system. */
@Composable
fun VoiceGatewayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/**
 * True when the system's "Remove animations" (animator duration scale 0) is on: the orb then draws a still frame.
 * Read once per composition; previews always animate.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    if (LocalInspectionMode.current) return false
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
