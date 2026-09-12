package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// MAX Official Design System (Premium White Light Theme)
val MaxPrimary = Color(0xFF0066FF)         // Vibrant Royal Electric Blue
val MaxSecondary = Color(0xFF6366F1)       // Vivid Indigo
val MaxAccent = Color(0xFF6366F1)          // Vivid Indigo
val MaxBgDark = Color(0xFFF8FAFC)          // Clean Off-White Slate Background
val MaxBgLight = Color(0xFFFFFFFF)         // Pure White Background
val MaxSurface = Color(0xFFFFFFFF)         // Clean White Cards & Containers
val MaxSurfaceDark = Color(0xFFFFFFFF)     // Clean White Cards & Containers
val MaxSurfaceVariant = Color(0xFFF1F5F9)  // Soft Slate for Inputs & Elevated Cards
val MaxTextPrimary = Color(0xFF0F172A)     // Deep Charcoal / Black for crisp readability
val MaxTextSecondary = Color(0xFF334155)   // Deep Slate Secondary Text
val MaxTextMuted = Color(0xFF64748B)       // Medium Slate Muted Text
val MaxBorder = Color(0xFFE2E8F0)          // Crisp Card & Divider Border
val MaxSuccess = Color(0xFF10B981)         // Modern Emerald
val MaxError = Color(0xFFEF4444)           // Modern Crimson

// Map legacy tokens to MAX Light Theme equivalents
val BgDark = MaxBgDark
val SurfaceDark = MaxSurfaceDark
val SurfaceDarker = MaxSurfaceVariant
val BorderDark = MaxBorder
val TextLight = MaxTextPrimary
val TextMuted = MaxTextMuted
val PrimaryIndigo = MaxPrimary
val PrimaryLight = MaxPrimary
val OnPrimaryDark = Color.White

val BgLight = MaxBgLight
val SurfaceLight = MaxSurface
val SurfaceLighter = MaxSurfaceVariant
val BorderLight = MaxBorder
val TextDark = MaxTextPrimary
val TextMutedDark = MaxTextMuted
val PrimaryIndigoLight = MaxPrimary
val OnPrimaryLight = Color.White

val Emerald400 = MaxSuccess
val Blue600 = MaxPrimary
val Purple600 = MaxAccent
val GradientStart = MaxPrimary
val GradientEnd = MaxAccent

// Aesthetic Colors (Light Mode Adjusted)
val NeonPink = MaxError
val NeonBlue = MaxPrimary
val NeonGreen = MaxSuccess
val NeonGold = Color(0xFFD97706)
val GradientBorderColors = listOf(MaxPrimary, MaxAccent, MaxPrimary)

// Professional Theme Tokens
val SlateBackground = MaxBgDark
val SlateSurface = MaxSurface
val SlateBorder = MaxBorder

// Category Accent Colors
val AccentCyan = Color(0xFF0284C7)
val AccentTeal = Color(0xFF0D9488)
val AccentAmber = Color(0xFFD97706)
val AccentPurple = MaxAccent
val AccentIndigo = MaxPrimary
val AccentBlue = MaxPrimary

// Status Colors
val StatusGrantedBg = Color(0xFFECFDF5)
val StatusGrantedText = Color(0xFF059669)

// Theme Tokens
val JarvisAmber = NeonGold
val JarvisGold = NeonGold
val JarvisBrightGold = NeonGold
val JarvisFieryOrange = MaxError
val JarvisDarkBg = MaxBgDark
val JarvisCardBg = MaxSurface
val JarvisBorderAmber = MaxBorder
