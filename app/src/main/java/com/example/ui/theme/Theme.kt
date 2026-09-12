package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

// MAX Official Design Tokens
object MaxTokens {
    val CardRadius = 16.dp
    val ButtonRadius = 12.dp
    val InputRadius = 12.dp
    val ScreenPadding = 20.dp
    val ElementSpacing = 12.dp
    val ButtonHeight = 52.dp
}

private val LightColorScheme =
    lightColorScheme(
        primary = MaxPrimary,
        onPrimary = OnPrimaryLight,
        secondary = MaxAccent,
        onSecondary = OnPrimaryLight,
        background = MaxBgDark,
        onBackground = MaxTextPrimary,
        surface = MaxSurfaceDark,
        onSurface = MaxTextPrimary,
        surfaceVariant = MaxSurfaceVariant,
        onSurfaceVariant = MaxTextSecondary,
        outline = MaxBorder,
        outlineVariant = MaxBorder,
        error = MaxError,
        onError = OnPrimaryLight
    )

/**
 * Primary MAX Theme for Jetpack Compose.
 * Clean, high-contrast White Light Theme with vibrant royal blue & indigo accents.
 */
@Composable
fun MaxTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}

/**
 * Backward-compatible wrapper.
 */
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaxTheme(content = content)
}
