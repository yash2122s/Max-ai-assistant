package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.*
import kotlin.math.*

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import kotlinx.coroutines.delay

/**
 * Jarvis-style animated neural orb (Stitch Aether HUD) — glowing sphere with
 * orbiting particle electrons, pulsing core, and dual counter-rotating containment rings.
 * Reacts to microphone RMS for an organic, breathing AI entity.
 */
@Composable
fun JarvisNeuralOrb(
    isActive: Boolean,
    isRecording: Boolean,
    isAiSpeaking: Boolean,
    isThinking: Boolean,
    rms: Float = 0f,
    modifier: Modifier = Modifier,
    orbSize: androidx.compose.ui.unit.Dp = 170.dp,
    showSuggestions: Boolean = false,
    onClick: (() -> Unit)? = null
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

    // Stitch Aether HUD: Outer containment rings (10s and 15s counter-rotation)
    val outerRing1Angle by if (isActive) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(10000, easing = LinearEasing)
            ),
            label = "outerRing1"
        )
    } else remember { mutableFloatStateOf(0f) }

    val outerRing2Angle by if (isActive) {
        infiniteTransition.animateFloat(
            initialValue = 360f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(15000, easing = LinearEasing)
            ),
            label = "outerRing2"
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

    val isDark = LocalIsDarkMode.current

    // Color state: Stitch Aether HUD palette with Vibrant Luminous Pink for Day Mode
    val activeColor = if (isDark) {
        when {
            !isActive -> MaxTextMuted
            isRecording -> MaxAccentCyan
            isAiSpeaking -> Color(0xFF8B5CF6)
            isThinking -> MaxAccentCyan
            else -> MaxAccentCyan
        }
    } else {
        when {
            !isActive -> MaxTextMuted
            isRecording -> MaxAccentCyan
            isAiSpeaking -> Color(0xFF8B5CF6)
            isThinking -> MaxAccentCyan
            else -> MaxAccentCyan
        }
    }
    val secondaryColor = if (isDark) {
        when {
            !isActive -> BorderDark
            isRecording -> Color(0xFF8B5CF6)
            isAiSpeaking -> MaxPrimaryRoyal
            isThinking -> MaxAccentCyan
            else -> Color(0xFF8B5CF6)
        }
    } else {
        when {
            !isActive -> MaxBorderHairline
            isRecording -> MaxPrimaryRoyal
            isAiSpeaking -> Color(0xFF8B5CF6)
            isThinking -> MaxAccentCyan
            else -> MaxPrimaryRoyal
        }
    }

    // RMS influence: makes the orb react to voice amplitude
    val rmsScale = if (isActive) 1f + (rms.coerceIn(0f, 1f) * 0.35f) else 1f

    val statusText = when {
        !isActive -> "MAX OFFLINE"
        isRecording -> "LISTENING..."
        isAiSpeaking -> "MAX SPEAKING..."
        isThinking -> "PROCESSING..."
        else -> "ONLINE & READY"
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.semantics {
            contentDescription = "MAX status: $statusText"
        }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(orbSize)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                val baseRadius = size.minDimension / 2f * 0.36f

                // --- Layer 0: Stitch Aether HUD Outer Containment Rings ---
                if (isActive) {
                    val ring1Radius = baseRadius * 1.95f * rmsScale
                    val ring2Radius = baseRadius * 2.15f * rmsScale
                    drawCircle(
                        color = activeColor.copy(alpha = 0.18f),
                        radius = ring1Radius,
                        center = Offset(centerX, centerY),
                        style = Stroke(width = 1.dp.toPx())
                    )
                    drawCircle(
                        color = secondaryColor.copy(alpha = 0.12f),
                        radius = ring2Radius,
                        center = Offset(centerX, centerY),
                        style = Stroke(width = MaxStroke.hairline.toPx())
                    )
                    // Orbital ticks on containment ring
                    for (k in 0 until 4) {
                        val tickAngleRad = (outerRing1Angle + (k * 90f)) * PI.toFloat() / 180f
                        val tx = centerX + ring1Radius * cos(tickAngleRad)
                        val ty = centerY + ring1Radius * sin(tickAngleRad)
                        drawCircle(
                            color = activeColor.copy(alpha = 0.7f),
                            radius = 2.5f,
                            center = Offset(tx, ty)
                        )
                    }
                }

                // --- Layer 1: Deep outer aura glow ---
                val auraRadius = baseRadius * 2.2f * corePulse * rmsScale
                val auraColors = if (isDark) listOf(
                    activeColor.copy(alpha = 0.14f * innerGlowPulse),
                    secondaryColor.copy(alpha = 0.05f),
                    Color.Transparent
                ) else listOf(
                    activeColor.copy(alpha = 0.32f * innerGlowPulse),
                    secondaryColor.copy(alpha = 0.15f),
                    Color.Transparent
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = auraColors,
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
                val orbitColors = if (isDark) listOf(activeColor, secondaryColor, NeonPink)
                    else listOf(NeonPink, MagentaRose, MaxSemanticColors.accentPink)

                orbits.forEachIndexed { index, (angle, radius, tiltDeg) ->
                    val tiltRad = tiltDeg * PI.toFloat() / 180f
                    val ringAlpha = if (isDark) 0.25f + (innerGlowPulse * 0.15f) else 0.52f + (innerGlowPulse * 0.20f)

                    // Draw orbital ring path (ellipse)
                    drawOvalRing(
                        center = Offset(centerX, centerY),
                        radiusX = radius * rmsScale,
                        radiusY = radius * rmsScale * cos(tiltRad) * 0.6f,
                        color = orbitColors[index].copy(alpha = ringAlpha),
                        strokeWidth = if (isDark) 1.2f else 1.8f
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
                                orbitColors[index].copy(alpha = 0.9f),
                                orbitColors[index].copy(alpha = 0.3f),
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
                            activeColor.copy(alpha = if (isDark) 0.3f * innerGlowPulse else 0.65f * innerGlowPulse),
                            activeColor.copy(alpha = if (isDark) 0.08f else 0.2f),
                            Color.Transparent
                        ),
                        center = Offset(centerX, centerY),
                        radius = shieldRadius
                    ),
                    radius = shieldRadius,
                    center = Offset(centerX, centerY),
                    style = Stroke(width = if (isDark) 2f else 2.5f)
                )

                // --- Layer 5: Core sphere with gradient ---
                val coreRadius = baseRadius * corePulse * rmsScale * 0.85f
                val coreColors = if (isDark) listOf(
                    Color.White.copy(alpha = 0.35f),
                    activeColor.copy(alpha = 0.6f * innerGlowPulse),
                    activeColor.copy(alpha = 0.25f),
                    secondaryColor.copy(alpha = 0.15f),
                    Color.Transparent
                ) else listOf(
                    Color.White,
                    NeonPink,
                    MagentaRose,
                    MaxSemanticColors.destructive.copy(alpha = 0.85f),
                    MaxSemanticColors.accentPink.copy(alpha = 0.25f),
                    Color.Transparent
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = coreColors,
                        center = Offset(centerX, centerY - coreRadius * 0.12f),
                        radius = coreRadius
                    ),
                    radius = coreRadius,
                    center = Offset(centerX, centerY)
                )

                // --- Layer 6: Specular highlight (glassy 3D effect) ---
                val highlightRadius = coreRadius * 0.42f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (isDark) 0.4f else 0.9f),
                            Color.White.copy(alpha = if (isDark) 0.05f else 0.2f),
                            Color.Transparent
                        ),
                        center = Offset(centerX - coreRadius * 0.2f, centerY - coreRadius * 0.26f),
                        radius = highlightRadius
                    ),
                    radius = highlightRadius,
                    center = Offset(centerX - coreRadius * 0.2f, centerY - coreRadius * 0.26f)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Status telemetry badge below orb
        Text(
            text = statusText,
            style = MaxType.Mono,
            color = activeColor.copy(alpha = 0.95f)
        )

    }
}

@Composable
fun RotatingSuggestions(
    suggestions: List<String>,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    intervalMs: Long = 5000L
) {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(suggestions) {
        if (suggestions.size < 2) return@LaunchedEffect
        while (true) {
            delay(intervalMs)
            index = (index + 1) % suggestions.size
        }
    }
    AnimatedContent(
        targetState = index,
        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
        modifier = modifier,
        label = "suggestions"
    ) { i ->
        val text = suggestions[i]
        Box(
            Modifier
                .heightIn(min = 40.dp)
                .clip(MaxShapes.pill)
                .background(MaxSemanticColors.surfaceElevated.copy(alpha = 0.65f))
                .border(MaxDimensions.borderHairline, MaxSemanticColors.borderHairline, MaxShapes.pill)
                .clickable(role = Role.Button, onClickLabel = "Use this suggestion") {
                    onSuggestionClick(text)
                }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Try: “$text”",
                style = MaxType.CaptionMedium,
                color = MaxSemanticColors.accentCyan
            )
        }
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
