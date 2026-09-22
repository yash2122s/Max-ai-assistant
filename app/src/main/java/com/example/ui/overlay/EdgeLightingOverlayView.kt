package com.example.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * MAX Edge Lighting Overlay
 *
 * Visual-only overlay.
 *
 * IMPORTANT:
 * - Never handles touch.
 * - WindowManager must use FLAG_NOT_TOUCHABLE.
 * - Border reaches the actual View/screen edge.
 * - Glow is allowed to bleed outside the core border.
 */
class EdgeLightingOverlayView(
    context: Context
) : View(context) {

    enum class Mode {
        OFF,
        VOICE_LIVE,
        PLANNING,
        RUNNING,
        VERIFYING,
        SUCCESS,
        FAILURE
    }

    private val density =
        context.resources.displayMetrics.density

    private var currentMode = Mode.OFF

    // ------------------------------------------------------------------------
    // Paint
    // ------------------------------------------------------------------------

    private val corePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isDither = true
        }

    private val glowPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 22f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isDither = true
        }

    // ------------------------------------------------------------------------
    // Animation
    // ------------------------------------------------------------------------

    private var strokeAlpha = 0f

    private var animator: ValueAnimator? = null
    private var flowAnimator: ValueAnimator? = null
    private var breathAnimator: ValueAnimator? = null

    private var rotationAngle = 0f
    private var breathPhase = 0f
    private var smoothedRms = 0f

    // ------------------------------------------------------------------------
    // Voice gradient
    // ------------------------------------------------------------------------

    private val fluidColors = intArrayOf(
        Color.parseColor("#00F5D4"), // Vibrant Neon Cyan new
        Color.parseColor("#00BBF9"), // Electric Sky Blue
        Color.parseColor("#4361EE"), // Royal Electric Blue
        Color.parseColor("#7209B7"), // Deep Neon Purple
        Color.parseColor("#F72585"), // Electric Pink / Magenta
        Color.parseColor("#FF007F"), // Vivid Neon Rose
        Color.parseColor("#FF5400"), // Radiant Neon Orange
        Color.parseColor("#FFD166"), // Warm Glow Yellow / Amber
        Color.parseColor("#06D6A0"), // Emerald Mint
        Color.parseColor("#00F5D4")  // Seamless loop to Neon Cyan
    )

    private val shaderMatrix = Matrix()

    private val borderRect = RectF()

    // ------------------------------------------------------------------------
    // Initialization
    // ------------------------------------------------------------------------

    init {
        setWillNotDraw(false)

        // View itself never handles input.
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false

        setBackgroundColor(Color.TRANSPARENT)
    }

    // ------------------------------------------------------------------------
    // TOUCH SAFETY
    // ------------------------------------------------------------------------

    override fun dispatchTouchEvent(
        event: MotionEvent?
    ): Boolean {
        // Never consume touch.
        return false
    }

    override fun onTouchEvent(
        event: MotionEvent?
    ): Boolean {
        // Never consume touch.
        return false
    }

    override fun onGenericMotionEvent(
        event: MotionEvent?
    ): Boolean {
        return false
    }

    override fun onHoverEvent(
        event: MotionEvent?
    ): Boolean {
        return false
    }

    // ------------------------------------------------------------------------
    // VOICE RMS
    // ------------------------------------------------------------------------

    fun updateRms(rawRms: Float) {

        val clamped =
            rawRms.coerceIn(0f, 1f)

        smoothedRms =
            smoothedRms * 0.72f +
                    clamped * 0.28f

        if (currentMode == Mode.VOICE_LIVE) {
            postInvalidateOnAnimation()
        }
    }

    // ------------------------------------------------------------------------
    // MODE
    // ------------------------------------------------------------------------

    fun setMode(mode: Mode) {

        if (currentMode == mode) {
            return
        }

        currentMode = mode

        animator?.cancel()
        animator = null

        if (mode != Mode.VOICE_LIVE) {

            stopFlowAnimation()

            corePaint.shader = null
            glowPaint.shader = null
        }

        if (mode == Mode.OFF) {

            stopBreathAnimation()

            strokeAlpha = 0f

            postInvalidateOnAnimation()

            return
        }

        startBreathAnimation()

        when (mode) {

            Mode.VOICE_LIVE -> {

                strokeAlpha = 1f

                startFlowAnimation()
            }

            Mode.PLANNING -> {

                val color =
                    Color.parseColor("#00B0FF")

                corePaint.color = color
                glowPaint.color = color

                startPulseAnimation(
                    minAlpha = 0.55f,
                    maxAlpha = 1f,
                    durationMs = 1400L
                )
            }

            Mode.RUNNING -> {

                val color =
                    Color.parseColor("#D500F9")

                corePaint.color = color
                glowPaint.color = color

                startPulseAnimation(
                    minAlpha = 0.60f,
                    maxAlpha = 1f,
                    durationMs = 1000L
                )
            }

            Mode.VERIFYING -> {

                val color =
                    Color.parseColor("#FFD600")

                corePaint.color = color
                glowPaint.color = color

                startPulseAnimation(
                    minAlpha = 0.50f,
                    maxAlpha = 0.95f,
                    durationMs = 800L
                )
            }

            Mode.SUCCESS -> {

                val color =
                    Color.parseColor("#00E676")

                corePaint.color = color
                glowPaint.color = color

                flashAndFade(1000L)
            }

            Mode.FAILURE -> {

                val color =
                    Color.parseColor("#FF1744")

                corePaint.color = color
                glowPaint.color = color

                flashAndFade(1200L)
            }

            Mode.OFF -> Unit
        }
    }

    // ------------------------------------------------------------------------
    // FLOW
    // ------------------------------------------------------------------------

    private fun startFlowAnimation() {

        flowAnimator?.cancel()

        flowAnimator =
            ValueAnimator.ofFloat(
                0f,
                360f
            ).apply {

                duration = 2600L

                repeatCount =
                    ValueAnimator.INFINITE

                repeatMode =
                    ValueAnimator.RESTART

                interpolator =
                    LinearInterpolator()

                addUpdateListener { animation ->

                    rotationAngle =
                        animation.animatedValue as Float

                    postInvalidateOnAnimation()
                }

                start()
            }
    }

    private fun stopFlowAnimation() {

        flowAnimator?.cancel()
        flowAnimator = null
    }

    // ------------------------------------------------------------------------
    // BREATHING
    // ------------------------------------------------------------------------

    private fun startBreathAnimation() {

        if (breathAnimator != null) {
            return
        }

        breathAnimator =
            ValueAnimator.ofFloat(
                0f,
                (Math.PI * 2.0).toFloat()
            ).apply {

                duration = 2400L

                repeatCount =
                    ValueAnimator.INFINITE

                repeatMode =
                    ValueAnimator.RESTART

                interpolator =
                    LinearInterpolator()

                addUpdateListener { animation ->

                    breathPhase =
                        animation.animatedValue as Float

                    postInvalidateOnAnimation()
                }

                start()
            }
    }

    private fun stopBreathAnimation() {

        breathAnimator?.cancel()
        breathAnimator = null
    }

    // ------------------------------------------------------------------------
    // PULSE
    // ------------------------------------------------------------------------

    private fun startPulseAnimation(
        minAlpha: Float,
        maxAlpha: Float,
        durationMs: Long
    ) {

        animator =
            ValueAnimator.ofFloat(
                minAlpha,
                maxAlpha
            ).apply {

                duration = durationMs

                repeatMode =
                    ValueAnimator.REVERSE

                repeatCount =
                    ValueAnimator.INFINITE

                interpolator =
                    LinearInterpolator()

                addUpdateListener { animation ->

                    strokeAlpha =
                        animation.animatedValue as Float

                    postInvalidateOnAnimation()
                }

                start()
            }
    }

    // ------------------------------------------------------------------------
    // SUCCESS / FAILURE
    // ------------------------------------------------------------------------

    private fun flashAndFade(
        durationMs: Long
    ) {

        animator =
            ValueAnimator.ofFloat(
                1f,
                0f
            ).apply {

                duration = durationMs

                interpolator =
                    DecelerateInterpolator()

                addUpdateListener { animation ->

                    strokeAlpha =
                        animation.animatedValue as Float

                    postInvalidateOnAnimation()
                }

                start()
            }
    }

    // ------------------------------------------------------------------------
    // OFF
    // ------------------------------------------------------------------------

    fun turnOffImmediately() {

        animator?.cancel()
        animator = null

        stopFlowAnimation()
        stopBreathAnimation()

        currentMode = Mode.OFF

        strokeAlpha = 0f

        postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------------
    // DRAW
    // ------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {

        super.onDraw(canvas)

        if (
            currentMode == Mode.OFF ||
            strokeAlpha <= 0f ||
            width <= 0 ||
            height <= 0
        ) {
            return
        }

        val w =
            width.toFloat()

        val h =
            height.toFloat()

        val centerX =
            w * 0.5f

        val centerY =
            h * 0.5f

        // --------------------------------------------------------------------
        // Gentle breathing
        // --------------------------------------------------------------------

        val breath =
            sin(breathPhase) * 0.06f

        // --------------------------------------------------------------------
        // VOICE
        // --------------------------------------------------------------------

        if (currentMode == Mode.VOICE_LIVE) {

            val gradient =
                SweepGradient(
                    centerX,
                    centerY,
                    fluidColors,
                    null
                )

            val speedFactor =
                1f +
                        smoothedRms * 1.35f

            shaderMatrix.setRotate(
                rotationAngle * speedFactor,
                centerX,
                centerY
            )

            gradient.setLocalMatrix(
                shaderMatrix
            )

            corePaint.shader = gradient
            glowPaint.shader = gradient

            corePaint.strokeWidth =
                (8f +
                        smoothedRms * 5f) *
                        density *
                        (1f + breath.toFloat())

            glowPaint.strokeWidth =
                (26f +
                        smoothedRms * 14f) *
                        density *
                        (1f + breath.toFloat())

        } else {

            corePaint.shader = null
            glowPaint.shader = null

            corePaint.strokeWidth =
                6f *
                        density *
                        (1f + breath.toFloat())

            glowPaint.strokeWidth =
                22f *
                        density *
                        (1f + breath.toFloat())
        }

        // --------------------------------------------------------------------
        // TRUE EDGE POSITIONING
        // --------------------------------------------------------------------
        //
        // Core stroke is positioned exactly against the View boundary.
        //
        // The glow extends outside the boundary and is naturally clipped
        // by the display/window edge.
        // --------------------------------------------------------------------

        val coreHalf =
            corePaint.strokeWidth * 0.5f

        val edgePadding =
            coreHalf

        borderRect.set(
            edgePadding,
            edgePadding,
            w - edgePadding,
            h - edgePadding
        )

        val cornerRadius =
            minOf(
                34f * density,
                minOf(w, h) * 0.08f
            )

        // --------------------------------------------------------------------
        // GLOW
        // --------------------------------------------------------------------

        val glowAlpha =
            (
                    strokeAlpha *
                            (
                                    0.40f +
                                            smoothedRms * 0.18f
                                    ) *
                            255f
                    )
                .toInt()
                .coerceIn(0, 255)

        glowPaint.alpha =
            glowAlpha

        canvas.drawRoundRect(
            borderRect,
            cornerRadius,
            cornerRadius,
            glowPaint
        )

        // --------------------------------------------------------------------
        // CORE
        // --------------------------------------------------------------------

        corePaint.alpha =
            (strokeAlpha * 255f)
                .toInt()
                .coerceIn(0, 255)

        canvas.drawRoundRect(
            borderRect,
            cornerRadius,
            cornerRadius,
            corePaint
        )

        corePaint.alpha = 255
        glowPaint.alpha = 255
    }

    // ------------------------------------------------------------------------
    // WINDOW PARAMS HELPER
    // ------------------------------------------------------------------------
    //
    // IMPORTANT:
    // Your WindowManager caller MUST use these params.
    // This is what makes the entire overlay touch-through.
    // ------------------------------------------------------------------------

    companion object {

        fun createTouchThroughLayoutParams():
                WindowManager.LayoutParams {

            return WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {

                gravity =
                    Gravity.TOP or
                            Gravity.START

                x = 0
                y = 0
            }
        }
    }

    // ------------------------------------------------------------------------
    // CLEANUP
    // ------------------------------------------------------------------------

    override fun onDetachedFromWindow() {

        super.onDetachedFromWindow()

        animator?.cancel()
        animator = null

        stopFlowAnimation()
        stopBreathAnimation()
    }
}