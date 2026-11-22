package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.example.ui.theme.MaxAccentCyan
import com.example.ui.theme.MaxBaseDark
import com.example.ui.theme.MaxPrimaryRoyal

enum class AuroraMode { FULL, SUBTLE, NONE }

@Composable
fun MaxAuroraBackground(
    mode: AuroraMode = AuroraMode.SUBTLE,
    scrollOffset: Float = 0f,
    modifier: Modifier = Modifier
) {
    when (mode) {
        AuroraMode.NONE -> Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaxBaseDark)
        )
        AuroraMode.SUBTLE -> StaticAurora(modifier)
        AuroraMode.FULL -> FullAurora(scrollOffset, modifier)
    }
}

@Composable
private fun StaticAurora(modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "static_aurora")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(24000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "static_phase"
    )
    val breathe by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(4500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "static_breathe"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaxBaseDark)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val r = size.minDimension * 1.1f * breathe

            // Dynamic subtle orbit offsets
            val cx1 = w * 0.15f + kotlin.math.cos(phase) * w * 0.04f
            val cy1 = h * 0.10f + kotlin.math.sin(phase) * h * 0.03f

            val cx2 = w * 0.85f - kotlin.math.cos(phase + 1f) * w * 0.05f
            val cy2 = h * 0.90f + kotlin.math.sin(phase + 1f) * h * 0.03f

            // Top-left cyan glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        MaxAccentCyan.copy(alpha = 0.18f),
                        Color.Transparent
                    ),
                    center = Offset(cx1, cy1),
                    radius = r
                ),
                radius = r,
                center = Offset(cx1, cy1)
            )

            // Bottom-right royal glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        MaxPrimaryRoyal.copy(alpha = 0.24f),
                        Color.Transparent
                    ),
                    center = Offset(cx2, cy2),
                    radius = r * 1.1f
                ),
                radius = r * 1.1f,
                center = Offset(cx2, cy2)
            )
        }
    }
}

@Composable
private fun FullAurora(scrollOffset: Float, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(16000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "t"
    )
    val phase = t * 2f * Math.PI.toFloat()

    Box(modifier = modifier.fillMaxSize().background(MaxBaseDark)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = -scrollOffset * 0.25f }
        ) {
            val w = size.width
            val h = size.height
            val r = size.minDimension * 0.85f

            // Cyan blob
            val cx1 = w * 0.5f + kotlin.math.cos(phase) * w * 0.40f
            val cy1 = h * 0.25f + kotlin.math.sin(phase) * h * 0.15f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(MaxAccentCyan.copy(alpha = 0.38f), Color.Transparent),
                    center = Offset(cx1, cy1), radius = r
                ),
                radius = r, center = Offset(cx1, cy1)
            )

            // Purple blob
            val cx2 = w * 0.5f - kotlin.math.cos(phase) * w * 0.42f
            val cy2 = h * 0.82f + kotlin.math.sin(phase + 1.5f) * h * 0.18f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(MaxPrimaryRoyal.copy(alpha = 0.50f), Color.Transparent),
                    center = Offset(cx2, cy2), radius = r * 1.05f
                ),
                radius = r * 1.05f, center = Offset(cx2, cy2)
            )

            // Center pulse
            val pulse = 0.10f + 0.06f * kotlin.math.sin(phase * 2f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(MaxAccentCyan.copy(alpha = pulse), Color.Transparent),
                    center = Offset(w * 0.5f, h * 0.5f), radius = r * 0.7f
                ),
                radius = r * 0.7f, center = Offset(w * 0.5f, h * 0.5f)
            )

            // Particles
            val particleCount = 14
            repeat(particleCount) { i ->
                val seed = i * 137.5f
                val px = (kotlin.math.sin(phase * 0.4f + seed) * 0.5f + 0.5f) * w
                val py = (kotlin.math.cos(phase * 0.3f + seed * 1.3f) * 0.5f + 0.5f) * h
                val radius = 1.5f + (i % 3) * 0.8f
                drawCircle(
                    color = MaxAccentCyan.copy(alpha = 0.35f - (i % 4) * 0.06f),
                    radius = radius, center = Offset(px, py)
                )
            }
        }

        // Vignette
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaxBaseDark.copy(alpha = 0.20f),
                            MaxBaseDark.copy(alpha = 0.55f)
                        )
                    )
                )
        )
    }
}
