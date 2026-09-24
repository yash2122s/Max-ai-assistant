package com.example.ui.theme

import androidx.compose.ui.graphics.Color

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
val MaxRoyalIndigo = Color(0xFF5E5CE6)       // iOS Indigo

// Text & Content Hierarchy (iOS High-Contrast Scale)
val MaxTextPrimary = Color(0xFFFFFFFF)       // 100% Crisp White (Titles & Key Content)
val MaxTextSecondary = Color(0xFF94A3B8)     // Slate 400 (Body, Subheadings, Descriptions)
val MaxTextTertiary = Color(0xFF64748B)      // Slate 500 (Captions, Footnotes, Placeholders)
val MaxTextMuted = Color(0xFF64748B)         // Alias for Tertiary

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

// Aesthetic & Jarvis Tokens
val NeonPink = MaxDestructiveApple
val NeonBlue = MaxAccentCyan
val NeonGreen = MaxSuccessApple
val NeonGold = MaxRoyalGold
val GradientBorderColors = listOf(MaxAccentCyan, MaxPrimaryRoyal, MaxAccentCyan)

val SlateBackground = MaxBaseDark
val SlateSurface = MaxSurfaceCard
val SlateBorder = MaxBorderHairline

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
