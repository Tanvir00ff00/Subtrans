package com.subtrans.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

private val Violet = Color(0xFF7C5CFF)
private val VioletDim = Color(0xFF4C2FD0)
private val Ink = Color(0xFF0A0A0F)
private val Surface = Color(0xFF15151E)
private val OnInk = Color(0xFFE7E7EE)

private val DarkScheme = darkColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = VioletDim,
    onPrimaryContainer = Color.White,
    background = Ink,
    onBackground = OnInk,
    surface = Ink,
    onSurface = OnInk,
    surfaceVariant = Surface,
    onSurfaceVariant = Color(0xFF9E9EB0),
    outline = Color(0xFF32323F),
    error = Color(0xFFFF6B6B),
)

private val LightScheme = lightColorScheme(
    primary = VioletDim,
    background = Color(0xFFFAFAFC),
    surface = Color.White,
)

/**
 * Bengali sits lower and needs more line height than Latin at the same size,
 * so the body styles are loosened rather than left at the Material defaults.
 */
private val AppTypography = Typography().let { base ->
    base.copy(
        bodyLarge = base.bodyLarge.copy(lineHeight = 26.sp),
        bodyMedium = base.bodyMedium.copy(lineHeight = 24.sp),
        bodySmall = base.bodySmall.copy(lineHeight = 20.sp),
        labelLarge = base.labelLarge.copy(lineHeight = 22.sp),
    )
}

@Composable
fun SubTransTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content,
    )
}

/** Monospace style for file names and ids, where alignment matters. */
val MonoSmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp)
