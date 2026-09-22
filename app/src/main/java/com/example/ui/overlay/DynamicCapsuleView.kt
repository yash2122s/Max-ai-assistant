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

    private var currentWidthPx: Int = (90 * density).toInt()
    private var currentHeightPx: Int = (28 * density).toInt()
    private var onBoundsChangeListener: ((Int, Int) -> Unit)? = null

    // Palette for vibrant visualizer bars
    private val barColors = intArrayOf(
        Color.parseColor("#00F5D4"), // Neon Mint / Cyan
        Color.parseColor("#00BBF9"), // Electric Sky
        Color.parseColor("#4361EE"), // Royal Blue
        Color.parseColor("#7209B7"), // Neon Violet
        Color.parseColor("#F72585"), // Neon Magenta
        Color.parseColor("#FF5400")  // Neon Coral
    )

    init {
        // High-performance Glassmorphic Container with sleek micro-border
        backgroundDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20 * density
            setColor(Color.parseColor("#F00A0E17")) // Deep obsidian glass
            setStroke((1.2f * density).toInt(), Color.parseColor("#00F5D4")) // Neon Cyan glow
        }

        containerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val padH = (11 * density).toInt()
            val padV = (3.5f * density).toInt()
            setPadding(padH, padV, padH, padV)
            background = backgroundDrawable
            elevation = 16 * density
        }

        // Cute glowing icon indicator
        iconView = TextView(context).apply {
            text = "✦"
            textSize = 11.5f
            setTextColor(Color.parseColor("#00F5D4"))
            gravity = Gravity.CENTER
            setPadding(0, 0, (5 * density).toInt(), 0)
        }

        // Label / Text content with compact modern typography
        titleView = TextView(context).apply {
            text = "MAX"
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        // Real-time Visualizer bars container (6 luminous multicolor bars)
        visualizerContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding((5 * density).toInt(), 0, 0, 0)
        }

        for (i in 0 until 6) {
            val barColor = barColors[i % barColors.size]
            val bar = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (2.5f * density).toInt(),
                    (6 * density).toInt()
                ).apply {
                    setMargins((1.5f * density).toInt(), 0, (1.5f * density).toInt(), 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 4 * density
                    setColor(barColor)
                }
            }
            visualizerBars.add(bar)
            visualizerContainer.addView(bar)
        }

        // 3 Animated Glowing Processing Dots
        processingDotsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding((6 * density).toInt(), 0, 0, 0)
        }

        val dotColors = intArrayOf(
            Color.parseColor("#00F5D4"),
            Color.parseColor("#7209B7"),
            Color.parseColor("#F72585")
        )

        for (i in 0 until 3) {
            val dot = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (4 * density).toInt(),
                    (4 * density).toInt()
                ).apply {
                    setMargins((2 * density).toInt(), 0, (2 * density).toInt(), 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(dotColors[i % dotColors.size])
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
                iconView.text = "✦"
                iconView.setTextColor(Color.parseColor("#00F5D4"))
                titleView.text = "MAX"
                titleView.setTextColor(Color.WHITE)
                visualizerContainer.visibility = View.GONE
                processingDotsContainer.visibility = View.GONE
                backgroundDrawable.setStroke((1.2f * density).toInt(), Color.parseColor("#00F5D4"))
                startBreathingAnimation()
                animateToDimensions(widthDp = 90, heightDp = 28, animate = animate)
            }
            CapsuleState.LISTENING -> {
                visibility = View.VISIBLE
                iconView.text = "🎙"
                iconView.setTextColor(Color.parseColor("#F72585")) // Vivid Neon Magenta/Rose
                titleView.text = "Listening"
                titleView.setTextColor(Color.parseColor("#FFD1DC"))
                visualizerContainer.visibility = View.VISIBLE
                processingDotsContainer.visibility = View.GONE
                backgroundDrawable.setStroke((1.5f * density).toInt(), Color.parseColor("#F72585"))
                visualizerBars.forEachIndexed { index, bar ->
                    (bar.background as? GradientDrawable)?.setColor(barColors[index % barColors.size])
                }
                animateToDimensions(widthDp = 138, heightDp = 30, animate = animate)
            }
            CapsuleState.PROCESSING -> {
                visibility = View.VISIBLE
                iconView.text = "✦"
                iconView.setTextColor(Color.parseColor("#7209B7")) // Neon Purple
                titleView.text = "Thinking"
                titleView.setTextColor(Color.parseColor("#E0AAFF"))
                visualizerContainer.visibility = View.GONE
                processingDotsContainer.visibility = View.VISIBLE
                backgroundDrawable.setStroke((1.5f * density).toInt(), Color.parseColor("#7209B7"))
                startProcessingAnimation()
                animateToDimensions(widthDp = 132, heightDp = 30, animate = animate)
            }
            CapsuleState.SPEAKING -> {
                visibility = View.VISIBLE
                iconView.text = "⚡"
                iconView.setTextColor(Color.parseColor("#00BBF9")) // Electric Sky Blue
                titleView.text = "MAX"
                titleView.setTextColor(Color.WHITE)
                visualizerContainer.visibility = View.VISIBLE
                processingDotsContainer.visibility = View.GONE
                backgroundDrawable.setStroke((1.5f * density).toInt(), Color.parseColor("#00BBF9"))
                visualizerBars.forEachIndexed { index, bar ->
                    (bar.background as? GradientDrawable)?.setColor(barColors[index % barColors.size])
                }
                startSpeakingEnergyAnimation()
                animateToDimensions(widthDp = 142, heightDp = 30, animate = animate)
            }
            CapsuleState.EXPANDED -> {
                visibility = View.VISIBLE
                iconView.text = "💬"
                iconView.setTextColor(Color.parseColor("#00F5D4"))
                titleView.text = textPayload?.takeIf { it.isNotBlank() } ?: "Processing..."
                titleView.setTextColor(Color.WHITE)
                visualizerContainer.visibility = View.GONE
                processingDotsContainer.visibility = View.GONE
                backgroundDrawable.setStroke((1.4f * density).toInt(), Color.parseColor("#00F5D4"))
                animateToDimensions(widthDp = 210, heightDp = 38, animate = animate)
            }
        }
    }

    fun updateRms(rawRms: Float) {
        val clamped = rawRms.coerceIn(0.0f, 1.0f)
        // High-order exponential moving average (EMA) for ultra-smooth fluid response
        smoothedRms = (smoothedRms * 0.82f) + (clamped * 0.18f)

        if (currentState == CapsuleState.LISTENING || currentState == CapsuleState.SPEAKING) {
            val minHeight = (4 * density).toInt()
            val maxHeight = (16 * density).toInt()

            // Harmonic wave multipliers for aesthetic organic bounce
            val multipliers = when (currentState) {
                CapsuleState.SPEAKING -> floatArrayOf(0.45f, 0.80f, 1.00f, 0.85f, 0.90f, 0.55f)
                else -> floatArrayOf(0.50f, 0.90f, 0.85f, 0.70f, 0.55f, 0.35f)
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
                val scaleFactor = 1.0f + (smoothedRms * 0.025f).coerceIn(0.0f, 0.035f)
                containerLayout.scaleX = scaleFactor
                containerLayout.scaleY = scaleFactor

                val strokeWidth = ((1.2f + (smoothedRms * 1.4f)) * density).toInt()
                backgroundDrawable.setStroke(strokeWidth, Color.parseColor("#00BBF9"))
            } else if (currentState == CapsuleState.LISTENING) {
                val strokeWidth = ((1.2f + (smoothedRms * 1.4f)) * density).toInt()
                backgroundDrawable.setStroke(strokeWidth, Color.parseColor("#F72585"))
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

        // Visually satisfying Spring-like cubic-bezier ease: Fast responsive start with gentle settle
        dimensionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f)
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
        breathingAnimator = ValueAnimator.ofFloat(0.92f, 1.08f).apply {
            duration = 2000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.PathInterpolator(0.42f, 0f, 0.58f, 1f) // Smooth sine-like ease
            addUpdateListener { anim ->
                if (currentState == CapsuleState.IDLE) {
                    val scaleVal = anim.animatedValue as Float
                    iconView.scaleX = scaleVal
                    iconView.scaleY = scaleVal
                    iconView.alpha = 0.6f + (scaleVal - 0.92f) * 2.5f
                }
            }
            start()
        }
    }

    private fun startProcessingAnimation() {
        processingAnimator?.cancel()
        processingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { anim ->
                if (currentState == CapsuleState.PROCESSING) {
                    val progress = anim.animatedFraction
                    processingDots.forEachIndexed { index, dot ->
                        val phase = (progress + (index * 0.33f)) % 1.0f
                        // Pure harmonic sinusoidal scaling
                        val sinVal = Math.sin(phase * 2 * Math.PI).toFloat()
                        val scale = 0.7f + (sinVal * 0.35f)
                        dot.scaleX = scale
                        dot.scaleY = scale
                        dot.alpha = 0.4f + ((sinVal + 1f) * 0.3f)
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
            duration = 1200
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.PathInterpolator(0.42f, 0f, 0.58f, 1f)
            addUpdateListener { anim ->
                if (currentState == CapsuleState.SPEAKING) {
                    val wave = anim.animatedValue as Float
                    // Very subtle micro-phase translation (max +-0.8dp)
                    containerLayout.translationX = wave * (smoothedRms * 0.8f * density)
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
