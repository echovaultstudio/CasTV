package app.castv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.castv.data.Settings

@Immutable
data class Ui(
    val accent: Color,
    val panel: Color,
    val panelStrong: Color,
    val text: Color = Color(0xFFF2F2F2),
    val dim: Color = Color(0xFFA8A8A8),
    val focus: Color,
    val selection: Color,
    val scale: Float,
)

val accents = linkedMapOf(
    "blue" to Color(0xFF3D8BFF), "indigo" to Color(0xFF5C6BC0), "purple" to Color(0xFF9C5CF0), "pink" to Color(0xFFEC5A9A),
    "red" to Color(0xFFE5484D), "orange" to Color(0xFFF08A24), "amber" to Color(0xFFF5B300), "yellow" to Color(0xFFE8D54A),
    "lime" to Color(0xFF9CCC3C), "green" to Color(0xFF3CB371), "teal" to Color(0xFF1FA89A), "cyan" to Color(0xFF22B8D6),
    "brown" to Color(0xFF9A6B4F), "grey" to Color(0xFF8C8C8C), "blue_grey" to Color(0xFF6E8798), "white" to Color(0xFFEDEDED),
)

val LocalUi = staticCompositionLocalOf {
    Ui(accent = accents.getValue("blue"), panel = Color(0xCC101318), panelStrong = Color(0xF0101318), focus = accents.getValue("blue"), selection = Color(0x553D8BFF), scale = 1f)
}

fun uiFrom(s: Settings): Ui {
    val accent = accents[s.accent] ?: accents.getValue("blue")
    val alpha = (1f - s.uiTransparency.coerceIn(0, 90) / 100f)
    val base = Color(0xFF0E1116)
    return Ui(
        accent = accent,
        panel = base.copy(alpha = alpha.coerceIn(0.1f, 1f)),
        panelStrong = base.copy(alpha = (alpha + 0.15f).coerceAtMost(1f)),
        focus = accent,
        selection = accent.copy(alpha = 0.35f),
        scale = when (s.fontSize) { "small" -> 0.88f; "large" -> 1.15f; else -> 1f },
    )
}

@Composable
fun CasTheme(ui: Ui, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    MaterialTheme(
        colorScheme = darkColorScheme(primary = ui.accent, secondary = ui.accent, background = Color.Black, surface = Color(0xFF15181E)),
    ) {
        CompositionLocalProvider(
            LocalUi provides ui,
            LocalDensity provides Density(d.density, d.fontScale * ui.scale),
            content = content,
        )
    }
}
