package app.lightsout.ui

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val NightColors = darkColorScheme(
    primary = Color(0xFFF6E7B0),
    onPrimary = Color(0xFF2A2410),
    background = Color(0xFF111628),
    surface = Color(0xFF111628),
    surfaceContainer = Color(0xFF1B2340),
    surfaceContainerHighest = Color(0xFF232C4D),
)

/** Always dark: this app is opened at bedtime. */
@Composable
fun LightsOutTheme(content: @Composable () -> Unit) {
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(LocalContext.current)
    } else {
        NightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
