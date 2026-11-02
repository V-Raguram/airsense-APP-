package com.airsense.companion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF0B1216)
val Panel = Color(0xFF142127)
val Mint = Color(0xFF7EE2C4)
val Muted = Color(0xFF91A6AD)
val Amber = Color(0xFFF4C574)
val Coral = Color(0xFFFF8E89)
val Border = Color(0xFF29393F)

@Composable
fun AirsenseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Mint, onPrimary = Ink,
            primaryContainer = Color(0xFF1A443B), onPrimaryContainer = Mint,
            background = Ink, onBackground = Color(0xFFE8F0F1),
            surface = Ink, onSurface = Color(0xFFE8F0F1),
            surfaceVariant = Panel, onSurfaceVariant = Muted,
            outline = Border, error = Coral,
        ), content = content,
    )
}
