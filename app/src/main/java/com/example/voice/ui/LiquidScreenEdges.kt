package com.example.voice.ui

import android.graphics.BlurMaskFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import com.example.ui.theme.MaxTheme
import kotlin.math.sin

enum class SiriGlowState {
    LISTENING,
    THINKING,
    SPEAKING,
    IDLE
}

/**
 * Advanced Apple Intelligence (iOS 18+) Perimeter Edge Lighting.
 *
 * Architecture:
 * - 3-layer GPU-accelerated glow pipeline with BlurMaskFilter for soft diffused light spread.
 * - Sine-wave based organic pendulum movement.
 * - RMS audio reactivity with exponential moving average smoothing.
 * - Soft, premium pastel gradient palette.
 */
@Composable
fun LiquidScreenEdges(
    modifier: Modifier = Modifier,
    state: SiriGlowState = SiriGlowState.LISTENING,
    rms: Float = 0f
) {
    val density = LocalDensity.current.density

    // Smooth state intensity transition
    val targetIntensity = if (state == SiriGlowState.IDLE) 0f else 1f
    val smoothIntensity by animateFloatAsState(
        targetValue = targetIntensity,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "intensityTransition"
    )

    // Smooth RMS with exponential moving average (no flicker)
    var smoothedRms by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(rms) {
        smoothedRms = smoothedRms * 0.75f + rms.coerceIn(0f, 1f) * 0.25f
    }

    // Fluid Motion Drivers (Sine-wave based for organic feel)
    val infiniteTransition = rememberInfiniteTransition(label = "siri_edge")

    val rotationPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotationPhase"
    )

    val breathPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "breathPhase"
    )

    // Apple Intelligence Advanced Palette (Soft, Premium Pastels)
    val advancedColors = remember {
        intArrayOf(
            0xFF00D2FF.toInt(), // Soft Cyan
            0xFF3A86FF.toInt(), // Soft Blue
            0xFF8338EC.toInt(), // Royal Violet
            0xFFFF006E.toInt(), // Soft Pink
            0xFFFFBE0B.toInt(), // Soft Amber
            0xFF00F5D4.toInt(), // Mint
            0xFF00D2FF.toInt()  // Loop back
        )
    }

    // Reusable drawing objects (Zero allocation per frame)
    val shaderMatrix = remember { Matrix() }
    val borderRect = remember { RectF() }

    // Layer 1: Deep Bloom (Wide, heavily blurred for soft atmospheric light spread)
    val bloomPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            maskFilter = BlurMaskFilter(60f, BlurMaskFilter.Blur.NORMAL)
        }
    }

    // Layer 2: Mid Glow (Medium width, soft blur)
    val midGlowPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            maskFilter = BlurMaskFilter(15f, BlurMaskFilter.Blur.NORMAL)
        }
    }

    // Layer 3: Core Filament (Thin, sharp, bright)
    val corePaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f || smoothIntensity <= 0.01f) return@Canvas

        val centerX = w * 0.5f
        val centerY = h * 0.5f
        val cRms = smoothedRms.coerceIn(0f, 1f)

        // Fluid Motion calculations
        val fluidRotation = sin(rotationPhase.toDouble()).toFloat() * 180f
        val breath = sin(breathPhase.toDouble()).toFloat() * 0.05f + 1f

        // Dynamic Multiplier based on state and audio
        val dynamicMultiplier = when (state) {
            SiriGlowState.LISTENING -> (1f + cRms * 1.5f) * breath
            SiriGlowState.THINKING -> 0.85f + (sin(rotationPhase * 2.0).toFloat() * 0.15f)
            SiriGlowState.SPEAKING -> (1f + sin(rotationPhase * 3.0).toFloat() * 0.2f) * breath
            SiriGlowState.IDLE -> 0f
        } * smoothIntensity

        if (dynamicMultiplier <= 0.01f) return@Canvas

        // Setup Gradient
        val gradient = SweepGradient(centerX, centerY, advancedColors, null)
        shaderMatrix.setRotate(fluidRotation, centerX, centerY)
        gradient.setLocalMatrix(shaderMatrix)

        // Configure Paints with Dynamic Widths and Alphas
        val baseWidth = 8f * density * dynamicMultiplier

        bloomPaint.shader = gradient
        bloomPaint.strokeWidth = baseWidth * 4.5f
        bloomPaint.alpha = ((0.15f + cRms * 0.15f) * smoothIntensity * 255f).toInt().coerceIn(0, 255)

        midGlowPaint.shader = gradient
        midGlowPaint.strokeWidth = baseWidth * 1.8f
        midGlowPaint.alpha = ((0.45f + cRms * 0.25f) * smoothIntensity * 255f).toInt().coerceIn(0, 255)

        corePaint.shader = gradient
        corePaint.strokeWidth = baseWidth * 0.4f
        corePaint.alpha = ((0.95f + cRms * 0.05f) * smoothIntensity * 255f).toInt().coerceIn(0, 255)

        // Border Rect & Corner Radius (Matching device bezel)
        borderRect.set(0f, 0f, w, h)
        val cornerRadius = minOf(38f * density, minOf(w, h) * 0.085f)

        // 3-Pass Advanced Rendering Pipeline
        drawContext.canvas.nativeCanvas.apply {
            // Pass 1: Deep Atmospheric Bloom (Widest, softest)
            drawRoundRect(borderRect, cornerRadius, cornerRadius, bloomPaint)
            // Pass 2: Mid Chromatic Aura (Saturated glow)
            drawRoundRect(borderRect, cornerRadius, cornerRadius, midGlowPaint)
            // Pass 3: Core Neon Filament (Bright, crisp edge)
            drawRoundRect(borderRect, cornerRadius, cornerRadius, corePaint)
        }
    }
}

@Preview(name = "Liquid Screen Edges Preview", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun LiquidScreenEdgesPreview() {
    MaxTheme(darkTheme = true) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            LiquidScreenEdges(
                state = SiriGlowState.LISTENING,
                rms = 0.5f
            )
        }
    }
}
