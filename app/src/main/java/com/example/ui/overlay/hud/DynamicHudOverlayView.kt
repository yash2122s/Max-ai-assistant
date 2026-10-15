package com.example.ui.overlay.hud

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.core.view.GestureDetectorCompat
import com.example.network.model.GeminiModelMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 60 FPS Hardware-Accelerated Dynamic HUD Overlay View.
 *
 * Implements MAX's futuristic, glassmorphic HUD design with:
 * - Compact Pill vs Expanded Panel vs Quick Controls vs Details view modes.
 * - Dynamic state-driven animations (Waveform, Thinking Dots, Tool Progress).
 * - Interactive gestures (Tap-to-interrupt, Swipe Up/Down, Long-Press Quick Controls, Drag & Move).
 */
class DynamicHudOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // Callbacks for service and assistant interaction
    var onBoundsChangeListener: ((widthPx: Int, heightPx: Int) -> Unit)? = null
    var onPositionChangeListener: ((deltaX: Int, deltaY: Int) -> Unit)? = null
    var onInterruptRequest: (() -> Unit)? = null
    var onToggleMicRequest: (() -> Unit)? = null
    var onToggleModelModeRequest: (() -> Unit)? = null
    var onOpenAppRequest: (() -> Unit)? = null
    var onCloseOverlayRequest: (() -> Unit)? = null
    var onActivateVoiceLiveRequest: (() -> Unit)? = null

    var currentState: HudState = HudState.Idle
        private set

    var currentViewMode: HudViewMode = HudViewMode.COMPACT
        private set

    private val density = resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())

    // Design Tokens (Obsidian & Cyan / Purple Accent)
    private val colorBgDark = Color.parseColor("#E60B0F14")        // Frosted obsidian glass
    private val colorBorderSpecular = Color.parseColor("#3300A8FF") // Cyber cyan hairline
    private val colorPrimary = Color.parseColor("#00A8FF")        // Electric cyan/royal
    private val colorAccent = Color.parseColor("#7C4DFF")         // Cyber purple
    private val colorSuccess = Color.parseColor("#30D158")        // Ready green
    private val colorWarning = Color.parseColor("#FF9F0A")        // Tool amber
    private val colorError = Color.parseColor("#FF453A")          // Error red
    private val colorTextPrimary = Color.WHITE
    private val colorTextSecondary = Color.parseColor("#94A3B8")
    private val colorTextTertiary = Color.parseColor("#64748B")

    // Dynamic Visual Dimensions (in pixels)
    private var targetWidthPx: Int = (110 * density).toInt()
    private var targetHeightPx: Int = (36 * density).toInt()
    private var currentWidthPx: Int = targetWidthPx
    private var currentHeightPx: Int = targetHeightPx

    // Drawing Paints & Rects
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorBgDark
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorBorderSpecular
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val containerRect = RectF()
    private val tempRect = RectF()

    // Animation & Waveform State
    private var animTime: Float = 0f
    private var smoothedRms: Float = 0f
    private var thinkingDotOffset: Float = 0f
    private var progressSweep: Float = 0f

    // Animators
    private var dimensionAnimator: ValueAnimator? = null
    private var continuousLoopAnimator: ValueAnimator? = null

    // Gesture Detector for Swipes, Taps, Long-Press
    private val gestureDetector: GestureDetectorCompat
    private var isDragging = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = false

        // Start continuous 60fps animator for visualizer and dot animations
        continuousLoopAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                animTime = animator.animatedFraction
                thinkingDotOffset = (animTime * 360f)
                progressSweep = (animTime * 100f)
                invalidate()
            }
            start()
        }

        // Gesture handling
        gestureDetector = GestureDetectorCompat(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                handleTap(e.x, e.y)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                setViewMode(HudViewMode.QUICK_CONTROLS)
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val dy = e2.y - e1.y
                if (dy > 40 && currentViewMode != HudViewMode.COMPACT) {
                    // Swipe Down -> Collapse
                    setViewMode(HudViewMode.COMPACT)
                    return true
                } else if (dy < -40 && currentViewMode == HudViewMode.COMPACT) {
                    // Swipe Up -> Details Panel
                    setViewMode(HudViewMode.DETAILS)
                    return true
                }
                return false
            }
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - lastTouchX).toInt()
                val dy = (event.rawY - lastTouchY).toInt()
                if (abs(dx) > 8 || abs(dy) > 8) {
                    isDragging = true
                    onPositionChangeListener?.invoke(dx, dy)
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTap(x: Float, y: Float) {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

        // 1. If currently Speaking, tap interrupts speech instantly
        if (currentState is HudState.Speaking) {
            onInterruptRequest?.invoke()
            return
        }

        // 2. Handle button clicks inside Expanded or Quick Controls mode
        if (currentViewMode == HudViewMode.EXPANDED) {
            // Check top-right close button
            if (x >= width - 40 * density && y <= 40 * density) {
                setViewMode(HudViewMode.COMPACT)
                return
            }
            // Check bottom action buttons
            val btnY = height - 44 * density
            if (y >= btnY) {
                if (x <= width / 2f) {
                    // Left button: Stop
                    onInterruptRequest?.invoke()
                    setViewMode(HudViewMode.COMPACT)
                } else {
                    // Right button: Details
                    setViewMode(HudViewMode.DETAILS)
                }
                return
            }
            // Toggle to compact
            setViewMode(HudViewMode.COMPACT)
        } else if (currentViewMode == HudViewMode.QUICK_CONTROLS) {
            handleQuickControlsClick(x, y)
        } else if (currentViewMode == HudViewMode.DETAILS) {
            // Tap outside close button -> compact
            setViewMode(HudViewMode.COMPACT)
        } else {
            // In Compact mode, tapping activates Voice Live
            onActivateVoiceLiveRequest?.invoke()
        }
    }

    private fun handleQuickControlsClick(x: Float, y: Float) {
        val colWidth = width / 4f
        val col = (x / colWidth).toInt().coerceIn(0, 3)
        when (col) {
            0 -> onToggleMicRequest?.invoke()
            1 -> onInterruptRequest?.invoke()
            2 -> onToggleModelModeRequest?.invoke()
            3 -> onOpenAppRequest?.invoke()
        }
        setViewMode(HudViewMode.COMPACT)
    }

    /**
     * Updates the active HUD state and triggers smooth layout/animation updates.
     */
    fun updateState(newState: HudState) {
        if (currentState == newState) return
        val oldState = currentState
        currentState = newState

        // Auto-expand on tool execution or thinking if compact
        if (oldState is HudState.Idle && (newState is HudState.Listening || newState is HudState.Thinking || newState is HudState.ExecutingTool)) {
            // Maintain compact or subtle pill expansion
        }

        calculateTargetBounds()
        invalidate()
    }

    /**
     * Switches view mode between Compact Pill, Expanded Card, Quick Controls, and Details.
     */
    fun setViewMode(mode: HudViewMode) {
        if (currentViewMode == mode) return
        currentViewMode = mode
        calculateTargetBounds()
        invalidate()
    }

    /**
     * Updates live microphone or output audio RMS for waveform visualization.
     */
    fun updateRms(rms: Float) {
        smoothedRms = (smoothedRms * 0.7f) + (rms * 0.3f)
        invalidate()
    }

    private fun calculateTargetBounds() {
        when (currentViewMode) {
            HudViewMode.COMPACT -> {
                when (currentState) {
                    is HudState.Idle -> {
                        targetWidthPx = (105 * density).toInt()
                        targetHeightPx = (34 * density).toInt()
                    }
                    is HudState.Listening -> {
                        targetWidthPx = (150 * density).toInt()
                        targetHeightPx = (36 * density).toInt()
                    }
                    is HudState.Thinking -> {
                        targetWidthPx = (165 * density).toInt()
                        targetHeightPx = (36 * density).toInt()
                    }
                    is HudState.Speaking -> {
                        targetWidthPx = (160 * density).toInt()
                        targetHeightPx = (36 * density).toInt()
                    }
                    is HudState.ExecutingTool -> {
                        targetWidthPx = (180 * density).toInt()
                        targetHeightPx = (38 * density).toInt()
                    }
                    is HudState.Reconnecting -> {
                        targetWidthPx = (155 * density).toInt()
                        targetHeightPx = (36 * density).toInt()
                    }
                    is HudState.Error -> {
                        targetWidthPx = (160 * density).toInt()
                        targetHeightPx = (36 * density).toInt()
                    }
                }
            }
            HudViewMode.EXPANDED -> {
                targetWidthPx = (290 * density).toInt()
                targetHeightPx = (155 * density).toInt()
            }
            HudViewMode.QUICK_CONTROLS -> {
                targetWidthPx = (270 * density).toInt()
                targetHeightPx = (72 * density).toInt()
            }
            HudViewMode.DETAILS -> {
                targetWidthPx = (300 * density).toInt()
                targetHeightPx = (200 * density).toInt()
            }
        }

        animateDimensionsTo(targetWidthPx, targetHeightPx)
    }

    private fun animateDimensionsTo(targetW: Int, targetH: Int) {
        dimensionAnimator?.cancel()
        val startW = currentWidthPx
        val startH = currentHeightPx

        if (startW == targetW && startH == targetH) {
            onBoundsChangeListener?.invoke(targetW, targetH)
            return
        }

        dimensionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            interpolator = OvershootInterpolator(0.8f)
            addUpdateListener { anim ->
                val frac = anim.animatedFraction
                currentWidthPx = (startW + (targetW - startW) * frac).toInt()
                currentHeightPx = (startH + (targetH - startH) * frac).toInt()
                onBoundsChangeListener?.invoke(currentWidthPx, currentHeightPx)
                requestLayout()
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(currentWidthPx, currentHeightPx)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cornerRadius = when (currentViewMode) {
            HudViewMode.COMPACT -> h / 2f
            HudViewMode.EXPANDED, HudViewMode.DETAILS -> 24f * density
            HudViewMode.QUICK_CONTROLS -> 20f * density
        }

        containerRect.set(1.5f * density, 1.5f * density, w - 1.5f * density, h - 1.5f * density)

        // 1. Draw Frosted Glassmorphic Background & Glow
        canvas.drawRoundRect(containerRect, cornerRadius, cornerRadius, bgPaint)

        // Specular glow border
        val glowColor = when (currentState) {
            is HudState.Thinking -> colorAccent
            is HudState.Listening -> colorPrimary
            is HudState.Speaking -> colorPrimary
            is HudState.ExecutingTool -> colorWarning
            is HudState.Error -> colorError
            is HudState.Reconnecting -> colorWarning
            is HudState.Idle -> colorBorderSpecular
        }
        borderPaint.color = glowColor
        canvas.drawRoundRect(containerRect, cornerRadius, cornerRadius, borderPaint)

        // 2. Draw Mode-Specific Layout
        when (currentViewMode) {
            HudViewMode.COMPACT -> drawCompactPill(canvas, w, h)
            HudViewMode.EXPANDED -> drawExpandedCard(canvas, w, h)
            HudViewMode.QUICK_CONTROLS -> drawQuickControls(canvas, w, h)
            HudViewMode.DETAILS -> drawDetailsView(canvas, w, h)
        }
    }

    // =========================================================================
    // COMPACT PILL RENDERING
    // =========================================================================

    private fun drawCompactPill(canvas: Canvas, w: Float, h: Float) {
        val centerY = h / 2f

        when (val state = currentState) {
            is HudState.Idle -> {
                // Identity Orb
                drawStatusDot(canvas, 16f * density, centerY, colorSuccess)

                // "MAX" Label
                textPaint.color = colorTextPrimary
                textPaint.textSize = 12f * density
                canvas.drawText("MAX", 28f * density, centerY + 4.5f * density, textPaint)

                // "Ready" sub-text
                textPaint.color = colorTextSecondary
                textPaint.textSize = 10f * density
                canvas.drawText("Ready", 64f * density, centerY + 3.5f * density, textPaint)
            }

            is HudState.Listening -> {
                // Glowing Mic Indicator
                drawStatusDot(canvas, 14f * density, centerY, colorPrimary)

                // "Listening" Label
                textPaint.color = colorTextPrimary
                textPaint.textSize = 11.5f * density
                canvas.drawText("Listening", 26f * density, centerY + 4f * density, textPaint)

                // Mini Waveform Bars on right
                drawWaveformBars(canvas, w - 46f * density, centerY, 5, smoothedRms)
            }

            is HudState.Thinking -> {
                // Thinking Dots on left
                drawThinkingDots(canvas, 14f * density, centerY, state.mode)

                // "Thinking..."
                textPaint.color = if (state.mode == GeminiModelMode.DEEP) colorAccent else colorPrimary
                textPaint.textSize = 11f * density
                val label = if (state.mode == GeminiModelMode.DEEP) "Deep Thinking" else "Thinking..."
                canvas.drawText(label, 48f * density, centerY + 4f * density, textPaint)
            }

            is HudState.Speaking -> {
                // Live Cyan Waveform
                drawWaveformBars(canvas, 14f * density, centerY, 4, smoothedRms + 0.4f)

                // "Speaking" Label
                textPaint.color = colorTextPrimary
                textPaint.textSize = 11.5f * density
                canvas.drawText("Speaking", 44f * density, centerY + 4f * density, textPaint)

                // Compact Model Tag
                drawCompactBadge(canvas, w - 38f * density, centerY, "3.8", colorPrimary)
            }

            is HudState.ExecutingTool -> {
                // Gear / Action Dot
                drawStatusDot(canvas, 14f * density, centerY, colorWarning)

                // Tool Name truncated
                textPaint.color = colorTextPrimary
                textPaint.textSize = 11f * density
                val cleanName = state.toolName.replace("_", " ").take(14)
                canvas.drawText(cleanName, 26f * density, centerY + 4f * density, textPaint)

                // Target badge (Android / Win)
                val badgeText = if (state.isWindowsAgent) "PC" else "APP"
                drawCompactBadge(canvas, w - 32f * density, centerY, badgeText, colorWarning)
            }

            is HudState.Reconnecting -> {
                drawStatusDot(canvas, 14f * density, centerY, colorWarning)
                textPaint.color = colorWarning
                textPaint.textSize = 11f * density
                canvas.drawText("Reconnecting...", 26f * density, centerY + 4f * density, textPaint)
            }

            is HudState.Error -> {
                drawStatusDot(canvas, 14f * density, centerY, colorError)
                textPaint.color = colorError
                textPaint.textSize = 11f * density
                canvas.drawText("Offline", 26f * density, centerY + 4f * density, textPaint)
            }
        }
    }

    // =========================================================================
    // EXPANDED CARD RENDERING
    // =========================================================================

    private fun drawExpandedCard(canvas: Canvas, w: Float, h: Float) {
        val padX = 18f * density

        // Header Row: MAX badge + State Name + Close Button
        drawStatusDot(canvas, padX + 2f * density, 24f * density, colorPrimary)
        textPaint.color = colorTextPrimary
        textPaint.textSize = 14f * density
        canvas.drawText("MAX AI", padX + 16f * density, 28f * density, textPaint)

        // Close '×' button
        textPaint.color = colorTextTertiary
        textPaint.textSize = 16f * density
        canvas.drawText("×", w - padX - 8f * density, 28f * density, textPaint)

        // Center Content depending on state
        val midY = 68f * density
        when (val state = currentState) {
            is HudState.Speaking -> {
                drawWaveformBars(canvas, padX + 4f * density, midY, 8, smoothedRms + 0.5f)
                textPaint.color = colorTextPrimary
                textPaint.textSize = 13f * density
                val caption = state.caption?.take(36) ?: "Speaking response..."
                canvas.drawText(caption, padX, midY + 28f * density, textPaint)
            }
            is HudState.Thinking -> {
                drawThinkingDots(canvas, padX + 4f * density, midY, state.mode)
                textPaint.color = colorTextPrimary
                textPaint.textSize = 13f * density
                val label = if (state.mode == GeminiModelMode.DEEP) "Extended Thinking active..." else "Analyzing request..."
                canvas.drawText(label, padX + 40f * density, midY + 4f * density, textPaint)

                textPaint.color = colorTextSecondary
                textPaint.textSize = 11f * density
                canvas.drawText("Gemini 3.8 Live reasoning engine", padX, midY + 26f * density, textPaint)
            }
            is HudState.ExecutingTool -> {
                textPaint.color = colorWarning
                textPaint.textSize = 12f * density
                val platform = if (state.isWindowsAgent) "Windows Agent Action" else "Android System Action"
                canvas.drawText(platform, padX, midY - 6f * density, textPaint)

                textPaint.color = colorTextPrimary
                textPaint.textSize = 13.5f * density
                val action = state.actionDescription ?: "Executing ${state.toolName.replace("_", " ")}"
                canvas.drawText(action.take(34), padX, midY + 14f * density, textPaint)

                // Progress Bar
                drawProgressBar(canvas, padX, midY + 24f * density, w - (padX * 2f), state.progress ?: (animTime))
            }
            is HudState.Listening -> {
                drawWaveformBars(canvas, padX + 4f * density, midY, 8, smoothedRms)
                textPaint.color = colorTextPrimary
                textPaint.textSize = 13f * density
                canvas.drawText("Listening to voice...", padX, midY + 28f * density, textPaint)
            }
            else -> {
                textPaint.color = colorTextSecondary
                textPaint.textSize = 12.5f * density
                canvas.drawText("Ready for voice commands or actions", padX, midY + 10f * density, textPaint)
            }
        }

        // Bottom Action Bar: [ Stop ] and [ Details ]
        val btnY = h - 22f * density
        val btnW = (w - (padX * 2f) - 12f * density) / 2f

        // Stop Button
        tempRect.set(padX, h - 38f * density, padX + btnW, h - 10f * density)
        badgePaint.color = Color.parseColor("#22FF453A")
        canvas.drawRoundRect(tempRect, 10f * density, 10f * density, badgePaint)
        textPaint.color = colorError
        textPaint.textSize = 11.5f * density
        canvas.drawText("Stop", tempRect.centerX() - 12f * density, tempRect.centerY() + 4f * density, textPaint)

        // Details Button
        tempRect.set(padX + btnW + 12f * density, h - 38f * density, w - padX, h - 10f * density)
        badgePaint.color = Color.parseColor("#2200A8FF")
        canvas.drawRoundRect(tempRect, 10f * density, 10f * density, badgePaint)
        textPaint.color = colorPrimary
        textPaint.textSize = 11.5f * density
        canvas.drawText("Details", tempRect.centerX() - 18f * density, tempRect.centerY() + 4f * density, textPaint)
    }

    // =========================================================================
    // QUICK CONTROLS RENDERING
    // =========================================================================

    private fun drawQuickControls(canvas: Canvas, w: Float, h: Float) {
        val colW = w / 4f
        val centerY = h / 2f

        val controls = listOf(
            Pair("MIC", "Mic"),
            Pair("STOP", "Stop"),
            Pair("MODE", "Mode"),
            Pair("APP", "App")
        )

        for (i in controls.indices) {
            val cx = (i * colW) + (colW / 2f)
            val item = controls[i]

            // Icon / Short indicator
            textPaint.textSize = 12f * density
            textPaint.color = colorTextPrimary
            val iconWidth = textPaint.measureText(item.first)
            canvas.drawText(item.first, cx - iconWidth / 2f, centerY - 2f * density, textPaint)

            // Label
            textPaint.textSize = 9.5f * density
            textPaint.color = colorTextSecondary
            val labelWidth = textPaint.measureText(item.second)
            canvas.drawText(item.second, cx - labelWidth / 2f, centerY + 16f * density, textPaint)
        }
    }

    // =========================================================================
    // DETAILS VIEW RENDERING
    // =========================================================================

    private fun drawDetailsView(canvas: Canvas, w: Float, h: Float) {
        val padX = 18f * density
        var curY = 28f * density

        textPaint.color = colorPrimary
        textPaint.textSize = 14f * density
        canvas.drawText("MAX Session Details", padX, curY, textPaint)

        curY += 26f * density
        textPaint.color = colorTextSecondary
        textPaint.textSize = 11.5f * density
        canvas.drawText("Model: Gemini 3.8 Live", padX, curY, textPaint)

        curY += 20f * density
        val state = currentState
        val modeStr = when (state) {
            is HudState.Thinking -> if (state.mode == GeminiModelMode.DEEP) "DEEP (Extended Thinking)" else "FAST (Conversational)"
            is HudState.Speaking -> if (state.mode == GeminiModelMode.DEEP) "DEEP (Extended Thinking)" else "FAST (Conversational)"
            else -> "Auto Router Active"
        }
        canvas.drawText("Operating Mode: $modeStr", padX, curY, textPaint)

        curY += 20f * density
        canvas.drawText("Latency: Sub-500ms Audio Pipeline", padX, curY, textPaint)

        curY += 20f * density
        canvas.drawText("Tap anywhere to collapse", padX, curY, textPaint)
    }

    // =========================================================================
    // HELPER SHAPES & ANIMATIONS
    // =========================================================================

    private fun drawStatusDot(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        glowPaint.color = color
        canvas.drawCircle(cx, cy, 3.8f * density, glowPaint)

        // Soft outer glow aura
        glowPaint.alpha = 60
        canvas.drawCircle(cx, cy, 6.5f * density, glowPaint)
        glowPaint.alpha = 255
    }

    private fun drawThinkingDots(canvas: Canvas, startX: Float, cy: Float, mode: GeminiModelMode) {
        val dotRadius = 3.2f * density
        val spacing = 9f * density
        val dotColor = if (mode == GeminiModelMode.DEEP) colorAccent else colorPrimary

        for (i in 0 until 3) {
            val cx = startX + (i * spacing)
            val wave = sin((animTime * 6.28f) + (i * 1.2f)).toFloat()
            val yOffset = wave * 3.5f * density
            glowPaint.color = dotColor
            canvas.drawCircle(cx, cy + yOffset, dotRadius, glowPaint)
        }
    }

    private fun drawWaveformBars(canvas: Canvas, startX: Float, cy: Float, count: Int, amplitude: Float) {
        val barW = 2.4f * density
        val spacing = 4.2f * density
        val baseH = 6f * density
        val ampMultiplier = (amplitude.coerceIn(0.1f, 1.5f) * 14f * density)

        for (i in 0 until count) {
            val bx = startX + (i * spacing)
            val barAmp = sin((animTime * 8f) + (i * 0.9f)).toFloat()
            val barH = max(baseH, (abs(barAmp) * ampMultiplier))
            val top = cy - (barH / 2f)
            val bot = cy + (barH / 2f)

            tempRect.set(bx, top, bx + barW, bot)
            wavePaint.color = if (i % 2 == 0) colorPrimary else colorAccent
            canvas.drawRoundRect(tempRect, 2f * density, 2f * density, wavePaint)
        }
    }

    private fun drawProgressBar(canvas: Canvas, x: Float, y: Float, w: Float, progress: Float) {
        tempRect.set(x, y, x + w, y + (3f * density))
        badgePaint.color = Color.parseColor("#22232D42")
        canvas.drawRoundRect(tempRect, 2f * density, 2f * density, badgePaint)

        val fillW = (w * progress.coerceIn(0.05f, 1.0f))
        tempRect.set(x, y, x + fillW, y + (3f * density))
        badgePaint.color = colorWarning
        canvas.drawRoundRect(tempRect, 2f * density, 2f * density, badgePaint)
    }

    private fun drawCompactBadge(canvas: Canvas, x: Float, cy: Float, label: String, color: Int) {
        val badgeW = 24f * density
        val badgeH = 14f * density
        tempRect.set(x, cy - (badgeH / 2f), x + badgeW, cy + (badgeH / 2f))

        badgePaint.color = (color and 0x00FFFFFF) or 0x33000000
        canvas.drawRoundRect(tempRect, 4f * density, 4f * density, badgePaint)

        textPaint.color = color
        textPaint.textSize = 8.5f * density
        canvas.drawText(label, x + 3.5f * density, cy + 3f * density, textPaint)
    }

    fun destroy() {
        dimensionAnimator?.cancel()
        continuousLoopAnimator?.cancel()
    }
}
