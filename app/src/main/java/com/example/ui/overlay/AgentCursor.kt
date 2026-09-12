package com.example.ui.overlay

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.NativePaint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

enum class AgentCursorState {
    IDLE,
    THINKING,
    CLICKING,
    DRAGGING
}

/**
 * High-fidelity macOS-style agent cursor with drop shadow, rounded stroke,
 * click shockwave ripple, dynamic glow aura, and spring physics.
 */
@Composable
fun AgentCursor(
    targetPosition: Offset,
    modifier: Modifier = Modifier,
    state: AgentCursorState = AgentCursorState.IDLE,
    visible: Boolean = true,
    cursorSize: Dp = 26.dp,
    strokeWidth: Dp = 2.5.dp,
    cursorColor: Color = Color.Black,
    borderColor: Color = Color.White
) {
    // 1. Natural gliding physics towards target coordinates
    val animatedX by animateFloatAsState(
        targetValue = targetPosition.x,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "cursorX"
    )
    val animatedY by animateFloatAsState(
        targetValue = targetPosition.y,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "cursorY"
    )

    val cursorAlpha by animateFloatAsState(
        targetValue = if (visible && state != AgentCursorState.IDLE) 1f else if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "cursorAlpha"
    )

    // 2. Click shockwave animation
    val rippleRadius = remember { Animatable(0f) }
    val rippleAlpha = remember { Animatable(0f) }

    LaunchedEffect(state) {
        if (state == AgentCursorState.CLICKING) {
            rippleRadius.snapTo(0f)
            rippleAlpha.snapTo(0.7f)
            rippleRadius.animateTo(
                targetValue = 45f,
                animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)
            )
            rippleAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 200)
            )
        }
    }

    // 3. Status pulsing aura ("Thinking" state)
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val auraPulse = if (state == AgentCursorState.THINKING) {
        val anim by infiniteTransition.animateFloat(
            initialValue = 0.8f,
            targetValue = 1.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "auraScale"
        )
        anim
    } else {
        1.0f
    }

    // 4. Squash/Scale down on click
    val cursorScale by animateFloatAsState(
        targetValue = if (state == AgentCursorState.CLICKING) 0.86f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "scale"
    )

    if (cursorAlpha <= 0f && !visible) return

    Box(
        modifier = modifier
            .offset { IntOffset(animatedX.roundToInt(), animatedY.roundToInt()) }
            .size(cursorSize * 2.2f)
            .graphicsLayer {
                scaleX = cursorScale
                scaleY = cursorScale
                alpha = cursorAlpha
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = cursorSize.toPx()
            val h = cursorSize.toPx() * 1.25f

            // --- Precise Rounded Arrow Path ---
            val cursorPath = Path().apply {
                // Tip
                moveTo(w * 0.15f, h * 0.08f)
                // Left vertical edge
                lineTo(w * 0.15f, h * 0.72f)
                // Bottom-left rounded vertex
                quadraticTo(
                    w * 0.15f, h * 0.80f,
                    w * 0.25f, h * 0.76f
                )
                // Inward notch / crook
                quadraticTo(
                    w * 0.44f, h * 0.65f,
                    w * 0.58f, h * 0.67f
                )
                // Right wing point
                lineTo(w * 0.82f, h * 0.58f)
                // Right wing rounded vertex
                quadraticTo(
                    w * 0.90f, h * 0.52f,
                    w * 0.83f, h * 0.44f
                )
                // Upper slant back to tip
                lineTo(w * 0.28f, h * 0.08f)
                // Tip rounded apex
                quadraticTo(
                    w * 0.15f, h * 0.02f,
                    w * 0.15f, h * 0.08f
                )
                close()
            }

            // --- EFFECT 1: Agent Thinking Glow Aura ---
            if (state == AgentCursorState.THINKING) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF6366F1).copy(alpha = 0.45f),
                            Color(0xFF8B5CF6).copy(alpha = 0.20f),
                            Color.Transparent
                        ),
                        center = Offset(w * 0.15f, h * 0.08f),
                        radius = (w * 1.5f) * auraPulse
                    ),
                    radius = (w * 1.5f) * auraPulse,
                    center = Offset(w * 0.15f, h * 0.08f)
                )
            }

            // --- EFFECT 2: Click Ripple Shockwave ---
            if (rippleAlpha.value > 0f) {
                drawCircle(
                    color = Color(0xFF38BDF8).copy(alpha = rippleAlpha.value),
                    radius = rippleRadius.value * density,
                    center = Offset(w * 0.15f, h * 0.08f),
                    style = Stroke(width = 2.dp.toPx())
                )
            }

            // --- LAYER 1: Realistic Diffused Drop Shadow ---
            drawIntoCanvas { canvas ->
                val shadowPaint = NativePaint().apply {
                    color = android.graphics.Color.argb(80, 0, 0, 0)
                    isAntiAlias = true
                    maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
                }
                canvas.nativeCanvas.save()
                canvas.nativeCanvas.translate(2f, 5f)
                canvas.nativeCanvas.drawPath(cursorPath.asAndroidPath(), shadowPaint)
                canvas.nativeCanvas.restore()
            }

            // --- LAYER 2: Crisp Rounded Outer White Border ---
            drawPath(
                path = cursorPath,
                color = borderColor,
                style = Stroke(
                    width = strokeWidth.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // --- LAYER 3: Solid Black Fill ---
            drawPath(
                path = cursorPath,
                color = cursorColor,
                style = Fill
            )
        }
    }
}
