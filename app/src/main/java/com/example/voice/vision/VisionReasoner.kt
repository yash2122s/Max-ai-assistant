package com.example.voice.vision

import com.example.automation.state.WorldState
import com.example.service.PrunedNode

data class VisionContext(
    val screenshotJpeg: ByteArray?,
    val prunedTree: List<PrunedNode>,
    val worldState: WorldState,
    val userGoal: String,
    val foregroundPackageName: String = worldState.currentApp,
    val screenSignature: String = worldState.currentScreenSignature
)

data class DetectedUiElement(
    val label: String,
    val resourceId: String? = null,
    val bounds: String? = null,
    val isClickable: Boolean = true
)

data class VisionResult(
    val description: String,
    val confidence: Float = 1.0f,
    val detectedElements: List<DetectedUiElement> = emptyList(),
    val suggestedActionName: String? = null,
    val targetText: String? = null,
    val targetResourceId: String? = null,
    val fallbackXPercent: Float? = null,
    val fallbackYPercent: Float? = null,
    val requiresUserConfirmation: Boolean = false,
    val isCachedResult: Boolean = false
)

interface VisionReasoner {
    suspend fun analyzeScreen(context: VisionContext): VisionResult
}
