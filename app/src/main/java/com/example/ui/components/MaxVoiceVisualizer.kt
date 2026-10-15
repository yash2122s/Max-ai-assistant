package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AetherNeonCyan
import com.example.ui.theme.AetherNeonCyanDim
import com.example.ui.theme.AetherNeonPurple
import kotlin.math.sin

/**
 * High-performance audio voice visualizer bar.
 * Stitch Aether HUD 9-bar dynamic waveform with RMS reactivity and neon cyan glow.
 */
@Composable
fun MaxVoiceVisualizer(
    isRecording: Boolean,
    isAiSpeaking: Boolean,
    rms: Float,
    modifier: Modifier = Modifier
) {
    val barCount = 9
    val infiniteTransition = rememberInfiniteTransition(label = "waveform_anim")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val animatedRms by animateFloatAsState(
        targetValue = rms.coerceIn(0f, 1f),
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "rms_anim"
    )

    val baseCurve = remember { listOf(0.30f, 0.50f, 0.70f, 0.88f, 1.0f, 0.88f, 0.70f, 0.50f, 0.30f) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
    ) {
        val width = size.width
        val height = size.height
        val barWidth = 5.dp.toPx()
        val spacing = (width - (barCount * barWidth)) / (barCount + 1)
        val centerY = height / 2f

        val activeColor = when {
            isAiSpeaking -> AetherNeonPurple
            isRecording -> AetherNeonCyan
            else -> AetherNeonCyanDim.copy(alpha = 0.4f)
        }
        val glowColor = activeColor.copy(alpha = 0.35f)

        for (i in 0 until barCount) {
            val x = spacing + i * (barWidth + spacing)
            val curve = baseCurve.getOrElse(i) { 0.5f }

            val wave = if (isRecording || isAiSpeaking) {
                val offset = (i * 0.7f) + phase
                val rawSine = ((sin(offset.toDouble()) + 1.0) / 2.0).toFloat()
                val boost = animatedRms * 1.6f
                ((rawSine * 0.4f + boost * 0.6f) * curve).coerceIn(0.12f, 1.0f)
            } else {
                0.12f * curve
            }

            val barHeight = (height * 0.88f * wave).coerceAtLeast(4.dp.toPx())
            val top = centerY - (barHeight / 2f)

            // Outer subtle glow
            if (isRecording || isAiSpeaking) {
                drawRoundRect(
                    color = glowColor,
                    topLeft = Offset(x - 2.dp.toPx(), top - 2.dp.toPx()),
                    size = Size(barWidth + 4.dp.toPx(), barHeight + 4.dp.toPx()),
                    cornerRadius = CornerRadius((barWidth + 4.dp.toPx()) / 2f)
                )
            }

            // Core high-intensity neon bar
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        activeColor,
                        activeColor.copy(alpha = 0.7f),
                        activeColor
                    ),
                    startY = top,
                    endY = top + barHeight
                ),
                topLeft = Offset(x, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }
    }
}
