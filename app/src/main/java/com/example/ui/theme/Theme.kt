package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
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
    outlineVariant = MaxBorderSpecular,
    error = MaxDestructiveApple,
    onError = MaxTextPrimary
)

private val RoyalLightColorScheme = lightColorScheme(
    primary = NeonPink,
    onPrimary = Color.White,
    secondary = MagentaRose,
    onSecondary = Color.White,
    tertiary = MaxRoyalGoldLight,
    onTertiary = Color.White,
    background = LuminousCanvas,          // Pearlescent White Canvas (#FAFAFC)
    onBackground = SlateInkPrimary,       // Slate 900 Primary Text (#0F172A)
    surface = LuminousWhite,              // Pure White Card Surface (#FFFFFF)
    onSurface = SlateInkPrimary,          // Slate 900 Primary Text (#0F172A)
    surfaceVariant = SoftRoseTint,        // Soft Rose Elevated Surface (#FFF1F2)
    onSurfaceVariant = SlateInkSecondary, // Slate 600 Secondary Text (#475569)
    outline = SoftRoseBorder,             // Soft Rose Hairline Border (#FCE7F3)
    outlineVariant = SoftRoseBorderStrong,// Focused Rose Border (#FBCFE8)
    error = MaxDestructiveApple,
    onError = Color.White
)

/**
 * Flagship MAX iOS-Native Theme for Jetpack Compose.
 * Supports Day Mode (Light) and Night Mode (Dark).
 */
@Composable
fun MaxTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) RoyalDarkColorScheme else RoyalLightColorScheme
    val extra = if (darkTheme) MaxDarkExtra else MaxLightExtra
    CompositionLocalProvider(
        LocalIsDarkMode provides darkTheme,
        LocalMaxExtra provides extra,
        LocalMaxMotion provides MaxMotion,
        LocalMaxSemanticColors provides MaxSemanticColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography
        ) {
            androidx.compose.material3.Surface(
                color = colorScheme.background,
                contentColor = colorScheme.onBackground,
                content = content
            )
        }
    }
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
    MaxTheme(darkTheme = darkTheme, content = content)
}
