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
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MaxAccent
import com.example.ui.theme.MaxPrimary
import com.example.ui.theme.MaxSuccess
import kotlin.math.sin

/**
 * High-performance audio voice visualizer bar.
 * Leaf composable that smoothly renders audio waveforms based on RMS value.
 */
@Composable
fun MaxVoiceVisualizer(
    isRecording: Boolean,
    isAiSpeaking: Boolean,
    rms: Float,
    modifier: Modifier = Modifier
) {
    val barCount = 7
    val infiniteTransition = rememberInfiniteTransition(label = "waveform_anim")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
    ) {
        val width = size.width
        val height = size.height
        val barWidth = 6.dp.toPx()
        val spacing = (width - (barCount * barWidth)) / (barCount + 1)
        val centerY = height / 2f

        val activeColor = if (isAiSpeaking) MaxAccent else if (isRecording) MaxPrimary else MaxSuccess

        for (i in 0 until barCount) {
            val x = spacing + i * (barWidth + spacing)
            val wave = if (isRecording || isAiSpeaking) {
                val offset = (i * 0.8f) + phase
                val rawHeight = ((sin(offset.toDouble()) + 1.0) / 2.0).toFloat()
                val boost = (rms.coerceIn(0f, 1f) * 1.5f)
                (rawHeight * 0.5f + boost * 0.5f).coerceIn(0.15f, 1.0f)
            } else {
                0.15f
            }

            val barHeight = (height * 0.85f * wave).coerceAtLeast(4.dp.toPx())
            val top = centerY - (barHeight / 2f)

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(activeColor, activeColor.copy(alpha = 0.5f)),
                    startY = top,
                    endY = top + barHeight
                ),
                topLeft = Offset(x, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
