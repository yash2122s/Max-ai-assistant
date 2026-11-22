package com.example.service

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

data class IndexedUiElement(
    val index: Int,
    val text: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val isClickable: Boolean = false,
    val isFocusable: Boolean = false,
    val isEditable: Boolean = false,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false,
    val isScrollable: Boolean = false,
    val isEnabled: Boolean = true,
    val isVisibleToUser: Boolean = true,
    val bounds: Rect = Rect(),
    val role: String? = null
) {
    fun toCompactBlock(): String {
        val label = text?.takeIf { it.isNotBlank() }
            ?: contentDescription?.takeIf { it.isNotBlank() }
            ?: resourceId?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: className ?: "element"
        val sb = StringBuilder()
        sb.append("[$index] $label\n")
        if (!text.isNullOrBlank()) sb.append("    text=\"$text\"\n")
        if (!contentDescription.isNullOrBlank()) sb.append("    contentDescription=\"$contentDescription\"\n")
        if (!resourceId.isNullOrBlank()) sb.append("    resourceId=\"$resourceId\"\n")
        if (!className.isNullOrBlank()) sb.append("    class=\"$className\"\n")
        sb.append("    clickable=$isClickable\n")
        sb.append("    editable=$isEditable\n")
        sb.append("    enabled=$isEnabled\n")
        sb.append("    visible=$isVisibleToUser\n")
        sb.append("    bounds=[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]")
        return sb.toString()
    }

    fun toDisplayString(): String {
        val label = text?.takeIf { it.isNotBlank() }
            ?: contentDescription?.takeIf { it.isNotBlank() }
            ?: resourceId?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: className ?: "element"
        val res = resourceId?.substringAfterLast('/')?.let { "id=$it" } ?: ""
        val extra = mutableListOf<String>()
        if (isClickable) extra.add("clickable")
        if (isEditable) extra.add("input")
        if (isCheckable) extra.add(if (isChecked) "checked" else "unchecked")
        if (isScrollable) extra.add("scrollable")
        val roleStr = role?.let { "<$it>" } ?: ""
        val resStr = if (res.isNotBlank()) "<$res>" else ""
        val extraStr = if (extra.isNotEmpty()) "<${extra.joinToString(",")}>" else ""
        return "[$index] \"$label\" $resStr $extraStr $roleStr".replace(Regex("\\s+"), " ").trim()
    }
}

data class PrunedNode(
    val nodeId: String,
    val className: String,
    val normalizedType: String = "view",
    val text: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val boundsInScreen: Rect = Rect(),
    val isFocused: Boolean = false
) {
    fun toJsonObject(): JSONObject {
        val json = JSONObject()
        json.put("id", nodeId)
        json.put("type", normalizedType)
        text?.let { if (it.isNotBlank()) json.put("text", it) }
        contentDescription?.let { if (it.isNotBlank()) json.put("desc", it) }
        resourceId?.let { if (it.isNotBlank()) json.put("res", it.substringAfterLast('/')) }
        if (isClickable) json.put("clickable", true)
        if (isEditable) json.put("editable", true)
        if (isScrollable) json.put("scrollable", true)
        if (isFocused) json.put("focused", true)
        json.put("bounds", "[${boundsInScreen.left},${boundsInScreen.top},${boundsInScreen.right},${boundsInScreen.bottom}]")
        return json
    }
}

object AccessibilityTreePruner {
    private const val TAG = "AccessibilityTreePruner"
    private const val MAX_PRUNED_NODES = 60
    private const val MAX_INDEXED_ELEMENTS = 40
    private const val MAX_TEXT_LENGTH = 120

    fun pruneTree(rootNode: AccessibilityNodeInfo?): List<PrunedNode> {
        if (rootNode == null) return emptyList()

        val prunedList = mutableListOf<PrunedNode>()
        val startTime = System.currentTimeMillis()

        try {
            traverseAndFilter(rootNode, prunedList)
        } catch (e: Exception) {
            Log.w(TAG, "Error pruning accessibility tree: ${e.message}")
        }

        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "Pruned tree: extracted ${prunedList.size} interactive nodes in ${duration}ms")
        return prunedList.take(MAX_PRUNED_NODES)
    }

    /**
     * Session 1 P1: Traverses active accessibility tree, eliminates non-interactive noise,
     * assigns deterministic 1-based sequential indices, and retains live node mapping.
     */
    fun pruneAndIndexTree(rootNode: AccessibilityNodeInfo?): Pair<List<IndexedUiElement>, Map<Int, AccessibilityNodeInfo>> {
        if (rootNode == null) return Pair(emptyList(), emptyMap())

        val indexedElements = mutableListOf<IndexedUiElement>()
        val liveNodeMap = mutableMapOf<Int, AccessibilityNodeInfo>()
        val startTime = System.currentTimeMillis()

        try {
            traverseAndIndex(rootNode, indexedElements, liveNodeMap)
        } catch (e: Exception) {
            Log.w(TAG, "Error generating indexed element tree: ${e.message}")
        }

        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "Indexed tree: extracted ${indexedElements.size} interactive elements in ${duration}ms")
        return Pair(indexedElements.take(MAX_INDEXED_ELEMENTS), liveNodeMap)
    }

    private fun traverseAndIndex(
        node: AccessibilityNodeInfo,
        resultList: MutableList<IndexedUiElement>,
        nodeMap: MutableMap<Int, AccessibilityNodeInfo>
    ) {
        if (!node.isVisibleToUser) return
        if (resultList.size >= MAX_INDEXED_ELEMENTS) return

        val rawText = node.text?.toString()?.trim()
        val rawDesc = node.contentDescription?.toString()?.trim()
        val resId = node.viewIdResourceName?.toString()?.trim()

        // Filter system status bar noise
        if (resId != null && (resId.contains("status_bar") || resId.contains("battery") || resId.contains("clock"))) {
            return
        }

        val isClickable = node.isClickable
        val isEditable = node.isEditable
        val isScrollable = node.isScrollable
        val isFocusable = node.isFocusable
        val isCheckable = node.isCheckable
        val isChecked = node.isChecked
        val isEnabled = node.isEnabled

        val textStr = rawText?.take(MAX_TEXT_LENGTH)
        val descStr = rawDesc?.take(MAX_TEXT_LENGTH)
        val hasMeaningfulText = !textStr.isNullOrEmpty() || !descStr.isNullOrEmpty()

        // Filter: Keep actionable interactive elements or elements with meaningful text
        val isInteractive = (isClickable || isEditable || isScrollable || isCheckable || (isFocusable && hasMeaningfulText)) && isEnabled

        if (isInteractive) {
            val rect = Rect()
            node.getBoundsInScreen(rect)

            // Skip zero-dimension elements
            if (rect.width() > 0 && rect.height() > 0) {
                val rawClass = node.className?.toString() ?: "View"
                val normalizedRole = normalizeClass(rawClass)

                val nextIndex = resultList.size + 1
                val indexedElement = IndexedUiElement(
                    index = nextIndex,
                    text = textStr,
                    contentDescription = descStr,
                    resourceId = resId,
                    className = rawClass.substringAfterLast('.'),
                    isClickable = isClickable,
                    isFocusable = isFocusable,
                    isEditable = isEditable,
                    isCheckable = isCheckable,
                    isChecked = isChecked,
                    isScrollable = isScrollable,
                    isEnabled = isEnabled,
                    isVisibleToUser = node.isVisibleToUser,
                    bounds = rect,
                    role = normalizedRole
                )

                resultList.add(indexedElement)
                nodeMap[nextIndex] = node
            }
        }

        // Recursively traverse children
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            traverseAndIndex(child, resultList, nodeMap)
        }
    }

    private fun traverseAndFilter(node: AccessibilityNodeInfo, result: MutableList<PrunedNode>) {
        if (!node.isVisibleToUser) return
        if (result.size >= MAX_PRUNED_NODES) return

        val rawText = node.text?.toString()?.trim()
        val rawDesc = node.contentDescription?.toString()?.trim()
        val resId = node.viewIdResourceName?.toString()?.trim()

        // Filter system status bar noise
        if (resId != null && (resId.contains("status_bar") || resId.contains("battery") || resId.contains("clock"))) {
            return
        }

        val isClickable = node.isClickable
        val isEditable = node.isEditable
        val isScrollable = node.isScrollable
        val isFocusable = node.isFocusable

        val textStr = rawText?.take(MAX_TEXT_LENGTH)
        val descStr = rawDesc?.take(MAX_TEXT_LENGTH)
        val hasMeaningfulText = !textStr.isNullOrEmpty() || !descStr.isNullOrEmpty()

        // Filter criteria: Keep interactive elements or elements with explicit text/descriptions
        val isRelevant = isClickable || isEditable || isScrollable || (isFocusable && hasMeaningfulText) || hasMeaningfulText

        if (isRelevant) {
            val rect = Rect()
            node.getBoundsInScreen(rect)

            // Skip zero-dimension elements
            if (rect.width() > 0 && rect.height() > 0) {
                val rawClass = node.className?.toString() ?: "View"
                val normalized = normalizeClass(rawClass)

                val prunedNode = PrunedNode(
                    nodeId = "node_${result.size}",
                    className = rawClass.substringAfterLast('.'),
                    normalizedType = normalized,
                    text = textStr,
                    contentDescription = descStr,
                    resourceId = resId,
                    isClickable = isClickable,
                    isEditable = isEditable,
                    isScrollable = isScrollable,
                    boundsInScreen = rect,
                    isFocused = node.isFocused
                )
                result.add(prunedNode)
            }
        }

        // Recursively traverse child nodes with deterministic lifecycle recycling
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                traverseAndFilter(child, result)
            } finally {
                try {
                    child.recycle()
                } catch (_: Exception) {}
            }
        }
    }

    private fun normalizeClass(rawClass: String): String {
        val simple = rawClass.substringAfterLast('.')
        return when {
            simple.contains("EditText", ignoreCase = true) -> "input"
            simple.contains("Button", ignoreCase = true) -> "btn"
            simple.contains("TextView", ignoreCase = true) -> "text"
            simple.contains("ImageView", ignoreCase = true) -> "image"
            simple.contains("Recycler", ignoreCase = true) ||
                simple.contains("List", ignoreCase = true) ||
                simple.contains("Scroll", ignoreCase = true) ||
                simple.contains("ViewPager", ignoreCase = true) -> "list"
            simple.contains("CheckBox", ignoreCase = true) -> "checkbox"
            simple.contains("Switch", ignoreCase = true) -> "switch"
            else -> "view"
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
