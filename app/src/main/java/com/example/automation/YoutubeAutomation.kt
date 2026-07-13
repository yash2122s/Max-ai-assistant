package com.example.automation

import android.content.Context
import android.content.Intent
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
        Log.d(TAG, "Starting YouTube automation for query: $query")
        
        // 1. Open YouTube app
        openYoutube(context)
        delay(2000) // Wait for YouTube to launch and render
        
        // 2. Click Search Button
        var root = getFreshRoot()
        if (root == null || !clickSearch(root)) {
            Log.e(TAG, "Failed to click search button, retrying once...")
            delay(1500)
            root = getFreshRoot()
            if (root == null || !clickSearch(root)) return false
        }
        delay(1000) // Wait for search view to expand
        
        // 3. Type Search Query
        root = getFreshRoot() ?: return false
        if (!typeQuery(root, query)) {
            Log.e(TAG, "Failed to type search query")
            return false
        }
        delay(800)
        
        // 4. Press Enter/Search
        root = getFreshRoot() ?: return false
        if (!pressSearch(root)) {
            Log.e(TAG, "Failed to press search enter key")
            return false
        }
        delay(2500) // Wait for search results list to populate
        
        // 5. Click First Video
        root = getFreshRoot() ?: return false
        if (!clickFirstVideo(root)) {
            Log.e(TAG, "Failed to click first playable video")
            return false
        }
        
        Log.d(TAG, "YouTube automation completed successfully!")
        return true
    }

    private fun getFreshRoot(): AccessibilityNodeInfo? {
        val service = ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)
        if (service == null) {
            Log.e(TAG, "Accessibility Service is not bound or active in ServiceRegistry")
            return null
        }
        val root = service.rootInActiveWindow
        if (root == null) {
            Log.e(TAG, "rootInActiveWindow is null")
        }
        return root
    }

    private fun openYoutube(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            Log.e(TAG, "YouTube app not installed")
        }
    }

    private fun clickSearch(root: AccessibilityNodeInfo): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/menu_item_view")
        if (nodes.isNullOrEmpty()) {
            // Fallback contentDescription search
            val descNodes = root.findAccessibilityNodeInfosByText("Search")
            for (node in descNodes) {
                if (node.isClickable && (node.contentDescription?.toString()?.contains("Search", ignoreCase = true) == true)) {
                    return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            return false
        }
        for (node in nodes) {
            if (node.contentDescription?.toString() == "Search") {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        return false
    }

    private fun typeQuery(root: AccessibilityNodeInfo, query: String): Boolean {
        val node = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/search_edit_text").firstOrNull()
            ?: return false
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            query
        )
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun pressSearch(root: AccessibilityNodeInfo): Boolean {
        val node = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/search_edit_text").firstOrNull()
            ?: return false
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } else {
            node.performAction(131072) // 131072 = 0x00020000 (ACTION_IME_ENTER id)
        }
    }

    private fun clickFirstVideo(root: AccessibilityNodeInfo): Boolean {
        fun dfs(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            val desc = node.contentDescription?.toString()
            if (node.isClickable && desc != null && desc.contains("play video", ignoreCase = true)) {
                return node
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val result = dfs(child)
                if (result != null) return result
            }
            return null
        }
        val target = dfs(root)
        if (target != null) {
            return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return false
    }
}
