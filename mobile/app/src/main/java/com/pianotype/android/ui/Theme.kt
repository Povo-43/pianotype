package com.pianotype.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private fun textOn(bg: Color): Color {
    val lum = 0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue
    return if (lum > 0.55f) Color.Black else Color.White
}

@Composable
fun PianotypeTheme(
    primary: Color,
    background: Color,
    content: @Composable () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val fg = textOn(background)
    val scheme = if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = textOn(primary),
            primaryContainer = primary.copy(alpha = 0.25f),
            background = background,
            onBackground = fg,
            surface = background,
            onSurface = fg,
            surfaceVariant = background,
            onSurfaceVariant = fg,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = textOn(primary),
            primaryContainer = primary.copy(alpha = 0.15f),
            background = background,
            onBackground = fg,
            surface = background,
            onSurface = fg,
            surfaceVariant = background,
            onSurfaceVariant = fg,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}