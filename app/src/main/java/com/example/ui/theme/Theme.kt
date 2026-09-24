package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

// MAX Official Design Tokens
object MaxTokens {
    val CardRadius = 20.dp
    val ButtonRadius = 14.dp
    val InputRadius = 14.dp
    val ScreenPadding = 16.dp
    val ElementSpacing = 14.dp
    val ButtonHeight = 50.dp
}

private val RoyalDarkColorScheme = darkColorScheme(
    primary = MaxPrimaryRoyal,
    onPrimary = MaxTextPrimary,
    secondary = MaxAccentCyan,
    onSecondary = MaxBaseDark,
    tertiary = MaxRoyalGold,
    onTertiary = MaxBaseDark,
    background = MaxBaseDark,
    onBackground = MaxTextPrimary,
    surface = MaxSurfaceCard,
    onSurface = MaxTextPrimary,
    surfaceVariant = MaxSurfaceElevated,
    onSurfaceVariant = MaxTextSecondary,
    outline = MaxBorderHairline,
    outlineVariant = MaxBorderHairline,
    error = MaxDestructiveApple,
    onError = MaxTextPrimary
)

/**
 * Flagship MAX iOS-Native Theme for Jetpack Compose.
 * Deep obsidian dark luxury background with titanium cyan and cyber royal blue accents.
 */
@Composable
fun MaxTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = RoyalDarkColorScheme,
        typography = Typography,
        content = content
    )
}

/**
 * Backward-compatible wrapper.
 */
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaxTheme(content = content)
}

