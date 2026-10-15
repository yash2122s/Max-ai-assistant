package com.example.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Editorial typography scale tokens for AI flagship experiences (Arc/Linear/Raycast tier).
 */
object MaxType {

    // Hero — display title (44sp)
    val Display = TextStyle(
        fontSize = 44.sp,
        lineHeight = 46.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = (-1.5).sp
    )

    // Screen titles
    val Title = TextStyle(
        fontSize = 28.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = (-0.8).sp
    )

    // Section headers
    val Headline = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp
    )

    // Subtitle / eyebrow (uppercase tracking)
    val Eyebrow = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.5.sp
    )

    // Body text
    val Body = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp
    )

    // Caption
    val Caption = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.1.sp
    )

    // Monospace status / telemetry
    val Mono = TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp
    )
}
