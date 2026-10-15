package com.example.automation.models

import android.graphics.PointF
import android.graphics.Rect
import java.util.Locale

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
    val method: TargetResolutionMethod = TargetResolutionMethod.TEXT,

    // JARVIS migration — additional semantic fields
    val className: String = "",
    val viewId: String = resourceId ?: "",
    val isClickable: Boolean = false,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false
) {
    val center: PointF
        get() = bounds?.let { PointF(it.exactCenterX(), it.exactCenterY()) } ?: PointF(0f, 0f)

    /** JARVIS's primaryLabel fallback chain */
    val primaryLabel: String
        get() = when {
            !text.isNullOrBlank() -> text
            !contentDescription.isNullOrBlank() -> contentDescription
            viewId.isNotBlank() -> viewId.substringAfterLast("/")
            else -> className.substringAfterLast(".")
        }

    val hasTextOrDesc: Boolean
        get() = !text.isNullOrBlank() || !contentDescription.isNullOrBlank()

    /** JARVIS's toggle detection */
    fun isSwitchOrToggle(): Boolean {
        return isCheckable ||
                className.contains("Switch", ignoreCase = true) ||
                className.contains("CheckBox", ignoreCase = true) ||
                className.contains("ToggleButton", ignoreCase = true)
    }

    /** JARVIS's image/thumbnail detection */
    val isImageOrThumbnail: Boolean
        get() {
            val b = bounds ?: return false
            if (b.width() < 60 || b.height() < 60) return false
            val simpleClass = className.substringAfterLast(".").lowercase(Locale.ROOT)
            val simpleVid = viewId.substringAfterLast("/").lowercase(Locale.ROOT)
            val lbl = primaryLabel.lowercase(Locale.ROOT)

            val isImgClass = simpleClass.contains("image") || simpleClass.contains("photo") ||
                    simpleClass.contains("thumb") || simpleClass.contains("picture")
            val isImgVid = simpleVid.contains("thumb") || simpleVid.contains("image") ||
                    simpleVid.contains("photo") || simpleVid.contains("pic")
            val isImgLabel = lbl.endsWith(".jpg") || lbl.endsWith(".jpeg") ||
                    lbl.endsWith(".png") || lbl.endsWith(".webp") ||
                    lbl.startsWith("img_") || lbl.startsWith("pxl_")

            return isImgClass || isImgVid || isImgLabel
        }
}
