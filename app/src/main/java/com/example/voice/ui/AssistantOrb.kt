package com.example.voice.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.network.model.GeminiModelMode
import com.example.voice.assistant.SessionState
import kotlin.math.cos
import kotlin.math.sin

/**
 * 60 FPS Futuristic AI Core Orb for MAX Assistant.
 * Dynamically reacts to real microphone amplitude during LISTENING,
 * generates fluid speech pulses during SPEAKING,
 * rotates multi-node reasoning auras during THINKING,
 * and maintains a calm ambient breath during IDLE.
 */
@Composable
fun AssistantOrb(
    sessionState: SessionState,
    rms: Float,
    modelMode: GeminiModelMode,
    isAiSpeaking: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 140.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_continuous_anim")

    // Continuous rotation for thinking/reasoning satellites
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Breathing pulse for idle & speaking
    val idlePulse by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idle_pulse"
    )

    // Reactive RMS amplitude with smooth spring damping
    val animatedRms = remember { Animatable(0.05f) }
    LaunchedEffect(rms, sessionState, isAiSpeaking) {
        val target = when {
            sessionState == SessionState.LISTENING -> rms.coerceIn(0.12f, 1.0f)
            isAiSpeaking -> (0.35f + (rms * 0.5f)).coerceIn(0.25f, 0.95f)
            sessionState == SessionState.WAITING_FOR_MODEL -> 0.4f
            else -> 0.08f
        }
        animatedRms.animateTo(
            targetValue = target,
            animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessLow)
        )
    }

    val primaryCyan = Color(0xFF00A8FF)
    val accentPurple = Color(0xFF7C4DFF)
    val titaniumCyan = Color(0xFF00D2FF)
    val royalGold = Color(0xFFFFD700)
    val deepAmber = Color(0xFFFF9F0A)

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val baseRadius = this.size.minDimension / 3.4f
            val currentScale = if (sessionState == SessionState.LISTENING || isAiSpeaking) {
                1.0f + (animatedRms.value * 0.45f)
            } else {
                idlePulse
            }
            val activeRadius = baseRadius * currentScale

            // 1. Outer Diffused Radiant Glow Aura
            val outerGlowColor = when {
                sessionState == SessionState.TOOL_EXECUTION -> deepAmber
                modelMode == GeminiModelMode.DEEP -> accentPurple
                sessionState == SessionState.LISTENING -> primaryCyan
                isAiSpeaking -> titaniumCyan
                else -> primaryCyan
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        outerGlowColor.copy(alpha = 0.35f * currentScale),
                        outerGlowColor.copy(alpha = 0.12f * currentScale),
                        Color.Transparent
                    ),
                    center = center,
                    radius = activeRadius * 1.85f
                ),
                center = center,
                radius = activeRadius * 1.85f
            )

            // 2. Secondary Harmonic Ripple Ring
            drawCircle(
                color = outerGlowColor.copy(alpha = 0.25f),
                center = center,
                radius = activeRadius * 1.25f,
                style = Stroke(width = 1.5.dp.toPx())
            )

            // 3. Core Frosted Glassmorphic Orb Body
            val coreColors = when {
                sessionState == SessionState.TOOL_EXECUTION -> listOf(deepAmber, Color(0xFFE65100))
                modelMode == GeminiModelMode.DEEP -> listOf(accentPurple, Color(0xFF3F51B5), primaryCyan)
                sessionState == SessionState.LISTENING -> listOf(primaryCyan, titaniumCyan, accentPurple)
                isAiSpeaking -> listOf(titaniumCyan, primaryCyan, royalGold.copy(alpha = 0.6f))
                else -> listOf(primaryCyan.copy(alpha = 0.85f), accentPurple.copy(alpha = 0.85f))
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = coreColors,
                    center = center - Offset(activeRadius * 0.25f, activeRadius * 0.25f),
                    radius = activeRadius
                ),
                center = center,
                radius = activeRadius
            )

            // Specular light edge highlight
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.6f),
                        outerGlowColor.copy(alpha = 0.2f),
                        Color.Transparent,
                        Color.White.copy(alpha = 0.4f)
                    ),
                    center = center
                ),
                center = center,
                radius = activeRadius,
                style = Stroke(width = 1.8.dp.toPx())
            )

            // 4. Thinking / Reasoning Orbiting Nodes (When Gemini is processing)
            if (sessionState == SessionState.WAITING_FOR_MODEL || sessionState == SessionState.PROCESSING_AUDIO) {
                val orbitRadius = activeRadius * 1.42f
                val nodeCount = if (modelMode == GeminiModelMode.DEEP) 4 else 3
                val nodeColor = if (modelMode == GeminiModelMode.DEEP) royalGold else titaniumCyan

                for (i in 0 until nodeCount) {
                    val angleDeg = rotationAngle + (i * (360f / nodeCount))
                    val angleRad = Math.toRadians(angleDeg.toDouble())
                    val nodeX = center.x + (orbitRadius * cos(angleRad)).toFloat()
                    val nodeY = center.y + (orbitRadius * sin(angleRad)).toFloat()

                    drawCircle(
                        color = nodeColor,
                        center = Offset(nodeX, nodeY),
                        radius = 4.5.dp.toPx()
                    )
                    // Mini node glow
                    drawCircle(
                        color = nodeColor.copy(alpha = 0.4f),
                        center = Offset(nodeX, nodeY),
                        radius = 8.dp.toPx()
                    )
                }
            }
        }
    }
}
