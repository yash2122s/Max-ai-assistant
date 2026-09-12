package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import kotlin.math.*

/**
 * Jarvis-style animated neural orb — a glowing sphere with orbiting
 * electron/proton particles, pulsing core, and dynamic orbital rings.
 * Reacts to microphone RMS for organic, alive feeling.
 */
@Composable
fun JarvisNeuralOrb(
    isActive: Boolean,
    isRecording: Boolean,
    isAiSpeaking: Boolean,
    isThinking: Boolean,
    rms: Float = 0f,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "jarvis_orb")

    // Animations only run when isActive == true to prevent GPU battery drain
    val corePulse by if (isActive) {
        infiniteTransition.animateFloat(
            initialValue = 0.85f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(1800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "core_pulse"
        )
    } else remember { mutableFloatStateOf(0.9f) }

    val orbit1Angle by if (isActive) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(4000, easing = LinearEasing)
            ),
            label = "orbit1"
        )
    } else remember { mutableFloatStateOf(0f) }

    val orbit2Angle by if (isActive) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(5500, easing = LinearEasing)
            ),
            label = "orbit2"
        )
    } else remember { mutableFloatStateOf(0f) }

    val orbit3Angle by if (isActive) {
        infiniteTransition.animateFloat(
            initialValue = 360f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(3200, easing = LinearEasing)
            ),
            label = "orbit3"
        )
    } else remember { mutableFloatStateOf(0f) }

    val innerGlowPulse by if (isActive) {
        infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "inner_glow"
        )
    } else remember { mutableFloatStateOf(0.3f) }

    val breatheRing by if (isActive) {
        infiniteTransition.animateFloat(
            initialValue = 0.92f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(2500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breathe"
        )
    } else remember { mutableFloatStateOf(1.0f) }

    val synapsePhase by if (isActive) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(6000, easing = LinearEasing)
            ),
            label = "synapse"
        )
    } else remember { mutableFloatStateOf(0f) }

    // Color state: cyan when listening, indigo when AI speaking, purple when thinking
    val activeColor = when {
        !isActive -> MaxTextMuted
        isRecording -> AccentCyan
        isAiSpeaking -> AccentIndigo
        isThinking -> AccentPurple
        else -> NeonBlue
    }
    val secondaryColor = when {
        !isActive -> BorderDark
        isRecording -> NeonBlue
        isAiSpeaking -> AccentPurple
        isThinking -> NeonPink
        else -> AccentCyan
    }

    // RMS influence: makes the orb react to voice amplitude
    val rmsScale = if (isActive) 1f + (rms.coerceIn(0f, 1f) * 0.3f) else 1f

    val statusText = when {
        !isActive -> "MAX OFF"
        isRecording -> "Listening..."
        isAiSpeaking -> "MAX Speaking..."
        isThinking -> "Processing..."
        else -> "Ready"
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.semantics {
            contentDescription = "MAX Neural Core status: $statusText"
        }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(220.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                val baseRadius = size.minDimension / 2f * 0.38f

                // --- Layer 1: Deep outer aura glow ---
                val auraRadius = baseRadius * 2.2f * corePulse * rmsScale
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            activeColor.copy(alpha = 0.12f * innerGlowPulse),
                            activeColor.copy(alpha = 0.04f),
                            Color.Transparent
                        ),
                        center = Offset(centerX, centerY),
                        radius = auraRadius
                    ),
                    radius = auraRadius,
                    center = Offset(centerX, centerY)
                )

                // --- Layer 2: Orbital rings (3 elliptical orbits) ---
                val orbits = listOf(
                    Triple(orbit1Angle, baseRadius * 1.65f * breatheRing, 25f),   // tilt 25°
                    Triple(orbit2Angle, baseRadius * 1.45f * breatheRing, 65f),   // tilt 65°
                    Triple(orbit3Angle, baseRadius * 1.55f * breatheRing, 45f)    // tilt 45° (reverse)
                )
                val orbitColors = listOf(activeColor, secondaryColor, NeonPink)

                orbits.forEachIndexed { index, (angle, radius, tiltDeg) ->
                    val tiltRad = tiltDeg * PI.toFloat() / 180f
                    val ringAlpha = 0.25f + (innerGlowPulse * 0.15f)

                    // Draw orbital ring path (ellipse)
                    drawOvalRing(
                        center = Offset(centerX, centerY),
                        radiusX = radius * rmsScale,
                        radiusY = radius * rmsScale * cos(tiltRad) * 0.6f,
                        color = orbitColors[index].copy(alpha = ringAlpha),
                        strokeWidth = 1.2f
                    )

                    // Electron particle on the orbit
                    val particleAngleRad = angle * PI.toFloat() / 180f
                    val px = centerX + radius * rmsScale * cos(particleAngleRad)
                    val py = centerY + radius * rmsScale * cos(tiltRad) * 0.6f * sin(particleAngleRad)
                    val particleRadius = 4f + (rms * 3f)

                    // Particle glow
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                orbitColors[index].copy(alpha = 0.8f),
                                orbitColors[index].copy(alpha = 0.2f),
                                Color.Transparent
                            ),
                            center = Offset(px, py),
                            radius = particleRadius * 4f
                        ),
                        radius = particleRadius * 4f,
                        center = Offset(px, py)
                    )

                    // Particle core
                    drawCircle(
                        color = orbitColors[index],
                        radius = particleRadius,
                        center = Offset(px, py)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = particleRadius * 0.4f,
                        center = Offset(px, py)
                    )
                }

                // --- Layer 3: Neural synapse particles scattered around the core ---
                val synapseCount = 12
                for (i in 0 until synapseCount) {
                    val phaseOffset = (2 * PI * i / synapseCount).toFloat()
                    val dist = baseRadius * (1.1f + 0.5f * sin(synapsePhase + phaseOffset)) * rmsScale
                    val sAngle = synapsePhase * (if (i % 2 == 0) 1f else -0.7f) + phaseOffset
                    val sx = centerX + dist * cos(sAngle)
                    val sy = centerY + dist * sin(sAngle) * 0.7f
                    val sAlpha = (0.3f + 0.4f * sin(synapsePhase + phaseOffset * 2f)).coerceIn(0f, 1f)
                    val sRadius = 1.5f + (rms * 2f)

                    drawCircle(
                        color = secondaryColor.copy(alpha = sAlpha),
                        radius = sRadius,
                        center = Offset(sx, sy)
                    )
                }

                // --- Layer 4: Inner shield ring (glowing boundary) ---
                val shieldRadius = baseRadius * 1.15f * corePulse * rmsScale
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            activeColor.copy(alpha = 0.3f * innerGlowPulse),
                            activeColor.copy(alpha = 0.08f),
                            Color.Transparent
                        ),
                        center = Offset(centerX, centerY),
                        radius = shieldRadius
                    ),
                    radius = shieldRadius,
                    center = Offset(centerX, centerY),
                    style = Stroke(width = 2f)
                )

                // --- Layer 5: Core sphere with gradient ---
                val coreRadius = baseRadius * corePulse * rmsScale * 0.85f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.35f),
                            activeColor.copy(alpha = 0.6f * innerGlowPulse),
                            activeColor.copy(alpha = 0.25f),
                            secondaryColor.copy(alpha = 0.15f),
                            Color.Transparent
                        ),
                        center = Offset(centerX, centerY - coreRadius * 0.15f),
                        radius = coreRadius
                    ),
                    radius = coreRadius,
                    center = Offset(centerX, centerY)
                )

                // --- Layer 6: Specular highlight (glassy 3D effect) ---
                val highlightRadius = coreRadius * 0.45f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.4f),
                            Color.White.copy(alpha = 0.05f),
                            Color.Transparent
                        ),
                        center = Offset(centerX - coreRadius * 0.2f, centerY - coreRadius * 0.3f),
                        radius = highlightRadius
                    ),
                    radius = highlightRadius,
                    center = Offset(centerX - coreRadius * 0.2f, centerY - coreRadius * 0.3f)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Status text below orb
        Text(
            text = statusText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = activeColor.copy(alpha = 0.9f),
            letterSpacing = 1.5.sp
        )
    }
}

/**
 * Helper extension: draws an elliptical ring outline using line segments.
 */
private fun DrawScope.drawOvalRing(
    center: Offset,
    radiusX: Float,
    radiusY: Float,
    color: Color,
    strokeWidth: Float,
    segments: Int = 72
) {
    val step = (2 * PI / segments).toFloat()
    for (i in 0 until segments) {
        val a1 = step * i
        val a2 = step * (i + 1)
        val p1 = Offset(center.x + radiusX * cos(a1), center.y + radiusY * sin(a1))
        val p2 = Offset(center.x + radiusX * cos(a2), center.y + radiusY * sin(a2))
        drawLine(color = color, start = p1, end = p2, strokeWidth = strokeWidth)
    }
}
