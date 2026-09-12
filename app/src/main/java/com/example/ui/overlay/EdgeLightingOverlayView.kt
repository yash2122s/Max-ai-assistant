package com.example.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator

/**
 * Visual feedback overlay rendering bold edge lighting and glow around the screen perimeter.
 * Decoupled and purely observational; does NOT intercept touch events.
 */
class EdgeLightingOverlayView(context: Context) : View(context) {

    enum class Mode {
        OFF,
        PLANNING,
        RUNNING,
        VERIFYING,
        SUCCESS,
        FAILURE
    }

    private val density = context.resources.displayMetrics.density
    private var currentMode = Mode.OFF

    // Core crisp neon border (scaled ~8dp = ~22-26px on 1080p/modern displays)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // Outer soft glow aura (scaled ~18dp = ~50-60px)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 18f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var strokeAlpha = 0f
    private var animator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent?): Boolean = false
    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean = false
    override fun onGenericMotionEvent(event: android.view.MotionEvent?): Boolean = false
    override fun onHoverEvent(event: android.view.MotionEvent?): Boolean = false

    fun setMode(mode: Mode) {
        if (currentMode == mode) return
        currentMode = mode
        animator?.cancel()

        when (mode) {
            Mode.OFF -> {
                strokeAlpha = 0f
                invalidate()
            }
            Mode.PLANNING -> {
                val color = Color.parseColor("#00B0FF") // Electric Cyan
                corePaint.color = color
                glowPaint.color = color
                startPulseAnimation(0.5f, 1.0f, 1000L)
            }
            Mode.RUNNING -> {
                val color = Color.parseColor("#D500F9") // Vibrant Magenta / Neon Purple
                corePaint.color = color
                glowPaint.color = color
                startPulseAnimation(0.6f, 1.0f, 800L)
            }
            Mode.VERIFYING -> {
                val color = Color.parseColor("#FFD600") // Luminous Golden Amber
                corePaint.color = color
                glowPaint.color = color
                startPulseAnimation(0.5f, 0.95f, 650L)
            }
            Mode.SUCCESS -> {
                val color = Color.parseColor("#00E676") // Vibrant Emerald Green
                corePaint.color = color
                glowPaint.color = color
                flashAndFade(900L)
            }
            Mode.FAILURE -> {
                val color = Color.parseColor("#FF1744") // Electric Crimson Red
                corePaint.color = color
                glowPaint.color = color
                flashAndFade(1100L)
            }
        }
    }

    private fun startPulseAnimation(minAlpha: Float, maxAlpha: Float, durationMs: Long) {
        animator = ValueAnimator.ofFloat(minAlpha, maxAlpha).apply {
            duration = durationMs
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                strokeAlpha = va.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun flashAndFade(durationMs: Long) {
        animator = ValueAnimator.ofFloat(1.0f, 0f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                strokeAlpha = va.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun turnOffImmediately() {
        animator?.cancel()
        animator = null
        currentMode = Mode.OFF
        strokeAlpha = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (currentMode == Mode.OFF || strokeAlpha <= 0f) return

        val outerHalf = glowPaint.strokeWidth / 2f
        val coreHalf = corePaint.strokeWidth / 2f

        val cornerRadius = 38f * density

        // 1. Draw Outer Glow Aura
        glowPaint.alpha = ((strokeAlpha * 0.42f) * 255).toInt().coerceIn(0, 255)
        val glowRect = RectF(outerHalf, outerHalf, width.toFloat() - outerHalf, height.toFloat() - outerHalf)
        canvas.drawRoundRect(glowRect, cornerRadius, cornerRadius, glowPaint)

        // 2. Draw Core Vibrant Neon Line
        corePaint.alpha = (strokeAlpha * 255).toInt().coerceIn(0, 255)
        val coreRect = RectF(coreHalf, coreHalf, width.toFloat() - coreHalf, height.toFloat() - coreHalf)
        canvas.drawRoundRect(coreRect, cornerRadius, cornerRadius, corePaint)
    }
}
