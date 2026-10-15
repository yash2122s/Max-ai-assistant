package com.example.automation.ui

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.models.UiTarget
import java.util.Locale

object ScreenSummaryFormatter {

    fun format(
        elements: List<UiTarget>,
        root: AccessibilityNodeInfo? = null,
        maxElements: Int = 75
    ): String {
        val sb = StringBuilder()
        val pkg = root?.packageName?.toString() ?: ""
        val title = extractScreenTitle(elements)

        if (pkg.isNotBlank()) sb.append("App: $pkg | ")
        if (title.isNotBlank()) sb.append("Screen Title: \"$title\"\n")
        else if (pkg.isNotBlank()) sb.append("\n")

        sb.append("Visible Elements:\n")

        val screenBounds = Rect().also { root?.getBoundsInScreen(it) }
        val screenWidth = screenBounds.width().takeIf { it > 0 } ?: 1080
        val screenHeight = screenBounds.height().takeIf { it > 0 } ?: 2400

        val isWebSearchPage = (pkg.contains("chrome", true) || pkg.contains("browser", true)) &&
                isGoogleSearchResults(elements)

        if (isWebSearchPage) {
            sb.append("⚠️ [CURRENT VIEW: WEB SEARCH RESULTS]\n")
            sb.append("RULE: Do NOT type your generation/creative prompt into the Google search bar! " +
                    "Click a website result link below.\n")
        }

        var index = 0
        for (el in elements) {
            if (index >= maxElements) break
            if (!isMeaningful(el)) continue

            index++
            val label = if (el.isImageOrThumbnail && el.primaryLabel.contains("ImageView", true)) {
                "Image Thumbnail"
            } else el.primaryLabel

            if (isWebSearchPage && el.isEditable) {
                sb.append("[#$index] Search / URL Bar (InputField - DO NOT TYPE CREATIVE PROMPTS HERE)\n")
                continue
            }
            if (isWebSearchPage && el.isClickable && !el.isEditable &&
                (el.bounds?.top ?: 0) > 250 && !el.text.isNullOrBlank()
            ) {
                sb.append("[#$index] Search Result Link: \"$label\" (Clickable - Tap to open site)\n")
                continue
            }

            sb.append("[#$index] $label")
            when {
                el.isSwitchOrToggle() ->
                    sb.append(" (Switch: ${if (el.isChecked) "ON" else "OFF"})")
                el.isEditable ->
                    sb.append(" (InputField)")
                el.isImageOrThumbnail -> {
                    val b = el.bounds
                    if (b != null) {
                        val spatial = getSpatialPosition(b, screenWidth, screenHeight)
                        sb.append(" (Position: $spatial, bounds=[${b.left},${b.top},${b.right},${b.bottom}])")
                    }
                }
            }
            sb.append("\n")
        }
        return sb.toString().trim()
    }

    private fun isMeaningful(el: UiTarget): Boolean {
        if (el.isImageOrThumbnail) return true
        if (el.isEditable || el.isCheckable) return true
        if (!el.text.isNullOrBlank() || !el.contentDescription.isNullOrBlank()) return true
        return false
    }

    private fun extractScreenTitle(elements: List<UiTarget>): String {
        return elements
            .filter {
                val top = it.bounds?.top ?: 0
                val h = it.bounds?.height() ?: 0
                !it.text.isNullOrBlank() && top in 60..380 && h in 20..220 &&
                        (it.text?.length ?: 0) in 3..60
            }
            .minByOrNull { it.bounds?.top ?: Int.MAX_VALUE }
            ?.text ?: ""
    }

    fun getSpatialPosition(bounds: Rect, screenWidth: Int, screenHeight: Int): String {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val vPos = when {
            cy < screenHeight * 0.35f -> "Top"
            cy < screenHeight * 0.65f -> "Mid"
            else -> "Bottom"
        }
        val hPos = when {
            cx < screenWidth * 0.33f -> "Left"
            cx < screenWidth * 0.67f -> "Center"
            else -> "Right"
        }
        return "$vPos-$hPos"
    }

    fun isGoogleSearchResults(elements: List<UiTarget>): Boolean {
        val urlText = elements.asSequence()
            .filter { it.viewId.contains("url_bar", true) || it.viewId.contains("omnibox", true) }
            .joinToString(" ") { "${it.text} ${it.contentDescription}" }
            .lowercase(Locale.ROOT)
        if (urlText.contains("flow.google") || urlText.contains("labs.google/fx/tools/flow")) return false
        return urlText.contains("google.") &&
                (urlText.contains("/search") || urlText.contains("?q=") || urlText.contains("&q="))
    }
}
