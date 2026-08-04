package com.example.service

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

data class PrunedNode(
    val nodeId: String,
    val className: String,
    val text: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val boundsInScreen: Rect = Rect()
) {
    fun toJsonObject(): JSONObject {
        val json = JSONObject()
        json.put("id", nodeId)
        json.put("class", className.substringAfterLast('.'))
        text?.let { if (it.isNotBlank()) json.put("text", it) }
        contentDescription?.let { if (it.isNotBlank()) json.put("desc", it) }
        resourceId?.let { if (it.isNotBlank()) json.put("resourceId", it) }
        if (isClickable) json.put("clickable", true)
        if (isEditable) json.put("editable", true)
        if (isScrollable) json.put("scrollable", true)
        json.put("bounds", "[${boundsInScreen.left},${boundsInScreen.top},${boundsInScreen.right},${boundsInScreen.bottom}]")
        return json
    }
}

object AccessibilityTreePruner {
    private const val TAG = "AccessibilityTreePruner"
    private const val MAX_PRUNED_NODES = 60

    fun pruneTree(rootNode: AccessibilityNodeInfo?): List<PrunedNode> {
        if (rootNode == null) return emptyList()

        val prunedList = mutableListOf<PrunedNode>()
        val startTime = System.currentTimeMillis()

        traverseAndFilter(rootNode, prunedList)

        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "Pruned tree from root node: extracted ${prunedList.size} interactive nodes in ${duration}ms")
        return prunedList.take(MAX_PRUNED_NODES)
    }

    private fun traverseAndFilter(node: AccessibilityNodeInfo, result: MutableList<PrunedNode>) {
        if (!node.isVisibleToUser) return

        val textStr = node.text?.toString()?.trim()
        val descStr = node.contentDescription?.toString()?.trim()
        val resId = node.viewIdResourceName?.toString()?.trim()

        val isClickable = node.isClickable
        val isEditable = node.isEditable
        val isScrollable = node.isScrollable
        val isFocusable = node.isFocusable
        val hasMeaningfulText = !textStr.isNullOrEmpty() || !descStr.isNullOrEmpty()

        // Filter criteria: Keep interactive elements or elements with explicit text/descriptions
        val isRelevant = isClickable || isEditable || isScrollable || (isFocusable && hasMeaningfulText)

        if (isRelevant) {
            val rect = Rect()
            node.getBoundsInScreen(rect)

            val prunedNode = PrunedNode(
                nodeId = "node_${result.size}",
                className = node.className?.toString() ?: "View",
                text = textStr,
                contentDescription = descStr,
                resourceId = resId,
                isClickable = isClickable,
                isEditable = isEditable,
                isScrollable = isScrollable,
                boundsInScreen = rect
            )
            result.add(prunedNode)
        }

        // Recursively traverse child nodes
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            traverseAndFilter(child, result)
            child.recycle()
        }
    }

    fun toJsonArray(prunedNodes: List<PrunedNode>): JSONArray {
        val jsonArray = JSONArray()
        for (node in prunedNodes) {
            jsonArray.put(node.toJsonObject())
        }
        return jsonArray
    }
}
