package com.example.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Editorial typography scale tokens for AI flagship experiences (Arc/Linear/Raycast luxury tier).
 * Uses clean geometric SansSerif optical metrics with calibrated line-heights and negative letter-spacing.
 */
object MaxType {

    // Hero — display title (44sp / 48sp / Black / -1.5sp)
    val Display = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 44.sp,
        lineHeight = 48.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = (-1.5).sp
    )

    // Screen titles (28sp / 34sp / ExtraBold / -0.8sp)
    val Title = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.8).sp
    )

    // Section headers (20sp / 26sp / Bold / -0.4sp)
    val Headline = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp
    )

    // Sub-section & Card titles (16sp / 22sp / SemiBold / -0.2sp)
    val Subheadline = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp
    )

    // Subtitle / eyebrow (uppercase tracking, 11sp / 14sp / Bold / 2.0sp)
    val Eyebrow = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.0.sp
    )

    // Body regular (14sp / 20sp / Normal / -0.1sp)
    val Body = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.1).sp
    )

    // Body prominent / Medium (14sp / 20sp / SemiBold / -0.1sp)
    val BodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp
    )

    // Caption regular (12sp / 16sp / Normal / 0sp)
    val Caption = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp
    )

    // Caption prominent (12sp / 16sp / SemiBold / 0sp)
    val CaptionMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp
    )

    // Monospace status / telemetry / code (12sp / 16sp / SemiBold / 1.0sp)
    val Mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.0.sp
    )

    // Monospace micro / badge telemetry (10sp / 13sp / Medium / 0.8sp)
    val MonoSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp
    )
}
