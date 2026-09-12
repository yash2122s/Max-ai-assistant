package com.example.automation.models

import android.graphics.Rect

/**
 * Method used to locate or identify a UI target on screen.
 */
enum class TargetResolutionMethod {
    RESOURCE_ID,
    TEXT,
    CONTENT_DESCRIPTION,
    SEMANTIC,
    EDITABLE,
    OCR,
    VISUAL,
    COORDINATE
}

/**
 * Resolved UI target with grounding metadata and confidence.
 */
data class UiTarget(
    val resourceId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val role: String? = null,
    val bounds: Rect? = null,
    val confidence: Float = 1.0f,
    val method: TargetResolutionMethod = TargetResolutionMethod.TEXT
)
