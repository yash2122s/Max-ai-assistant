package com.example.ui.overlay

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.example.voice.session.ComposeViewLifecycleOwner
import com.example.voice.session.setupComposeOwners

/**
 * Visual cursor indicator overlay hosting the macOS-style AgentCursor composable.
 * Animates smoothly so that the sharp arrow TIP points directly at the grounded target bounds.
 * Purely observational visual layer; strictly NOT clickable and NOT focusable.
 */
class ActionCursorOverlayView(context: Context) : FrameLayout(context) {

    private val density = context.resources.displayMetrics.density
    private val cursorSizeDp = 26.dp
    private val cursorSizePx = 26f * density

    // Arrow tip offset in local Box coordinates: (w * 0.15f, h * 0.08f) where w = cursorSizePx, h = cursorSizePx * 1.25f
    private val tipOffsetX = cursorSizePx * 0.15f
    private val tipOffsetY = cursorSizePx * 1.25f * 0.08f

    var targetPositionState by mutableStateOf(Offset(-100f, -100f))
        private set
    var currentCursorState by mutableStateOf(AgentCursorState.IDLE)
        private set
    var isVisibleState by mutableStateOf(false)
        private set

    val cursorState: AgentCursorState
        get() = currentCursorState

    companion object {
        @Volatile
        var isTesting = false
    }

    private val lifecycleOwner = ComposeViewLifecycleOwner()
    private var composeView: ComposeView? = null

    init {
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false

        if (!isTesting) {
            this.setupComposeOwners(lifecycleOwner)
            lifecycleOwner.onCreate()
            lifecycleOwner.onStart()
            lifecycleOwner.onResume()

            val view = ComposeView(context).apply {
                isClickable = false
                isFocusable = false
                setupComposeOwners(lifecycleOwner)

                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
                setContent {
                    AgentCursor(
                        targetPosition = targetPositionState,
                        state = currentCursorState,
                        visible = isVisibleState,
                        cursorSize = cursorSizeDp,
                        strokeWidth = 2.5.dp
                    )
                }
            }
            composeView = view
            addView(
                view,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
        }
    }

    override fun onAttachedToWindow() {
        if (!isTesting) {
            this.setupComposeOwners(lifecycleOwner)
            composeView?.setupComposeOwners(lifecycleOwner)
            lifecycleOwner.onStart()
            lifecycleOwner.onResume()
        }
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (!isTesting) {
            lifecycleOwner.onPause()
            lifecycleOwner.onStop()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean = false
    override fun onTouchEvent(event: MotionEvent?): Boolean = false
    override fun onGenericMotionEvent(event: MotionEvent?): Boolean = false
    override fun onHoverEvent(event: MotionEvent?): Boolean = false

    /**
     * Smoothly animates the AgentCursor so the tip points directly at the target center.
     */
    fun animateToTarget(bounds: Rect) {
        val targetCenterX = bounds.exactCenterX()
        val targetCenterY = bounds.exactCenterY()

        // Place the Box at (targetCenterX - tipOffsetX, targetCenterY - tipOffsetY)
        // so that (animatedX + tipOffsetX, animatedY + tipOffsetY) == (targetCenterX, targetCenterY)
        targetPositionState = Offset(targetCenterX - tipOffsetX, targetCenterY - tipOffsetY)
        isVisibleState = true
        if (currentCursorState == AgentCursorState.IDLE) {
            currentCursorState = AgentCursorState.IDLE
        }
    }

    /**
     * Sets the high-level agent cursor state (IDLE, THINKING, CLICKING, DRAGGING).
     */
    fun setCursorState(state: AgentCursorState) {
        currentCursorState = state
        if (state != AgentCursorState.IDLE) {
            isVisibleState = true
        }
    }

    /**
     * Shows thinking state with dynamic glow aura.
     */
    fun showThinking() {
        currentCursorState = AgentCursorState.THINKING
        isVisibleState = true
    }

    /**
     * Subtle tip click / shockwave ripple animation when an action or typing begins.
     */
    fun pulseAction() {
        currentCursorState = AgentCursorState.CLICKING
        isVisibleState = true
    }

    /**
     * Shows dragging state when swiping/scrolling.
     */
    fun showDragging() {
        currentCursorState = AgentCursorState.DRAGGING
        isVisibleState = true
    }

    fun hideImmediately() {
        currentCursorState = AgentCursorState.IDLE
        isVisibleState = false
    }

    fun destroy() {
        hideImmediately()
        lifecycleOwner.onPause()
        lifecycleOwner.onStop()
        lifecycleOwner.onDestroy()
    }
}
