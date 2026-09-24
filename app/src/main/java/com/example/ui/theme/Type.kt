package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ============================================================================
// MAX iOS-NATIVE SF PRO TYPOGRAPHY HIERARCHY
// ============================================================================

val Typography = Typography(
    // Large Title (34sp / 41sp / Bold / +0.37sp)
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 41.sp,
        letterSpacing = 0.37.sp,
        color = MaxTextPrimary
    ),
    // Title 1 (28sp / 34sp / Bold / +0.36sp)
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.36.sp,
        color = MaxTextPrimary
    ),
    // Title 2 (22sp / 28sp / SemiBold / +0.35sp)
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.35.sp,
        color = MaxTextPrimary
    ),
    // Title 3 (20sp / 25sp / SemiBold / +0.38sp)
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.38.sp,
        color = MaxTextPrimary
    ),
    // Headline (17sp / 22sp / SemiBold / -0.41sp)
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.41).sp,
        color = MaxTextPrimary
    ),
    // Subhead (15sp / 20sp / Medium / -0.24sp)
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.24).sp,
        color = MaxTextPrimary
    ),
    // Body (17sp / 22sp / Regular / -0.41sp)
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.41).sp,
        color = MaxTextPrimary
    ),
    // Callout (16sp / 21sp / Regular / -0.32sp)
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        letterSpacing = (-0.32).sp,
        color = MaxTextSecondary
    ),
    // Footnote (13sp / 18sp / Regular / -0.08sp)
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = (-0.08).sp,
        color = MaxTextTertiary
    ),
    // Caption 1 (12sp / 16sp / Medium / 0sp)
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
        color = MaxTextSecondary
    ),
    // Caption 2 (11sp / 13sp / Regular / +0.06sp)
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.06.sp,
        color = MaxTextTertiary
    ),
    // Caption 3 (10sp / 12sp / Medium / +0.06sp)
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.06.sp,
        color = MaxTextTertiary
    )
)

