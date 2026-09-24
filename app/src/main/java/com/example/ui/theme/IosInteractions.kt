package com.example.ui.theme

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// ============================================================================
// MAX iOS-NATIVE INTERACTION PHYSICS, EDGE LIGHTING & TAPTIC ENGINE SYSTEM
// ============================================================================

/**
 * States for MAX Edge Lighting activation and voice feedback.
 */
enum class EdgeLightingState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}

/**
 * Checks whether system reduced-motion or disabled animator scale is active.
 */
fun isReducedMotionEnabled(context: Context): Boolean {
    return try {
        val durationScale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1.0f
        )
        val transitionScale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            1.0f
        )
        durationScale == 0f || transitionScale == 0f
    } catch (_: Exception) {
        false
    }
}

/**
 * iOS-native Spring Scale & Opacity Press Physics.
 * Replaces standard Android Material Ripples with continuous spring tactile feedback.
 */
fun Modifier.iosPressEffect(
    enabled: Boolean = true,
    scalePressed: Float = 0.96f,
    alphaPressed: Float = 0.88f,
    onClick: (() -> Unit)? = null
): Modifier = composed {
    if (!enabled) return@composed this

    val scaleAnim = remember { Animatable(1f) }
    val alphaAnim = remember { Animatable(1f) }
    val coroutineScope = rememberCoroutineScope()
    val view = LocalView.current

    this
        .scale(scaleAnim.value)
        .alpha(alphaAnim.value)
        .pointerInput(enabled, onClick) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                coroutineScope.launch {
                    scaleAnim.animateTo(
                        targetValue = scalePressed,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    )
                }
                coroutineScope.launch {
                    alphaAnim.animateTo(
                        targetValue = alphaPressed,
                        animationSpec = spring(stiffness = Spring.StiffnessMedium)
                    )
                }

                val upOrCancel = waitForUpOrCancellation()

                coroutineScope.launch {
                    scaleAnim.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        )
                    )
                }
                coroutineScope.launch {
                    alphaAnim.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(stiffness = Spring.StiffnessLow)
                    )
                }

                if (upOrCancel != null && onClick != null) {
                    MaxHaptics.lightImpact(view)
                    onClick()
                }
            }
        }
}

/**
 * iOS Specular Edge Gradient Border Modifier.
 * Simulates directional top-edge lighting reflection across dark squircle glass cards.
 */
fun Modifier.specularBorder(
    shape: Shape,
    borderWidth: Dp = 0.75.dp,
    highlightColor: Color = MaxBorderSpecular,
    baseColor: Color = MaxBorderHairline
): Modifier {
    val specularBrush = Brush.verticalGradient(
        colors = listOf(
            highlightColor,
            baseColor.copy(alpha = 0.6f),
            baseColor
        )
    )
    return this.border(
        border = BorderStroke(borderWidth, specularBrush),
        shape = shape
    )
}

/**
 * Native Samsung / iOS Edge Lighting Activation Glow Modifier.
 * Traces the four display edges with an animated gradient beam and dynamic mic amplitude reactivity.
 *
 * - Idle: No glow (fades out over 400ms).
 * - Listening: Ramps in over 300ms, sustains 1.2s breathing pulse, pulsing synced to mic amplitude.
 * - Speaking: Shifts to RoyalGold accent, then fades on session end.
 * - Reduced motion: Falls back to static thin border highlight.
 *
 * @param state Active assistant interaction state (IDLE, LISTENING, THINKING, SPEAKING).
 * @param amplitude Real-time microphone RMS volume (0.0f to 1.0f).
 * @param strokeWidth Width of the primary high-intensity laser core stroke.
 * @param glowRadius Radius / spread of the outer diffused halo glow.
 * @param cornerRadius Display corner radius to match device physical display curvature.
 */
fun Modifier.edgeLightingGlow(
    state: EdgeLightingState,
    amplitude: Float = 0f,
    strokeWidth: Dp = 4.dp,
    glowRadius: Dp = 18.dp,
    cornerRadius: Dp = 28.dp
): Modifier = composed {
    val context = LocalContext.current
    val isReducedMotion = remember(context) { isReducedMotionEnabled(context) }

    // 1. Master Alpha Transition (Ramps in over 300ms, fades out over 400ms)
    val glowAlphaAnim = remember { Animatable(0f) }
    LaunchedEffect(state) {
        if (state != EdgeLightingState.IDLE) {
            glowAlphaAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
            )
        } else {
            glowAlphaAnim.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 400, easing = LinearEasing)
            )
        }
    }

    // If completely idle and fully faded out, skip drawing entirely
    if (glowAlphaAnim.value <= 0.001f && state == EdgeLightingState.IDLE) {
        return@composed this
    }

    // 2. Color Transition: Listening (TitaniumCyan -> RoyalCyberBlue), Thinking (Royal Indigo), Speaking (RoyalGold)
    val targetPrimaryColor = when (state) {
        EdgeLightingState.SPEAKING -> MaxRoyalGold
        EdgeLightingState.THINKING -> MaxRoyalIndigo
        else -> MaxAccentCyan
    }
    val targetSecondaryColor = when (state) {
        EdgeLightingState.SPEAKING -> MaxRoyalGoldDark
        EdgeLightingState.THINKING -> MaxPrimaryRoyal
        else -> MaxPrimaryRoyal
    }

    val primaryColor by animateColorAsState(
        targetValue = targetPrimaryColor,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "edge_lighting_primary"
    )
    val secondaryColor by animateColorAsState(
        targetValue = targetSecondaryColor,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "edge_lighting_secondary"
    )

    // 3. Amplitude Smoothing with spring physics
    val smoothAmp = remember { Animatable(0f) }
    LaunchedEffect(amplitude) {
        smoothAmp.animateTo(
            targetValue = amplitude.coerceIn(0f, 1f),
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
    }

    // 4. Reduced Motion Fallback: static thin border highlight
    if (isReducedMotion) {
        return@composed this.drawWithContent {
            drawContent()
            val strokePx = 1.5.dp.toPx()
            val cornerPx = cornerRadius.toPx()
            val alpha = (glowAlphaAnim.value * 0.9f).coerceIn(0f, 1f)
            if (alpha > 0.01f) {
                drawRoundRect(
                    color = primaryColor.copy(alpha = alpha),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx, cornerPx),
                    style = Stroke(width = strokePx)
                )
            }
        }
    }

    // 5. Infinite Continuous Breathing Pulse (1.2s cycle = 600ms each way) & Traveling Sweep Gradient
    val infiniteTransition = rememberInfiniteTransition(label = "edge_lighting_loop")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathing_pulse"
    )

    this.drawWithContent {
        drawContent()

        val masterAlpha = glowAlphaAnim.value
        if (masterAlpha <= 0.001f) return@drawWithContent

        val w = size.width
        val h = size.height
        val cornerPx = cornerRadius.toPx()
        val ampFactor = smoothAmp.value
        val brightness = (pulse * 0.75f + ampFactor * 0.6f).coerceIn(0.4f, 1.4f)

        // Continuous perimeter sweep gradient brush (Titanium Cyan -> Cyber Blue -> Cyan)
        val gradientBrush = Brush.sweepGradient(
            colors = listOf(
                primaryColor.copy(alpha = (0.95f * brightness).coerceAtMost(1f)),
                secondaryColor.copy(alpha = (0.75f * brightness).coerceAtMost(1f)),
                primaryColor.copy(alpha = (0.35f * brightness).coerceAtMost(1f)),
                secondaryColor.copy(alpha = (0.85f * brightness).coerceAtMost(1f)),
                primaryColor.copy(alpha = (0.95f * brightness).coerceAtMost(1f))
            ),
            center = Offset(w / 2f, h / 2f)
        )

        val outerGlowWidthPx = (glowRadius.toPx() * (pulse * 0.8f + ampFactor * 0.5f)).coerceAtLeast(8.dp.toPx())
        val coreStrokeWidthPx = strokeWidth.toPx() * (1f + ampFactor * 0.4f)

        // Layer 1: Outer Soft Halo / Diffused Ambient Glow
        drawRoundRect(
            brush = gradientBrush,
            size = size,
            cornerRadius = CornerRadius(cornerPx, cornerPx),
            style = Stroke(
                width = outerGlowWidthPx,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            ),
            alpha = (masterAlpha * 0.45f * brightness).coerceIn(0f, 1f)
        )

        // Layer 2: Mid Bloom Glow
        drawRoundRect(
            brush = gradientBrush,
            size = size,
            cornerRadius = CornerRadius(cornerPx, cornerPx),
            style = Stroke(
                width = outerGlowWidthPx * 0.5f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            ),
            alpha = (masterAlpha * 0.75f * brightness).coerceIn(0f, 1f)
        )

        // Layer 3: Inner Intense Laser Core Stroke
        drawRoundRect(
            brush = gradientBrush,
            size = size,
            cornerRadius = CornerRadius(cornerPx, cornerPx),
            style = Stroke(
                width = coreStrokeWidthPx,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            ),
            alpha = (masterAlpha * 1.0f).coerceIn(0f, 1f)
        )
    }
}

/**
 * iOS/Native Edge-Origin Radial Reveal Animation Modifier.
 * Used when assistant is summoned via hardware Power-button or Assistant gesture.
 * Animates scale + alpha from screen edges inward (250-350ms, FastOutSlowInEasing).
 */
fun Modifier.edgeRevealAnimation(
    triggerKey: Any?,
    durationMs: Int = 300,
    onAnimationEnd: (() -> Unit)? = null
): Modifier = composed {
    if (triggerKey == null) return@composed this

    val scaleAnim = remember(triggerKey) { Animatable(0.92f) }
    val alphaAnim = remember(triggerKey) { Animatable(0.0f) }
    val view = LocalView.current

    LaunchedEffect(triggerKey) {
        MaxHaptics.mediumImpact(view)
        launch {
            scaleAnim.animateTo(
                targetValue = 1.0f,
                animationSpec = tween(durationMillis = durationMs, easing = FastOutSlowInEasing)
            )
        }
        launch {
            alphaAnim.animateTo(
                targetValue = 1.0f,
                animationSpec = tween(durationMillis = (durationMs * 0.85f).toInt(), easing = FastOutSlowInEasing)
            )
            onAnimationEnd?.invoke()
        }
    }

    this
        .scale(scaleAnim.value)
        .alpha(alphaAnim.value)
}

/**
 * iOS Taptic Engine feedback controller for Android hardware vibrators.
 */
object MaxHaptics {

    fun lightImpact(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun lightImpact(context: Context) {
        vibrate(context, 10, 40)
    }

    fun mediumImpact(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun mediumImpact(context: Context) {
        vibrate(context, 20, 80)
    }

    fun heavyImpact(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    fun successPattern(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    fun successPattern(context: Context) {
        vibratePattern(context, longArrayOf(0, 15, 60, 25), intArrayOf(0, 60, 0, 120))
    }

    fun errorPattern(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
    }

    fun errorPattern(context: Context) {
        vibratePattern(context, longArrayOf(0, 25, 50, 25, 50, 40), intArrayOf(0, 150, 0, 150, 0, 200))
    }

    private fun vibrate(context: Context, durationMs: Long, amplitude: Int) {
        try {
            val vibrator = getVibrator(context) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255)))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }

    private fun vibratePattern(context: Context, timings: LongArray, amplitudes: IntArray) {
        try {
            val vibrator = getVibrator(context) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, -1)
            }
        } catch (_: Exception) {}
    }

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
