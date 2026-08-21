package com.docuscan.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val IndigoPrimary = Color(0xFF4B6BFF)
val IndigoDark = Color(0xFF3350CC)
val TealAccent = Color(0xFF00C2A8)
val CoralAccent = Color(0xFFFF7A59)

val LightColors = lightColorScheme(
    primary = IndigoPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1E6FF),
    onPrimaryContainer = Color(0xFF1B2A6B),
    secondary = TealAccent,
    onSecondary = Color(0xFF00352E),
    secondaryContainer = Color(0xFFC7F5EE),
    onSecondaryContainer = Color(0xFF00332E),
    tertiary = CoralAccent,
    onTertiary = Color.White,
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF1B1B22),
    surface = Color.White,
    onSurface = Color(0xFF1B1B22),
    surfaceVariant = Color(0xFFE8EAF2),
    onSurfaceVariant = Color(0xFF494A55),
    outline = Color(0xFFB6B8C6),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFFA8B8FF),
    onPrimary = Color(0xFF10245E),
    primaryContainer = Color(0xFF23346E),
    onPrimaryContainer = Color(0xFFDCE3FF),
    secondary = Color(0xFF63D9C6),
    onSecondary = Color(0xFF003730),
    secondaryContainer = Color(0xFF005047),
    onSecondaryContainer = Color(0xFFA8F1E4),
    tertiary = Color(0xFFFFB59E),
    onTertiary = Color(0xFF4D1502),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE3E2E8),
    surface = Color(0xFF181920),
    onSurface = Color(0xFFE3E2E8),
    surfaceVariant = Color(0xFF282A34),
    onSurfaceVariant = Color(0xFFC5C6D2),
    outline = Color(0xFF6F7180),
)

@Composable
fun DocuScanTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
