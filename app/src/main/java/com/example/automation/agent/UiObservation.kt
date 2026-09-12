package com.example.automation.agent

import com.example.service.PrunedNode

/**
 * Captures the current visual and accessibility state of the device screen.
 */
data class UiObservation(
    val screenshotJpeg: ByteArray?,
    val prunedElements: List<PrunedNode>,
    val foregroundPackage: String?,
    val visibleText: String,
    val clickableElements: List<String>,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UiObservation

        if (screenshotJpeg != null) {
            if (other.screenshotJpeg == null) return false
            if (!screenshotJpeg.contentEquals(other.screenshotJpeg)) return false
        } else if (other.screenshotJpeg != null) return false
        if (prunedElements != other.prunedElements) return false
        if (foregroundPackage != other.foregroundPackage) return false
        if (visibleText != other.visibleText) return false
        if (clickableElements != other.clickableElements) return false
        if (timestamp != other.timestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = screenshotJpeg?.contentHashCode() ?: 0
        result = 31 * result + prunedElements.hashCode()
        result = 31 * result + (foregroundPackage?.hashCode() ?: 0)
        result = 31 * result + visibleText.hashCode()
        result = 31 * result + clickableElements.hashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}
