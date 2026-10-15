package com.example.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val LocalIsDarkMode = staticCompositionLocalOf { false }

/**
 * Centralized MAX iOS-Inspired Design System Tokens.
 * Dynamically resolves Day Mode (Light) and Night Mode (Dark).
 */
object MaxIOSColors {
    // Dark Theme Surfaces
    val DarkBase = MaxBaseDark
    val DarkSurfaceGrouped = MaxSurfaceCard
    val DarkSurfaceElevated = MaxSurfaceElevated
    val DarkSurfaceGlass = MaxSurfaceGlass
    val DarkSurfaceGlassHover = MaxSurfaceElevated

    // Light Theme Surfaces (Soft Off-White & Pure White Cards)
    val LightBase = Color(0xFFFAFAFC)          // Pearlescent White Canvas (#FAFAFC)
    val LightSurfaceGrouped = Color(0xFFFFFFFF) // Pure White Card Surface
    val LightSurfaceElevated = Color(0xFFFFF1F2)// Soft Rose Elevated Surface
    val LightSurfaceGlass = Color(0xF2FFFFFF)   // Frosted White Milk Glass
    val LightSurfaceGlassHover = Color(0xFFFCE7F3)

    // Dynamic Context-Aware Surfaces
    val Base @Composable get() = if (LocalIsDarkMode.current) DarkBase else LightBase
    val SurfaceGrouped @Composable get() = if (LocalIsDarkMode.current) DarkSurfaceGrouped else LightSurfaceGrouped
    val SurfaceElevated @Composable get() = if (LocalIsDarkMode.current) DarkSurfaceElevated else LightSurfaceElevated
    val SurfaceGlass @Composable get() = if (LocalIsDarkMode.current) DarkSurfaceGlass else LightSurfaceGlass

    // High-Contrast Typography Palette
    val TextPrimaryDark = MaxTextPrimary
    val TextSecondaryDark = MaxTextSecondary
    val TextTertiaryDark = MaxTextTertiary
    val TextQuaternaryDark = MaxTextTertiary

    val TextPrimaryLight = Color(0xFF0F172A)    // Slate 900 High-Contrast Black/Dark Text
    val TextSecondaryLight = Color(0xFF475569)  // Slate 600 Subheading Text
    val TextTertiaryLight = Color(0xFF64748B)   // Slate 500 Caption Text

    val TextPrimary @Composable get() = if (LocalIsDarkMode.current) TextPrimaryDark else TextPrimaryLight
    val TextSecondary @Composable get() = if (LocalIsDarkMode.current) TextSecondaryDark else TextSecondaryLight
    val TextTertiary @Composable get() = if (LocalIsDarkMode.current) TextTertiaryDark else TextTertiaryLight

    // Specular Highlights & Hairline Borders (0.5dp - 1dp)
    val HairlineDark = MaxBorderHairline
    val SpecularEdgeDark = MaxBorderSpecular
    val HairlineLight = Color(0xFFFCE7F3)        // Soft Rose Hairline Border (#FCE7F3)
    val SpecularEdgeLight = Color(0xFFFBCFE8)    // Focused Rose Edge Border (#FBCFE8)

    val Hairline @Composable get() = if (LocalIsDarkMode.current) HairlineDark else HairlineLight
    val SpecularEdge @Composable get() = if (LocalIsDarkMode.current) SpecularEdgeDark else SpecularEdgeLight

    // Flagship Vibrant Accent Hierarchy (Luminous White & Pink)
    val SystemBlue = NeonPink
    val SystemBlueLight = NeonPink
    val SystemIndigo = MagentaRose
    val SystemTeal = BrightRose
    val SystemGreen = MaxSuccessApple
    val SystemAmber = MaxWarningApple
    val SystemRed = MaxDestructiveApple
    val SystemPurple = MagentaRose
}

object MaxIOSShapes {
    val RadiusSmall: Dp = 8.dp
    val RadiusMedium: Dp = 14.dp
    val RadiusLarge: Dp = 20.dp
    val RadiusXLarge: Dp = 26.dp
    val RadiusPill: Dp = 32.dp

    val ShapeSmall = RoundedCornerShape(RadiusSmall)
    val ShapeMedium = RoundedCornerShape(RadiusMedium)
    val ShapeLarge = RoundedCornerShape(RadiusLarge)
    val ShapeXLarge = RoundedCornerShape(RadiusXLarge)
    val ShapePill = RoundedCornerShape(RadiusPill)
    val ShapeCircle = CircleShape
}

object MaxIOSSpacing {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
    val xxxl: Dp = 32.dp
    val screenPadding: Dp = 16.dp
    val dockHeight: Dp = 64.dp
}

private val IOSDarkColorScheme = darkColorScheme(
    primary = MaxIOSColors.SystemBlue,
    onPrimary = MaxIOSColors.TextPrimaryDark,
    secondary = MaxIOSColors.SystemTeal,
    onSecondary = MaxIOSColors.DarkBase,
    tertiary = MaxIOSColors.SystemIndigo,
    onTertiary = MaxIOSColors.TextPrimaryDark,
    background = MaxIOSColors.DarkBase,
    onBackground = MaxIOSColors.TextPrimaryDark,
    surface = MaxIOSColors.DarkSurfaceGrouped,
    onSurface = MaxIOSColors.TextPrimaryDark,
    surfaceVariant = MaxIOSColors.DarkSurfaceElevated,
    onSurfaceVariant = MaxIOSColors.TextSecondaryDark,
    outline = MaxIOSColors.HairlineDark,
    outlineVariant = MaxIOSColors.SpecularEdgeDark,
    error = MaxIOSColors.SystemRed,
    onError = MaxIOSColors.TextPrimaryDark
)

private val IOSLightColorScheme = lightColorScheme(
    primary = MaxIOSColors.SystemBlue,
    onPrimary = Color.White,
    secondary = MaxIOSColors.SystemTeal,
    onSecondary = Color.White,
    tertiary = MaxIOSColors.SystemIndigo,
    onTertiary = Color.White,
    background = MaxIOSColors.LightBase,
    onBackground = MaxIOSColors.TextPrimaryLight,
    surface = MaxIOSColors.LightSurfaceGrouped,
    onSurface = MaxIOSColors.TextPrimaryLight,
    surfaceVariant = MaxIOSColors.LightSurfaceElevated,
    onSurfaceVariant = MaxIOSColors.TextSecondaryLight,
    outline = MaxIOSColors.HairlineLight,
    outlineVariant = MaxIOSColors.SpecularEdgeLight,
    error = MaxIOSColors.SystemRed,
    onError = Color.White
)

/**
 * Flagship MAX iOS-Native Theme Provider.
 */
@Composable
fun MaxIOSTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) IOSDarkColorScheme else IOSLightColorScheme
    CompositionLocalProvider(LocalIsDarkMode provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
