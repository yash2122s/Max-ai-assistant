package com.example.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.core.registry.ServiceRegistry
import com.example.core.registry.ServiceType
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.delay

object YoutubeAutomation {
    private const val TAG = "YoutubeAutomation"

    suspend fun searchAndPlay(context: Context, query: String): Boolean {
        Log.d(TAG, "Starting YouTube automation for query: '$query'")
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return false

        // 1. Launch YouTube directly into search results via a direct results URL,
        //    which reliably opens the query (unlike ACTION_SEARCH which can ignore the
        //    query extra and leave stale search state on screen).
        val launched = openYoutubeSearchDirect(context, cleanQuery)
        if (!launched) {
            Log.e(TAG, "Failed to launch YouTube app via any intent")
            return false
        }

        // 2. Dynamically poll for YouTube search results UI and click the first playable video
        val startTime = System.currentTimeMillis()
        val timeoutMs = 4000L
        val pollIntervalMs = 150L
        var clickedVideo = false

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val root = getFreshRoot()
            if (root != null) {
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg.equals("com.google.android.youtube", ignoreCase = true)) {
                    if (clickFirstVideo(root)) {
                        clickedVideo = true
                        Log.d(TAG, "Successfully clicked first YouTube video result after ${System.currentTimeMillis() - startTime}ms!")
                        break
                    }
                }
            }
            delay(pollIntervalMs)
        }

        if (!clickedVideo) {
            val root = getFreshRoot()
            if (root == null) {
                Log.e(TAG, "Failed YouTube automation: Accessibility Service is disconnected or root is null")
            } else {
                Log.e(TAG, "Failed YouTube automation: Opened search for '$cleanQuery', but could not locate/click playable video within ${timeoutMs}ms")
            }
            return false
        }

        return true
    }

    private fun getFreshRoot(): AccessibilityNodeInfo? {
        val root = JarvisAccessibilityService.getActiveRoot()
            ?: ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)?.rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "Accessibility root is not currently available")
        }
        return root
    }

    private fun openYoutubeSearchDirect(context: Context, query: String): Boolean {
        val encodedQuery = Uri.encode(query)

        // Tier 1 (PREFERRED): Direct HTTPS search-results URL.
        // Modern YouTube accepts ACTION_SEARCH but silently ignores its query extra,
        // leaving whatever stale search state was on screen. A direct results URL
        // (https://www.youtube.com/results?search_query=...) reliably opens the actual
        // search results for the requested query.
        try {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encodedQuery")).apply {
                setPackage("com.google.android.youtube")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            if (context.packageManager.resolveActivity(webIntent, 0) != null) {
                context.startActivity(webIntent)
                Log.d(TAG, "Launched YouTube search '$query' via HTTPS results URL ($encodedQuery)")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tier 1 HTTPS results URL failed: ${e.message}")
        }

        // Tier 2: Custom YouTube URI Intent (vnd.youtube)
        try {
            val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube://results?search_query=$encodedQuery")).apply {
                setPackage("com.google.android.youtube")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            if (context.packageManager.resolveActivity(uriIntent, 0) != null) {
                context.startActivity(uriIntent)
                Log.d(TAG, "Launched YouTube via vnd.youtube URI intent")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tier 2 vnd.youtube failed: ${e.message}")
        }

        // Tier 3: YouTube Search Action Intent (kept as fallback; some YouTube
        // versions accept it but ignore the query extra)
        try {
            val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            if (context.packageManager.resolveActivity(searchIntent, 0) != null) {
                context.startActivity(searchIntent)
                Log.d(TAG, "Launched YouTube via ACTION_SEARCH intent")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tier 3 ACTION_SEARCH failed: ${e.message}")
        }

        // Tier 4: Fallback standard app launcher
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                Log.d(TAG, "Launched YouTube via standard launch intent")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tier 4 standard launch failed: ${e.message}")
        }

        return false
    }

    private fun clickFirstVideo(root: AccessibilityNodeInfo): Boolean {
        // Scope search to the results list if present, preventing accidental clicks on toolbar / filters / header
        val searchScope = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/results").firstOrNull()
            ?: root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/pane_fragment_contents").firstOrNull()
            ?: root

        fun isAdOrChannelOrNav(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            val id = (node.viewIdResourceName ?: "").lowercase()

            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)

            // Reject anything in the top header/appbar/filter area (y <= 340)
            if (bounds.bottom <= 340 && bounds.top < 200) {
                return true
            }

            // Reject Ads
            if (desc.contains("sponsored") || desc.contains("advertisement") || desc.contains("link opens in new window") ||
                text.equals("ad", ignoreCase = true) || text.contains("sponsored") ||
                id.contains("ad_") || id.contains("promoted")) {
                return true
            }
            // Reject Channels / Avatars / Subscribe
            if (desc.contains("subscribe") || desc.contains("go to channel") || desc.contains("explore menu") ||
                id.contains("avatar") || id.contains("channel_header") || id.contains("subscribe") || id.contains("channel_name")) {
                return true
            }
            // Reject Shorts / Navigation / Bars
            if (desc.contains("play short") || desc.contains("shorts") || desc.contains("filter") || desc.contains("explore") ||
                id.contains("search") || id.contains("filter") || id.contains("chip") ||
                id.contains("tab") || id.contains("pivot") || id.contains("action_bar") ||
                id.contains("toolbar") || id.contains("menu") || id.contains("header") ||
                id.contains("bar") || id.contains("logo") || id.contains("short")) {
                return true
            }
            return false
        }

        // 1. Content Description matching on video cards
        fun findVideoByDesc(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            
            if (!isAdOrChannelOrNav(node)) {
                val hasVideoIndicators = desc.contains("play video") || 
                    (desc.contains("ago") && (desc.contains("view") || desc.contains("crore") || desc.contains("lakh") || desc.contains("streamed"))) ||
                    ((desc.contains("minute") || desc.contains("second") || desc.contains("hour")) && (desc.contains("view") || desc.contains("ago") || desc.contains("·") || desc.contains("-"))) ||
                    (desc.contains("by ") && (desc.contains("view") || desc.contains("ago"))) ||
                    (desc.contains("watch") && (desc.contains("view") || desc.contains("ago")))

                if (hasVideoIndicators) {
                    return node
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val result = findVideoByDesc(child)
                if (result != null) return result
            }
            return null
        }

        val descNode = findVideoByDesc(searchScope)
        if (descNode != null && performClick(descNode)) {
            Log.d(TAG, "Clicked video node by verified content description: ${descNode.contentDescription}")
            return true
        }

        // 2. Known YouTube Video container & thumbnail view IDs
        val targetIds = listOf(
            "com.google.android.youtube:id/compact_renderer",
            "com.google.android.youtube:id/video_info",
            "com.google.android.youtube:id/thumbnail",
            "com.google.android.youtube:id/title"
        )

        for (resId in targetIds) {
            val nodes = searchScope.findAccessibilityNodeInfosByViewId(resId)
            for (node in nodes) {
                if (!isAdOrChannelOrNav(node) && performClick(node)) {
                    Log.d(TAG, "Clicked video node by ID: $resId")
                    return true
                }
            }
        }

        // 3. Fallback: Search direct items inside searchScope (RecyclerView)
        for (i in 0 until searchScope.childCount) {
            val item = searchScope.getChild(i) ?: continue
            if (!isAdOrChannelOrNav(item)) {
                if (performClick(item)) {
                    Log.d(TAG, "Clicked video card item in results list at index $i")
                    return true
                }
            }
        }

        return false
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 6) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
            depth++
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isClickable && child.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }
        val service = JarvisAccessibilityService.instance
        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                val x = bounds.centerX().toFloat()
                val y = bounds.centerY().toFloat()
                val path = android.graphics.Path().apply { moveTo(x, y) }
                val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 80)
                val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
                return service.dispatchGesture(gesture, null, null)
            }
        }
        return false
    }
}

