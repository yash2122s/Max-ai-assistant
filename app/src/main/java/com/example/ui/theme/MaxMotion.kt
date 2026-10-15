package com.example.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Global motion tokens — single source of truth for all animations.
 */
object MaxMotion {
    const val FAST = 180
    const val NORMAL = 320
    const val SLOW = 500
    const val AMBIENT = 2000

    val Ease = FastOutSlowInEasing

    val Bouncy = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )

    val Snappy = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow
    )

    val Gentle = spring<Float>(
        dampingRatio = 0.75f,
        stiffness = Spring.StiffnessMediumLow
    )
}

val LocalMaxMotion = staticCompositionLocalOf { MaxMotion }
