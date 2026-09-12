package com.example.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

enum class CapsuleState {
    HIDDEN,
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING,
    EXPANDED
}

class DynamicCapsuleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var currentState: CapsuleState = CapsuleState.IDLE
        private set

    private var smoothedRms: Float = 0f
    private val density = resources.displayMetrics.density

    private val containerLayout: LinearLayout
    private val iconView: TextView
    private val titleView: TextView
    private val visualizerBars = ArrayList<View>()
    private val visualizerContainer: LinearLayout

    // Processing animated dot views
    private val processingDotsContainer: LinearLayout
    private val processingDots = ArrayList<View>()

    private val backgroundDrawable: GradientDrawable
    private var breathingAnimator: ValueAnimator? = null
    private var processingAnimator: ValueAnimator? = null
    private var speakingEnergyAnimator: ValueAnimator? = null
    private var dimensionAnimator: ValueAnimator? = null

    private var currentWidthPx: Int = (125 * density).toInt()
    private var currentHeightPx: Int = (38 * density).toInt()
    private var onBoundsChangeListener: ((Int, Int) -> Unit)? = null

    init {
        // High-performance Glassmorphic Container
        backgroundDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24 * density
            setColor(Color.parseColor("#E60B0F14")) // MAX Deep Bg Obsidian
            setStroke((1.5f * density).toInt(), Color.parseColor("#00A8FF")) // MAX Primary Electric Blue
        }

        containerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val padH = (16 * density).toInt()
            val padV = (6 * density).toInt()
            setPadding(padH, padV, padH, padV)
            background = backgroundDrawable
            elevation = 14 * density
        }

        // Icon indicator (Orb / Mic / Lightning)
        iconView = TextView(context).apply {
            text = "●"
            textSize = 13f
            setTextColor(Color.parseColor("#00A8FF"))
            gravity = Gravity.CENTER
            setPadding(0, 0, (8 * density).toInt(), 0)
        }

        // Label / Text content with modern typography
        titleView = TextView(context).apply {
            text = "MAX"
            textSize = 12.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        // Real-time Visualizer bars container (6 waveform bars)
        visualizerContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding((8 * density).toInt(), 0, 0, 0)
        }

        for (i in 0 until 6) {
            val bar = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (3 * density).toInt(),
                    (8 * density).toInt()
                ).apply {
                    setMargins((2 * density).toInt(), 0, (2 * density).toInt(), 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 6 * density
                    setColor(Color.parseColor("#00A8FF"))
                }
            }
            visualizerBars.add(bar)
            visualizerContainer.addView(bar)
        }

        // 3 Animated Processing Dots
        processingDotsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding((8 * density).toInt(), 0, 0, 0)
        }

        for (i in 0 until 3) {
            val dot = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (5 * density).toInt(),
                    (5 * density).toInt()
                ).apply {
                    setMargins((2.5f * density).toInt(), 0, (2.5f * density).toInt(), 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#7C4DFF")) // MAX Accent Vivid Purple
                }
            }
            processingDots.add(dot)
            processingDotsContainer.addView(dot)
        }

        containerLayout.addView(iconView)
        containerLayout.addView(titleView)
        containerLayout.addView(visualizerContainer)
        containerLayout.addView(processingDotsContainer)

        addView(containerLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))

        setupTouchHandling()
        startBreathingAnimation()
        applyState(CapsuleState.IDLE, animate = false)
    }

    fun setOnBoundsChangeListener(listener: (widthPx: Int, heightPx: Int) -> Unit) {
        this.onBoundsChangeListener = listener
    }

    private fun setupTouchHandling() {
        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    if (launchIntent != null) {
                        context.startActivity(launchIntent)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    true
                }
                else -> false
            }
        }
    }

    fun updateState(newState: CapsuleState, textPayload: String? = null) {
        if (currentState == newState && textPayload == null) return
        currentState = newState
        applyState(newState, textPayload)
    }

    private fun applyState(state: CapsuleState, textPayload: String? = null, animate: Boolean = true) {
        stopProcessingAnimation()
        stopSpeakingEnergyAnimation()

        // Reset subtle scale & translations
        containerLayout.scaleX = 1.0f
        containerLayout.scaleY = 1.0f
        containerLayout.translationX = 0f

        when (state) {
            CapsuleState.HIDDEN -> {
                visibility = View.GONE
                return
            }
            CapsuleState.IDLE -> {
                visibility = View.VISIBLE
                iconView.text = "●"
                iconView.setTextColor(Color.parseColor("#00A8FF"))
                titleView.text = "MAX"
                titleView.setTextColor(Color.WHITE)
                visualizerContainer.visibility = View.GONE
                processingDotsContainer.visibility = View.GONE
                backgroundDrawable.setStroke((1.5f * density).toInt(), Color.parseColor("#00A8FF"))
                startBreathingAnimation()
                animateToDimensions(widthDp = 125, heightDp = 38, animate = animate)
            }
            CapsuleState.LISTENING -> {
                visibility = View.VISIBLE
                iconView.text = "🎙"
                iconView.setTextColor(Color.parseColor("#FF4D4D")) // MAX Error/Listen Red
                titleView.text = "Listening"
                titleView.setTextColor(Color.parseColor("#FFB3B3"))
                visualizerContainer.visibility = View.VISIBLE
                processingDotsContainer.visibility = View.GONE
                backgroundDrawable.setStroke((2.0f * density).toInt(), Color.parseColor("#FF4D4D"))
                visualizerBars.forEach { bar ->
                    (bar.background as? GradientDrawable)?.setColor(Color.parseColor("#FF4D4D"))
                }
                animateToDimensions(widthDp = 170, heightDp = 44, animate = animate)
            }
            CapsuleState.PROCESSING -> {
                visibility = View.VISIBLE
                iconView.text = "✦"
                iconView.setTextColor(Color.parseColor("#7C4DFF")) // MAX Accent
                titleView.text = "Thinking"
                titleView.setTextColor(Color.parseColor("#D9CCFF"))
                visualizerContainer.visibility = View.GONE
                processingDotsContainer.visibility = View.VISIBLE
                backgroundDrawable.setStroke((1.8f * density).toInt(), Color.parseColor("#7C4DFF"))
                startProcessingAnimation()
                animateToDimensions(widthDp = 165, heightDp = 44, animate = animate)
            }
            CapsuleState.SPEAKING -> {
                visibility = View.VISIBLE
                iconView.text = "⚡"
                iconView.setTextColor(Color.parseColor("#00A8FF"))
                titleView.text = "MAX"
                titleView.setTextColor(Color.WHITE)
                visualizerContainer.visibility = View.VISIBLE
                processingDotsContainer.visibility = View.GONE
                backgroundDrawable.setStroke((2.0f * density).toInt(), Color.parseColor("#00A8FF"))
                visualizerBars.forEach { bar ->
                    (bar.background as? GradientDrawable)?.setColor(Color.parseColor("#00A8FF"))
                }
                startSpeakingEnergyAnimation()
                animateToDimensions(widthDp = 180, heightDp = 46, animate = animate)
            }
            CapsuleState.EXPANDED -> {
                visibility = View.VISIBLE
                iconView.text = "💬"
                iconView.setTextColor(Color.parseColor("#00A8FF"))
                titleView.text = textPayload?.takeIf { it.isNotBlank() } ?: "Processing..."
                titleView.setTextColor(Color.WHITE)
                visualizerContainer.visibility = View.GONE
                processingDotsContainer.visibility = View.GONE
                backgroundDrawable.setStroke((1.5f * density).toInt(), Color.parseColor("#00A8FF"))
                animateToDimensions(widthDp = 280, heightDp = 52, animate = animate)
            }
        }
    }

    fun updateRms(rawRms: Float) {
        val clamped = rawRms.coerceIn(0.0f, 1.0f)
        // Exponential smoothing: smoothed = prev * 0.7 + current * 0.3
        smoothedRms = (smoothedRms * 0.7f) + (clamped * 0.3f)

        if (currentState == CapsuleState.LISTENING || currentState == CapsuleState.SPEAKING) {
            val minHeight = (5 * density).toInt()
            val maxHeight = (22 * density).toInt()

            // Amplitude multipliers for each bar
            val multipliers = when (currentState) {
                CapsuleState.SPEAKING -> floatArrayOf(0.55f, 0.85f, 1.00f, 0.75f, 0.95f, 0.65f)
                else -> floatArrayOf(0.60f, 1.00f, 0.80f, 0.65f, 0.50f, 0.40f)
            }

            visualizerBars.forEachIndexed { index, bar ->
                val factor = multipliers.getOrElse(index) { 0.7f }
                val targetHeight = (minHeight + (maxHeight - minHeight) * smoothedRms * factor).toInt().coerceIn(minHeight, maxHeight)
                val lp = bar.layoutParams
                if (lp.height != targetHeight) {
                    lp.height = targetHeight
                    bar.layoutParams = lp
                }
            }

            // Speaking Subtle Energy Pulse (Scale & Border Glow)
            if (currentState == CapsuleState.SPEAKING) {
                val scaleFactor = 1.0f + (smoothedRms * 0.035f).coerceIn(0.0f, 0.04f)
                containerLayout.scaleX = scaleFactor
                containerLayout.scaleY = scaleFactor

                val strokeWidth = ((1.5f + (smoothedRms * 2.0f)) * density).toInt()
                backgroundDrawable.setStroke(strokeWidth, Color.parseColor("#00A8FF"))
            } else if (currentState == CapsuleState.LISTENING) {
                val strokeWidth = ((1.5f + (smoothedRms * 1.5f)) * density).toInt()
                backgroundDrawable.setStroke(strokeWidth, Color.parseColor("#FF4D4D"))
            }
        }
    }

    private fun animateToDimensions(widthDp: Int, heightDp: Int, animate: Boolean) {
        val targetWidthPx = (widthDp * density).toInt()
        val targetHeightPx = (heightDp * density).toInt()

        if (!animate) {
            currentWidthPx = targetWidthPx
            currentHeightPx = targetHeightPx
            onBoundsChangeListener?.invoke(targetWidthPx, targetHeightPx)
            return
        }

        dimensionAnimator?.cancel()
        val startW = currentWidthPx
        val startH = currentHeightPx

        dimensionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                val animW = (startW + (targetWidthPx - startW) * fraction).toInt()
                val animH = (startH + (targetHeightPx - startH) * fraction).toInt()
                currentWidthPx = animW
                currentHeightPx = animH
                onBoundsChangeListener?.invoke(animW, animH)
            }
            start()
        }
    }

    private fun startBreathingAnimation() {
        breathingAnimator?.cancel()
        breathingAnimator = ValueAnimator.ofFloat(0.95f, 1.05f).apply {
            duration = 1400
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                if (currentState == CapsuleState.IDLE) {
                    val scaleVal = anim.animatedValue as Float
                    iconView.scaleX = scaleVal
                    iconView.scaleY = scaleVal
                    iconView.alpha = 0.5f + (scaleVal - 0.95f) * 5.0f
                }
            }
            start()
        }
    }

    private fun startProcessingAnimation() {
        processingAnimator?.cancel()
        processingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                if (currentState == CapsuleState.PROCESSING) {
                    val progress = anim.animatedFraction
                    processingDots.forEachIndexed { index, dot ->
                        val phase = (progress + (index * 0.33f)) % 1.0f
                        val scale = 0.6f + (Math.sin(phase * Math.PI).toFloat() * 0.7f).coerceIn(0.0f, 0.7f)
                        dot.scaleX = scale
                        dot.scaleY = scale
                        dot.alpha = 0.3f + (scale * 0.7f)
                    }
                }
            }
            start()
        }
    }

    private fun stopProcessingAnimation() {
        processingAnimator?.cancel()
        processingAnimator = null
    }

    private fun startSpeakingEnergyAnimation() {
        speakingEnergyAnimator?.cancel()
        speakingEnergyAnimator = ValueAnimator.ofFloat(-1f, 1f).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                if (currentState == CapsuleState.SPEAKING) {
                    val wave = anim.animatedValue as Float
                    // Very subtle micro-phase translation (max +-1.2dp)
                    containerLayout.translationX = wave * (smoothedRms * 1.2f * density)
                }
            }
            start()
        }
    }

    private fun stopSpeakingEnergyAnimation() {
        speakingEnergyAnimator?.cancel()
        speakingEnergyAnimator = null
        containerLayout.translationX = 0f
    }

    fun destroy() {
        breathingAnimator?.cancel()
        processingAnimator?.cancel()
        speakingEnergyAnimator?.cancel()
        dimensionAnimator?.cancel()
        breathingAnimator = null
        processingAnimator = null
        speakingEnergyAnimator = null
        dimensionAnimator = null
    }
}
