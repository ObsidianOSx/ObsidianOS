package obsidian.chat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** OBSIDIAN look: black background, grey accents. Always dark, never wallpaper-derived. */
private val ObsidianColors = darkColorScheme(
    primary = Color(0xFFB8B8B8),
    onPrimary = Color(0xFF000000),
    secondary = Color(0xFF8A8A8A),
    onSecondary = Color(0xFF000000),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF0E0E0E),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline = Color(0xFF3A3A3A),
    error = Color(0xFFE57373),
)

@Composable
fun ObsidianTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = ObsidianColors, content = content)
