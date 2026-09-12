package com.example.automation.verification

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.models.UiTarget
import com.example.automation.resolver.UiTargetResolver
import com.example.service.JarvisAccessibilityService

/**
 * Lightweight screen observation snapshot containing only essential UI state
 * without full hierarchy dump overhead.
 */
data class LightweightUiObservation(
    val foregroundPackage: String,
    val screenSignature: String,
    val focusedNodeText: String? = null,
    val focusedNodeId: String? = null,
    val isEditableFocused: Boolean = false,
    val isKeyboardVisible: Boolean = false,
    val targetResolved: UiTarget? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Lightweight UI Observer for MAX.
 * Captures targeted runtime UI state for post-action verification with minimum CPU/memory footprint.
 */
object LightweightUiObserver {
    private const val TAG = "LightweightUiObserver"

    fun observe(
        context: Context,
        targetQuery: String? = null,
        requireEditable: Boolean = false,
        rootNode: AccessibilityNodeInfo? = null
    ): LightweightUiObservation {
        val service = JarvisAccessibilityService.instance
        val effectiveRoot = rootNode ?: service?.rootInActiveWindow

        val pkgName = effectiveRoot?.packageName?.toString()
            ?: com.example.automation.state.WorldStateManager.getCurrentState().currentApp
        val sig = if (effectiveRoot != null) {
            service?.getScreenSignature(effectiveRoot) ?: "sig_${pkgName}"
        } else {
            "null_root"
        }

        val focusedNode = try {
            effectiveRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: Exception) {
            null
        }
        val focusedText = focusedNode?.text?.toString()
        val focusedId = focusedNode?.viewIdResourceName
        val isEditable = focusedNode?.isEditable == true

        var resolvedTarget: UiTarget? = null
        if (!targetQuery.isNullOrBlank() && effectiveRoot != null) {
            val result = UiTargetResolver.resolve(
                query = targetQuery,
                requireEditable = requireEditable,
                rootNode = effectiveRoot
            )
            if (result.success && result.confidence >= UiTargetResolver.SAFE_CONFIDENCE_THRESHOLD) {
                resolvedTarget = result.target
            }
        }

        val worldState = com.example.automation.state.WorldStateManager.getCurrentState()

        return LightweightUiObservation(
            foregroundPackage = pkgName,
            screenSignature = sig,
            focusedNodeText = focusedText,
            focusedNodeId = focusedId,
            isEditableFocused = isEditable,
            isKeyboardVisible = worldState.isKeyboardVisible,
            targetResolved = resolvedTarget
        )
    }
}
