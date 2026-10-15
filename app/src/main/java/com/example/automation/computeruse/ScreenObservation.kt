package com.example.automation.computeruse

import android.graphics.Rect

/**
 * Interactive element captured from the active accessibility hierarchy.
 */
data class InteractiveElement(
    val id: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val bounds: Rect = Rect(),
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val isFocused: Boolean = false,
    val isEnabled: Boolean = true,
    val isSelected: Boolean = false
) {
    val displayLabel: String
        get() = when {
            !text.isNullOrBlank() -> text
            !contentDescription.isNullOrBlank() -> contentDescription
            !id.isNullOrBlank() -> id.substringAfter(":id/")
            else -> className?.substringAfterLast(".") ?: "element"
        }
}

/**
 * Normalized snapshot of the current screen state combining visual and accessibility observations.
 */
data class ScreenObservation(
    val observationId: String = "obs_${java.util.UUID.randomUUID()}",
    /** Correlation metadata is optional for legacy callers, but populated by computer-use execution. */
    val taskId: String? = null,
    val stepId: String? = null,
    val executionId: String? = null,
    val causedByAction: String? = null,
    val expectedPostcondition: String? = null,
    val foregroundPackage: String = "",
    val currentActivity: String? = null,
    val interactiveElements: List<InteractiveElement> = emptyList(),
    val editableElements: List<InteractiveElement> = emptyList(),
    val scrollableElements: List<InteractiveElement> = emptyList(),
    val visibleTexts: List<String> = emptyList(),
    val focusedElement: InteractiveElement? = null,
    val screenshotPath: String? = null,
    val screenshotFingerprint: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    @Transient val screenshotJpeg: ByteArray? = null
) {
    val hasScreenshot: Boolean get() = screenshotJpeg?.isNotEmpty() == true

    fun isFresh(maxAgeMs: Long = 3000L): Boolean =
        foregroundPackage.isNotBlank() && System.currentTimeMillis() - timestamp in 0..maxAgeMs

    val screenId: String get() = java.security.MessageDigest.getInstance("SHA-256")
        .digest(computeScreenSignature().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    val isAppForeground: Boolean
        get() = foregroundPackage.isNotBlank() &&
                !foregroundPackage.contains("launcher", ignoreCase = true) &&
                !foregroundPackage.contains("systemui", ignoreCase = true)

    fun computeScreenSignature(): String {
        val textsHash = visibleTexts.joinToString("|")
        val idsHash = interactiveElements.joinToString("|") {
            "${it.id}:${it.text}:${it.contentDescription}:${it.className}@${it.bounds.left},${it.bounds.top},${it.bounds.right},${it.bounds.bottom}:" +
                "${it.isEnabled}:${it.isSelected}:${it.isFocused}:${it.isClickable}:${it.isEditable}:${it.isScrollable}"
        }
        val focusHash = "${focusedElement?.id}:${focusedElement?.text}"
        return "$foregroundPackage:$currentActivity:count=${interactiveElements.size}:$textsHash:$idsHash:$focusHash"
    }

    /**
     * True screen change detection comparing package, activity, visible texts,
     * element count, focused element, layout bounds, and structural signature.
     */
    fun hasScreenChanged(other: ScreenObservation?): Boolean {
        if (other == null) return true
        if (this.foregroundPackage != other.foregroundPackage) return true
        if (this.currentActivity != other.currentActivity) return true
        if (this.interactiveElements.size != other.interactiveElements.size) return true
        if (this.visibleTexts != other.visibleTexts) return true
        if (this.focusedElement?.id != other.focusedElement?.id || this.focusedElement?.text != other.focusedElement?.text) return true
        if (this.computeScreenSignature() != other.computeScreenSignature()) return true
        if (this.screenshotFingerprint != null && other.screenshotFingerprint != null && this.screenshotFingerprint != other.screenshotFingerprint) return true
        return false
    }

    /**
     * Compact observation serializer for token efficiency.
     */
    fun toCompactPrompt(): String {
        val sb = StringBuilder()
        sb.appendLine("OBSERVATION: $observationId SCREEN: $screenId TIMESTAMP: $timestamp")
        if (taskId != null || stepId != null || executionId != null) {
            sb.appendLine("CORRELATION: task=$taskId step=$stepId execution=$executionId action=$causedByAction expected=$expectedPostcondition")
        }
        sb.appendLine("CURRENT APP: $foregroundPackage")
        if (!currentActivity.isNullOrBlank()) {
            sb.appendLine("CURRENT ACTIVITY: $currentActivity")
        }
        sb.appendLine("VISIBLE TEXT: ${visibleTexts.take(30).joinToString(", ")}")
        sb.appendLine("INTERACTIVE ELEMENTS:")
        interactiveElements.take(40).forEachIndexed { index, elem ->
            val label = elem.displayLabel
            val idStr = if (!elem.id.isNullOrBlank()) " id=${elem.id.substringAfter(":id/")}" else ""
            val roleStr = when {
                elem.isEditable -> " [EDITABLE]"
                elem.isClickable -> " [CLICKABLE]"
                elem.isScrollable -> " [SCROLLABLE]"
                else -> ""
            }
            val focusStr = if (elem.isFocused) " [FOCUSED]" else ""
            val selectStr = if (elem.isSelected) " [SELECTED]" else ""
            sb.appendLine("  [$index] \"$label\"$idStr bounds=[${elem.bounds.left},${elem.bounds.top}][${elem.bounds.right},${elem.bounds.bottom}]$roleStr$focusStr$selectStr")
        }
        if (focusedElement != null) {
            sb.appendLine("FOCUSED: ${focusedElement.displayLabel} (id=${focusedElement.id})")
        }
        if (scrollableElements.isNotEmpty()) {
            sb.appendLine("SCROLLABLE: ${scrollableElements.size} region(s)")
        }
        if (hasScreenshot) {
            sb.appendLine("SCREENSHOT: available in memory")
        }
        return sb.toString()
    }

    fun findElementByText(query: String, ignoreCase: Boolean = true): InteractiveElement? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null

        // 1. Exact match on text or contentDescription
        interactiveElements.firstOrNull {
            it.text?.equals(trimmed, ignoreCase = ignoreCase) == true ||
            it.contentDescription?.equals(trimmed, ignoreCase = ignoreCase) == true
        }?.let { return it }

        // 2. Contains match
        interactiveElements.firstOrNull {
            it.text?.contains(trimmed, ignoreCase = ignoreCase) == true ||
            it.contentDescription?.contains(trimmed, ignoreCase = ignoreCase) == true
        }?.let { return it }

        // 3. Resource ID match
        return interactiveElements.firstOrNull {
            it.id?.contains(trimmed, ignoreCase = ignoreCase) == true
        }
    }

    fun findEditableElement(query: String? = null): InteractiveElement? {
        if (!query.isNullOrBlank()) {
            val matched = findElementByText(query)
            if (matched != null && matched.isEditable) return matched
        }
        return focusedElement?.takeIf { it.isEditable }
            ?: editableElements.firstOrNull()
            ?: interactiveElements.firstOrNull { it.isEditable }
    }
}
