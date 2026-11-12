package com.ahmad.laptopguard.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BgMain = Color(0xFF101216)
val BgCard = Color(0xFF181B22)
val BgCardElevated = Color(0xFF20242E)
val BorderSubtle = Color(0xFF2C3240)
val AccentOrange = Color(0xFFF59E0B)
val AccentOrangeStrong = Color(0xFFEA580C)
val SafeGreen = Color(0xFF10B981)
val DangerRed = Color(0xFFEF4444)
val TextPrimary = Color(0xFFF3F4F6)
val TextSecondary = Color(0xFF9CA3AF)

private val LaptopGuardColorScheme = darkColorScheme(
    primary = AccentOrange,
    onPrimary = Color(0xFF111317),
    secondary = AccentOrangeStrong,
    background = BgMain,
    onBackground = TextPrimary,
    surface = BgCard,
    onSurface = TextPrimary,
    surfaceVariant = BgCardElevated,
    onSurfaceVariant = TextSecondary,
    outline = BorderSubtle,
    error = DangerRed
)

@Composable
fun LaptopGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LaptopGuardColorScheme,
        content = content
    )
}

