package com.example.voice.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.ui.theme.NeonBlue
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonPink
import com.example.ui.theme.Purple600
import kotlin.math.sin

@Composable
fun LiquidScreenEdges(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "liquid_edges")

    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val waveAmplitude = 14f * pulse
        val waveFrequency = 0.015f

        val liquidPath = Path().apply {
            // 1. Top Edge
            moveTo(0f, 0f)
            for (x in 0..w.toInt() step 15) {
                val fx = x.toFloat()
                val y = waveAmplitude * sin(fx * waveFrequency + phase)
                lineTo(fx, y.coerceAtLeast(0f))
            }

            // 2. Right Edge
            for (y in 0..h.toInt() step 15) {
                val fy = y.toFloat()
                val x = w - waveAmplitude * sin(fy * waveFrequency + phase + 1.5f)
                lineTo(x.coerceAtMost(w), fy)
            }

            // 3. Bottom Edge
            for (x in w.toInt() downTo 0 step 15) {
                val fx = x.toFloat()
                val y = h - waveAmplitude * sin(fx * waveFrequency + phase + 3.0f)
                lineTo(fx, y.coerceAtMost(h))
            }

            // 4. Left Edge
            for (y in h.toInt() downTo 0 step 15) {
                val fy = y.toFloat()
                val x = waveAmplitude * sin(fy * waveFrequency + phase + 4.5f)
                lineTo(x.coerceAtLeast(0f), fy)
            }
            close()
        }

        val gradient = Brush.sweepGradient(
            colors = listOf(
                Purple600,
                NeonBlue,
                NeonGreen,
                NeonPink,
                Purple600
            )
        )

        drawPath(
            path = liquidPath,
            brush = gradient,
            style = Stroke(width = 6f * pulse)
        )
    }
}
