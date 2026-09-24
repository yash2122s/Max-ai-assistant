package com.example.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Centralized MAX iOS-Inspired Design System Tokens.
 * Embodies an Apple-style, calm, human-centered luxury design language.
 */
object MaxIOSColors {
    // Dark Theme Surfaces (Deep Obsidian & Translucent Glass)
    val DarkBase = Color(0xFF07090E)              // Deepest Obsidian Black
    val DarkSurfaceGrouped = Color(0xFF121722)     // Inset Grouped Section Background
    val DarkSurfaceElevated = Color(0xFF1A2232)    // Elevated Card & Dock Background
    val DarkSurfaceGlass = Color(0xD9121722)       // 85% Translucent Obsidian Glass
    val DarkSurfaceGlassHover = Color(0xF01E2738)  // Highlighted Glass State
    
    // Light Theme Surfaces (Soft Off-White & Translucent Milk Glass)
    val LightBase = Color(0xFFF2F2F7)             // iOS System Grouped Background
    val LightSurfaceGrouped = Color(0xFFFFFFFF)    // Inset Grouped Section Background
    val LightSurfaceElevated = Color(0xFFFFFFFF)   // Elevated Card Background
    val LightSurfaceGlass = Color(0xE6FFFFFF)      // 90% Translucent White Glass
    val LightSurfaceGlassHover = Color(0xF2F2F2F7) // Highlighted Light State

    // High-Contrast Apple Typography Palette
    val TextPrimaryDark = Color(0xFFFFFFFF)        // 100% Crisp White
    val TextSecondaryDark = Color(0xFF94A3B8)      // Slate 400 (Body & Subtitles)
    val TextTertiaryDark = Color(0xFF64748B)       // Slate 500 (Footnotes & Captions)
    val TextQuaternaryDark = Color(0xFF475569)     // Slate 600 (Placeholders)

    val TextPrimaryLight = Color(0xFF0F172A)       // Deep Slate 900
    val TextSecondaryLight = Color(0xFF475569)     // Slate 600
    val TextTertiaryLight = Color(0xFF94A3B8)      // Slate 400

    // Specular Highlights & Hairline Borders (0.5dp - 1dp)
    val HairlineDark = Color(0xFF232D42)          // Crisp Section Divider / Hairline
    val SpecularEdgeDark = Color(0xFF384968)      // Top Specular Highlight
    val HairlineLight = Color(0xFFE2E8F0)         // Soft Light Divider
    val SpecularEdgeLight = Color(0xFFCBD5E1)     // Soft Specular Light

    // Apple-Style Vibrant Accent Hierarchy
    val SystemBlue = Color(0xFF0A84FF)            // iOS Cyber Blue (Primary Actions)
    val SystemBlueLight = Color(0xFF007AFF)       // Light Mode System Blue
    val SystemIndigo = Color(0xFF5E5CE6)          // iOS Indigo / Purple (Thinking / AI)
    val SystemTeal = Color(0xFF00D2FF)            // Titanium Cyan (Active Glow & Accents)
    val SystemGreen = Color(0xFF30D158)           // iOS Emerald Green (Online / Verified / Success)
    val SystemAmber = Color(0xFFFF9F0A)           // iOS Amber Gold (Warning / Deep Mode)
    val SystemRed = Color(0xFFFF453A)             // iOS Coral Red (Destructive / Error)
    val SystemPurple = Color(0xFFBF5AF2)          // Apple Intelligence Purple
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

/**
 * Flagship MAX iOS-Native Theme Provider.
 */
@Composable
fun MaxIOSTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = IOSDarkColorScheme,
        typography = Typography,
        content = content
    )
}
