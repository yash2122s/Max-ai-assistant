package com.example.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic color tokens. Replaces all hardcoded Color(0x...) values
 * across screens. Aliases existing brand colors for cohesion.
 */
object MaxSemanticColors {
    // Backgrounds
    val background = MaxBaseDark
    val surface = MaxSurfaceCard
    val surfaceElevated = MaxSurfaceElevated
    val surfaceVariant = Color(0xFF1C2128)

    // Text
    val textPrimary = MaxTextPrimary
    val textSecondary = MaxTextSecondary
    val textMuted = MaxTextMuted

    // Brand
    val primaryRoyal = MaxPrimaryRoyal
    val accentCyan = MaxAccentCyan
    val royalGold = MaxRoyalGold

    // Status
    val success = Color(0xFF10B981)
    val successSurface = Color(0x1A10B981)
    val warning = Color(0xFFF59E0B)
    val warningSurface = Color(0xFF2A2311)
    val destructive = Color(0xFFEF4444)
    val destructiveSurface = Color(0xFF331418)
    val error = destructive
    val errorSurface = destructiveSurface
    val info = Color(0xFF3B82F6)
    val infoSurface = Color(0xFF0D253A)

    // Category accents
    val accentPink = Color(0xFFFF4081)
    val accentRed = Color(0xFFFF5252)

    // Borders
    val border = MaxBorderHairline
    val borderHairline = MaxBorderHairline
    val borderStrong = Color(0x33FFFFFF)
}

val LocalMaxSemanticColors = staticCompositionLocalOf { MaxSemanticColors }
