package com.example.voice.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.sin

@Composable
fun SoundWave(
    rms: Float,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    // Smoothly animated Audio Volume Level
    val animatedRms = remember { Animatable(0.05f) }

    LaunchedEffect(isRecording, rms) {
        val target = if (isRecording || rms > 0.01f) {
            rms.coerceIn(0.15f, 1.0f)
        } else {
            0.08f
        }
        animatedRms.animateTo(
            targetValue = target,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow)
        )
    }

    // Infinite Phase Animation for Continuous Fluid Motion
    val infiniteTransition = rememberInfiniteTransition(label = "wave_phase_transition")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    // Wave Palette Colors (Professional Siri/Gemini Style)
    val cyanColor = Color(0xFF00E5FF)
    val purpleColor = Color(0xFF7C4DFF)
    val pinkColor = Color(0xFFFF007F)

    Canvas(
        modifier = modifier
            .height(36.dp)
            .width(64.dp)
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val currentAmplitude = (height * 0.42f) * animatedRms.value

        // Draw 3 Fluid Layered Sine Waves with Phase Offsets & Gradients
        val waveConfigs = listOf(
            Triple(1.0f, phase, cyanColor.copy(alpha = 0.85f)),
            Triple(0.7f, phase + 1.2f, purpleColor.copy(alpha = 0.75f)),
            Triple(0.5f, phase + 2.4f, pinkColor.copy(alpha = 0.65f))
        )

        waveConfigs.forEach { (amplitudeFactor, phaseOffset, waveColor) ->
            val path = Path()
            val points = 40
            val step = width / points

            path.moveTo(0f, centerY)

            for (i in 0..points) {
                val x = i * step
                val normalizedX = (x / width) * (2 * Math.PI).toFloat()
                
                // Gaussian envelope to taper wave ends smoothly to 0 at edges
                val edgeEnvelope = sin((x / width) * Math.PI).toFloat()
                val y = centerY + (sin(normalizedX * 2.2f + phaseOffset).toFloat() * currentAmplitude * amplitudeFactor * edgeEnvelope)

                if (i == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }

            // Draw wave path with glowing gradient stroke
            drawPath(
                path = path,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        waveColor.copy(alpha = 0.2f),
                        waveColor,
                        waveColor.copy(alpha = 0.2f)
                    )
                ),
                style = Stroke(
                    width = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }

        // Draw Center Glow Core Dot
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(cyanColor.copy(alpha = animatedRms.value), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(width / 2f, centerY),
                radius = 18.dp.toPx()
            ),
            center = androidx.compose.ui.geometry.Offset(width / 2f, centerY),
            radius = 18.dp.toPx()
        )
    }
}
