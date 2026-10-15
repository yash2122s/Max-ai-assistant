package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ============================================================================
// MAX iOS-NATIVE GEOMETRY & SPACING SCALE
// ============================================================================

object MaxDimensions {
    // Spacing Grid
    val spaceNone: Dp = 0.dp
    val space2xs: Dp = 2.dp
    val spaceXs: Dp = 4.dp
    val spaceSm: Dp = 8.dp
    val spaceMd: Dp = 12.dp
    val spaceLg: Dp = 16.dp
    val spaceXl: Dp = 20.dp
    val space2xl: Dp = 24.dp
    val space3xl: Dp = 32.dp
    val space4xl: Dp = 48.dp

    // Corner Radii (iOS Continuous Squircle Scale)
    val radius2xs: Dp = 4.dp
    val radiusXs: Dp = 6.dp
    val radiusSm: Dp = 8.dp     // Badges, small tags
    val radiusMd: Dp = 12.dp    // Action buttons, compact cards
    val radiusLg: Dp = 16.dp    // Standard inputs, interactive tiles
    val radiusXl: Dp = 20.dp    // Inset grouped sections, main cards
    val radius2xl: Dp = 28.dp   // Hero cards, modal bottom sheets
    val radiusPill: Dp = 999.dp // Floating capsules, status pills

    // Border Hairlines & Dividers
    val borderHairline: Dp = 1.dp
    val borderStandard: Dp = 1.dp
    val borderFocused: Dp = 1.5.dp

    // Component Dimensions
    val buttonHeightSm: Dp = 36.dp
    val buttonHeightMd: Dp = 48.dp
    val buttonHeightLg: Dp = 54.dp
    val inputHeight: Dp = 52.dp
    val topBarHeight: Dp = 56.dp
    val orbSizeCompact: Dp = 140.dp
    val orbSizeStandard: Dp = 170.dp
    val bottomPillHeight: Dp = 64.dp

    // Elevation & Depth
    val elevationNone: Dp = 0.dp
    val elevationLow: Dp = 2.dp
    val elevationMedium: Dp = 6.dp
    val elevationHigh: Dp = 12.dp
}

object MaxRadius {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 28.dp
}

object MaxStroke {
    val hairline: Dp = 1.dp
    val emphasis: Dp = 1.5.dp
}

object MaxShapes {
    val bubbleUser = RoundedCornerShape(MaxRadius.xl, MaxRadius.xl, MaxRadius.xl, MaxRadius.xs)
    val bubbleMax  = RoundedCornerShape(MaxRadius.xs, MaxRadius.xl, MaxRadius.xl, MaxRadius.xl)
    val card = RoundedCornerShape(MaxRadius.lg)
    val pill = RoundedCornerShape(50)
}

