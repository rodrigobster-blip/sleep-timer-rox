package com.rox.sleeptimer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val RoxBackground = Color(0xFF0B0C10)
val RoxSurface = Color(0xFF14161D)
val RoxSurfaceVariant = Color(0xFF1C1F29)
val RoxOutline = Color(0xFF2B3040)
val RoxAmber = Color(0xFFE2A458)
val RoxAmberDark = Color(0xFF2A1D0E)
val RoxTextPrimary = Color(0xFFF1F2F6)
val RoxTextMuted = Color(0xFF9EA4B8)
val RoxDanger = Color(0xFFE06C75)

private val RoxColorScheme = darkColorScheme(
    primary = RoxAmber,
    onPrimary = RoxBackground,
    primaryContainer = RoxAmberDark,
    onPrimaryContainer = RoxAmber,
    background = RoxBackground,
    onBackground = RoxTextPrimary,
    surface = RoxSurface,
    onSurface = RoxTextPrimary,
    surfaceVariant = RoxSurfaceVariant,
    onSurfaceVariant = RoxTextMuted,
    outline = RoxOutline,
    error = RoxDanger,
    onError = RoxBackground
)

@Composable
fun SleepTimerRoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RoxColorScheme,
        content = content
    )
}
