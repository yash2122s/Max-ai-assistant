package com.example.automation.resolver

import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.models.TargetResolutionMethod
import com.example.automation.models.UiTarget
import java.util.Locale

/**
 * Specialized Input & Text-Field Target Resolver.
 * Discovers and grounds editable fields, search bars, and active text inputs.
 *
 * Priority Resolution Order:
 * 1. Explicit target hint resolution (via UiTargetResolver)
 * 2. Focused input node (FOCUS_INPUT or isFocused)
 * 3. Single active editable field (isEditable or EditText class)
 * 4. Search bar / query input heuristics (ID, hint, description matching search)
 * 5. First interactive editable container
 */
object InputTargetResolver {
    private const val TAG = "InputTargetResolver"

    data class ResolvedInput(
        val node: AccessibilityNodeInfo,
        val target: UiTarget,
        val isAlreadyFocused: Boolean
    )

    fun resolveInputTarget(
        rootNode: AccessibilityNodeInfo,
        targetHint: String? = null
    ): ResolvedInput? {
        // 1. Explicit target hint resolution
        if (!targetHint.isNullOrBlank()) {
            val res = UiTargetResolver.resolve(query = targetHint, rootNode = rootNode)
            if (res.success && res.target != null && res.matchedNode != null) {
                val node = res.matchedNode
                val isFocused = try { node.isFocused } catch (_: Exception) { false }
                Log.d(TAG, "Resolved input by targetHint '$targetHint' (${res.method}, confidence=${res.confidence})")
                return ResolvedInput(node, res.target, isFocused)
            }
        }

        // 2. Focused Input Node (FOCUS_INPUT or isFocused)
        val focused = try {
            rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFocusedNodeRecursive(rootNode)
        } catch (_: Exception) {
            findFocusedNodeRecursive(rootNode)
        }

        if (focused != null && (focused.isEditable || isEditTextClass(focused))) {
            val target = UiTarget(
                resourceId = try { focused.viewIdResourceName } catch (_: Exception) { null },
                text = try { focused.text?.toString() } catch (_: Exception) { null },
                contentDescription = try { focused.contentDescription?.toString() } catch (_: Exception) { null },
                role = "focused_edit_text",
                confidence = 0.98f,
                method = TargetResolutionMethod.EDITABLE
            )
            Log.d(TAG, "Resolved input from active focused node")
            return ResolvedInput(focused, target, isAlreadyFocused = true)
        }

        // 3. Discover all editable nodes
        val editables = findAllEditableNodes(rootNode)
        if (editables.size == 1) {
            val single = editables[0]
            val target = UiTarget(
                resourceId = try { single.viewIdResourceName } catch (_: Exception) { null },
                text = try { single.text?.toString() } catch (_: Exception) { null },
                contentDescription = try { single.contentDescription?.toString() } catch (_: Exception) { null },
                role = "single_editable_field",
                confidence = 0.92f,
                method = TargetResolutionMethod.EDITABLE
            )
            val isFocused = try { single.isFocused } catch (_: Exception) { false }
            Log.d(TAG, "Resolved single editable node on screen")
            return ResolvedInput(single, target, isFocused)
        }

        // 4. Search bar / query / composer input heuristic matching
        val searchKeywords = listOf(
            "search", "search_box", "search_bar", "search_src_text",
            "search_omnibox_text_box", "query", "find", "search here", "action_bar_search_edit_text",
            "search_edit_text", "meta ai", "ask meta ai", "row_thread_composer_edittext",
            "search_row", "ig_text", "entry", "message", "వెతుకు", "శోధించు"
        )
        for (editable in editables) {
            val id = try { editable.viewIdResourceName?.lowercase(Locale.ROOT) ?: "" } catch (_: Exception) { "" }
            val desc = try { editable.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: "" } catch (_: Exception) { "" }
            val text = try { editable.text?.toString()?.lowercase(Locale.ROOT) ?: "" } catch (_: Exception) { "" }
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try { editable.hintText?.toString()?.lowercase(Locale.ROOT) ?: "" } catch (_: Exception) { "" }
            } else ""

            if (searchKeywords.any { id.contains(it) || desc.contains(it) || hint.contains(it) || text.contains(it) }) {
                val target = UiTarget(
                    resourceId = try { editable.viewIdResourceName } catch (_: Exception) { null },
                    text = try { editable.text?.toString() } catch (_: Exception) { null },
                    contentDescription = try { editable.contentDescription?.toString() } catch (_: Exception) { null },
                    role = "search_or_composer_field",
                    confidence = 0.95f,
                    method = TargetResolutionMethod.SEMANTIC
                )
                val isFocused = try { editable.isFocused } catch (_: Exception) { false }
                Log.d(TAG, "Resolved input as semantic search/composer field: id=$id")
                return ResolvedInput(editable, target, isFocused)
            }
        }

        // 5. If multiple editables, return first one if non-empty
        if (editables.isNotEmpty()) {
            val first = editables[0]
            val target = UiTarget(
                resourceId = try { first.viewIdResourceName } catch (_: Exception) { null },
                text = try { first.text?.toString() } catch (_: Exception) { null },
                contentDescription = try { first.contentDescription?.toString() } catch (_: Exception) { null },
                role = "first_editable_field",
                confidence = 0.86f,
                method = TargetResolutionMethod.EDITABLE
            )
            val isFocused = try { first.isFocused } catch (_: Exception) { false }
            return ResolvedInput(first, target, isFocused)
        }

        return null
    }

    private fun findFocusedNodeRecursive(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 25) return null
        if (try { node.isFocused } catch (_: Exception) { false }) return node
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            val found = findFocusedNodeRecursive(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    fun findAllEditableNodes(node: AccessibilityNodeInfo, depth: Int = 0): List<AccessibilityNodeInfo> {
        if (depth > 25) return emptyList()
        val list = mutableListOf<AccessibilityNodeInfo>()
        val isEdit = try { node.isEditable || isEditTextClass(node) } catch (_: Exception) { false }
        if (isEdit) {
            list.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            list.addAll(findAllEditableNodes(child, depth + 1))
        }
        return list
    }

    fun isEditTextClass(node: AccessibilityNodeInfo): Boolean {
        val cls = try { node.className?.toString() ?: "" } catch (_: Exception) { "" }
        return cls.contains("EditText", ignoreCase = true) || cls.contains("AutoCompleteTextView", ignoreCase = true)
    }
}
