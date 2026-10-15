package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

// ============================================================================
// MAX "ROYAL OBSIDIAN & TITANIUM CYAN" DESIGN SYSTEM (iOS-Native Dark Luxury)
// ============================================================================

// Base Layers & Surfaces
val MaxBaseDark = Color(0xFF07090E)          // Deepest True Obsidian Black
val MaxSurfaceCard = Color(0xFF111622)       // Inset Grouped Section / Card Surface
val MaxSurfaceElevated = Color(0xFF182030)   // Elevated Sheet / Modal / Input Surface
val MaxSurfaceGlass = Color(0xEB111622)      // 92% Frosted Obsidian Glass for Bars & Pills
val MaxSurfaceGlassBorder = Color(0x3300D2FF)// Frosted Glass Specular Outline

// Royal & Cyber Accent Colors
val MaxPrimaryRoyal = Color(0xFF0A84FF)      // iOS Cyber Royal Blue (Primary Accent)
val MaxAccentCyan = Color(0xFF00D2FF)        // Titanium Cyan (Specular Sheen & Active Glow)
val MaxRoyalGold = Color(0xFFFFD700)         // Royal Amber Gold (Premium Sovereignty)
val MaxGold = MaxRoyalGold                   // Alias for Royal Gold
val MaxRoyalGoldDark = Color(0xFFB8860B)     // Dark Royal Gold for muted badges
val MaxRoyalGoldLight = Color(0xFFB45309)    // Accessible Amber Gold (>= 3:1 on white canvas)
val MaxRoyalIndigo = Color(0xFF5E5CE6)       // iOS Indigo

// Text & Content Hierarchy (iOS High-Contrast Scale)
val MaxTextPrimary = Color(0xFFFFFFFF)       // 100% Crisp White (Titles & Key Content)
val MaxTextSecondary = Color(0xFF94A3B8)     // Slate 400 (Body, Subheadings, Descriptions)
val MaxTextTertiary = Color(0xFF9AA9BC)      // Legible muted captions on obsidian
val MaxTextMuted = MaxTextTertiary

// Hairline Borders & Specular Edges
val MaxBorderHairline = Color(0xFF232D42)    // Crisp 0.5dp/1dp Inset Section Divider
val MaxBorderSpecular = Color(0xFF384968)    // Top Light-Catching Specular Edge
val MaxBorder = MaxBorderHairline

// iOS Semantic States
val MaxSuccessApple = Color(0xFF30D158)      // iOS Emerald Green (Online / Verified)
val MaxWarningApple = Color(0xFFFF9F0A)      // iOS Amber Gold (Trial / Warning)
val MaxDestructiveApple = Color(0xFFFF453A)  // iOS Coral Red (Revoked / Error / Delete)

val MaxSuccess = MaxSuccessApple
val MaxError = MaxDestructiveApple

// ============================================================================
// Backward-Compatible Aliases (Theme Smooth Transition)
// ============================================================================
val MaxPrimary = MaxPrimaryRoyal
val MaxSecondary = MaxAccentCyan
val MaxAccent = MaxAccentCyan
val MaxBgDark = MaxBaseDark
val MaxBgLight = MaxBaseDark
val MaxSurface = MaxSurfaceCard
val MaxSurfaceDark = MaxSurfaceCard
val MaxSurfaceVariant = MaxSurfaceElevated

val BgDark = MaxBaseDark
val SurfaceDark = MaxSurfaceCard
val SurfaceDarker = MaxSurfaceElevated
val BorderDark = MaxBorderHairline
val TextLight = MaxTextPrimary
val TextDark = MaxTextPrimary
val TextMuted = MaxTextMuted
val TextMutedDark = MaxTextMuted
val PrimaryIndigo = MaxPrimaryRoyal
val PrimaryLight = MaxPrimaryRoyal
val OnPrimaryDark = Color.White
val OnPrimaryLight = Color.White

val BgLight = MaxBaseDark
val SurfaceLight = MaxSurfaceCard
val SurfaceLighter = MaxSurfaceElevated
val BorderLight = MaxBorderHairline
val PrimaryIndigoLight = MaxPrimaryRoyal

val Emerald400 = MaxSuccessApple
val Blue600 = MaxPrimaryRoyal
val Purple600 = MaxRoyalIndigo
val GradientStart = MaxPrimaryRoyal
val GradientEnd = MaxAccentCyan

// Aesthetic & MAX Tokens (Stitch Luminous Neural White & Pink Theme)
val LuminousCanvas = Color(0xFFFAFAFC)       // Crisp Pearlescent White Canvas
val LuminousWhite = Color(0xFFFFFFFF)        // Pure White Surface
val LuminousCardElevated = Color(0xFFFFFFFF) // Suspended Pure White Card
val LuminousDockGlass = Color(0xF2FFFFFF)    // Frosted White Milk Glass Dock
val LuminousDockBorder = Color(0xFFFCE7F3)   // Frosted Glass Dock Border

val NeonPink = Color(0xFFFF2E93)             // Radiant Electric Neon Pink (#FF2E93)
val MagentaRose = Color(0xFFE11D48)          // Deep Contrast Magenta Rose (#E11D48)
val BrightRose = Color(0xFFF43F5E)           // Energetic Rose Accent (#F43F5E)
val SoftRoseTint = Color(0xFFFFF1F2)         // Soft Rose Pill Background (#FFF1F2)
val SoftRoseBorder = Color(0xFFFCE7F3)       // Delicate Crystalline Border (#FCE7F3)
val SoftRoseBorderStrong = Color(0xFFFBCFE8) // Focused Rose Border (#FBCFE8)

val SlateInkPrimary = Color(0xFF0F172A)      // Deep Slate 900 Text
val SlateInkSecondary = Color(0xFF475569)    // Slate 600 Body Text
val SlateInkMuted = Color(0xFF64748B)        // Slate 500 Metadata Text

val AetherBaseDark = Color(0xFFFAFAFC)       // Pearlescent White Base Canvas
val AetherSurface = Color(0xFFFFFFFF)        // Pure White Surface
val AetherSurfaceGlass = Color(0xF2FFFFFF)   // Frosted Glass White
val AetherBorder = Color(0xFFFCE7F3)         // Soft Rose Hairline
val AetherBorderNeon = Color(0x66FF2E93)     // Glowing Neon Pink Border
val AetherNeonCyan = NeonPink                // Electric Pink Accent (#FF2E93)
val AetherNeonCyanDim = BrightRose           // Bright Rose (#F43F5E)
val AetherNeonPurple = MagentaRose           // Deep Magenta (#E11D48)
val AetherGradientAi = listOf(NeonPink, MagentaRose)
val AetherUserBubbleBg = SoftRoseTint        // Soft Rose Chat Bubble (#FFF1F2)

val NeonBlue = NeonPink
val NeonCyan = NeonPink
val NeonPurple = MagentaRose
val NeonGreen = MaxSuccessApple
val NeonGold = MaxRoyalGold
val GradientBorderColors = listOf(MagentaRose, NeonPink, MagentaRose)

val SlateBackground = LuminousCanvas
val SlateSurface = LuminousWhite
val SlateBorder = SoftRoseBorder

val AccentCyan = MaxAccentCyan
val AccentTeal = Color(0xFF00C7BE)
val AccentAmber = MaxWarningApple
val AccentPurple = MaxRoyalIndigo
val AccentIndigo = MaxPrimaryRoyal
val AccentBlue = MaxPrimaryRoyal

val StatusGrantedBg = Color(0x1F30D158)
val StatusGrantedText = MaxSuccessApple

val JarvisAmber = MaxRoyalGold
val JarvisGold = MaxRoyalGold
val JarvisBrightGold = Color(0xFFFFE600)
val JarvisFieryOrange = Color(0xFFFF6B00)
val JarvisDarkBg = MaxBaseDark
val JarvisCardBg = MaxSurfaceCard
val JarvisBorderAmber = Color(0x40FFD700)

@Immutable
data class MaxExtraColors(
    val textSecondary: Color,
    val textTertiary: Color,
    val link: Color,
    val borderSubtle: Color,   // decorative card borders
    val borderStrong: Color,   // essential control borders (>= 3:1)
    val glow: Color            // decorative only, never for text
)

val MaxDarkExtra = MaxExtraColors(
    textSecondary = Color(0xFF94A3B8),
    textTertiary  = Color(0xFF9AA9BC),
    link          = Color(0xFF00D2FF),   // MaxAccentCyan
    borderSubtle  = Color(0x1FFFFFFF),
    borderStrong  = Color(0x66FFFFFF),
    glow          = Color(0xFFFF2E93)    // NeonPink: glow only
)

val MaxLightExtra = MaxExtraColors(
    textSecondary = Color(0xFF475569),
    textTertiary  = Color(0xFF64748B),
    link          = Color(0xFF0A60C8),   // >= 4.5:1 on white
    borderSubtle  = Color(0xFFE5C4D3),
    borderStrong  = Color(0xFFB07A94),   // ~3.4:1 on white
    glow          = Color(0xFFFF2E93)
)

val LocalMaxExtra = staticCompositionLocalOf { MaxDarkExtra }

object MaxColors {
    val extra: MaxExtraColors
        @Composable @ReadOnlyComposable get() = LocalMaxExtra.current
}
