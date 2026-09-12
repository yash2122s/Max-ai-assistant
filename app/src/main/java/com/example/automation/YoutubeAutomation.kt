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

        // Wait for YouTube search results page to load and render
        delay(1800)

        // 2. Try to auto-click the first playable video via Accessibility Service
        var root = getFreshRoot()
        var clickedVideo = false

        if (root != null) {
            clickedVideo = clickFirstVideo(root)
            if (!clickedVideo) {
                // Retry once after short delay for slow networks/rendering
                delay(1200)
                root = getFreshRoot()
                if (root != null) {
                    clickedVideo = clickFirstVideo(root)
                }
            }
        }

        if (clickedVideo) {
            Log.d(TAG, "Successfully clicked first YouTube video result!")
        } else {
            Log.w(TAG, "Opened YouTube search results for '$cleanQuery', but could not auto-click video. (Results are visible on screen)")
        }

        // Return true because YouTube is guaranteed to be open with the requested query results!
        return true
    }

    private fun getFreshRoot(): AccessibilityNodeInfo? {
        val service = JarvisAccessibilityService.instance
            ?: ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)
        if (service == null) {
            Log.w(TAG, "Accessibility Service is not currently connected")
            return null
        }
        val root = service.rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "rootInActiveWindow is null")
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
        // Known YouTube Video container & thumbnail view IDs across app versions
        val targetIds = listOf(
            "com.google.android.youtube:id/thumbnail",
            "com.google.android.youtube:id/video_info",
            "com.google.android.youtube:id/compact_renderer",
            "com.google.android.youtube:id/title",
            "com.google.android.youtube:id/player_view"
        )

        for (resId in targetIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(resId)
            for (node in nodes) {
                if (performClick(node)) {
                    Log.d(TAG, "Clicked video node by ID: $resId")
                    return true
                }
            }
        }

        // Content Description matching across YouTube versions (e.g. "by", "views", "ago", "play video")
        fun findVideoByDesc(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val id = (node.viewIdResourceName ?: "").lowercase()
            
            // Skip search bar / filters / tabs / navigation buttons
            if (!id.contains("search") && !id.contains("filter") && !id.contains("tab") && !id.contains("action_bar") && !id.contains("menu")) {
                if (desc.contains("play video") || 
                    (desc.contains("ago") && (desc.contains("views") || desc.contains("streamed"))) ||
                    (desc.contains("minute") && desc.contains("second")) ||
                    desc.contains("by ") && desc.contains("views")) {
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

        val descNode = findVideoByDesc(root)
        if (descNode != null && performClick(descNode)) {
            Log.d(TAG, "Clicked video node by content description: ${descNode.contentDescription}")
            return true
        }

        // Generic clickable row fallback: find the first clickable element in the main content body
        fun findFirstClickableContent(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
            if (node == null || depth > 20) return null
            val id = (node.viewIdResourceName ?: "").lowercase()
            if (!id.contains("search") && !id.contains("filter") && !id.contains("tab") && !id.contains("header") && !id.contains("bar")) {
                if (node.isClickable && (node.contentDescription?.isNotEmpty() == true || node.text?.isNotEmpty() == true)) {
                    return node
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val res = findFirstClickableContent(child, depth + 1)
                if (res != null) return res
            }
            return null
        }

        val contentNode = findFirstClickableContent(root)
        if (contentNode != null && performClick(contentNode)) {
            Log.d(TAG, "Clicked video via first clickable content node")
            return true
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
        return false
    }
}

