package com.example.automation.tools

import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import com.example.automation.verification.RuntimeStepVerifier
import com.example.service.JarvisAccessibilityService

class WaitForUiTool : Tool {
    private val TAG = "WaitForUiTool"
    override val name: String = "wait_for_ui"
    override val supportedActions: Set<String> = setOf("WAIT_FOR_UI", "WAIT_FOR_ELEMENT", "VERIFY_UI")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = true,
        requiresNetwork = false,
        cancellable = true
    )

    override fun validate(request: ExecutionRequest): Boolean {
        return true
    }

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        val args = request.arguments
        val text = args.get("text")?.asString
            ?: args.get("target")?.asString
            ?: args.get("query")?.asString
            ?: ""
        val desc = args.get("description")?.asString
            ?: args.get("desc")?.asString
            ?: ""
        val resId = args.get("resourceId")?.asString
            ?: args.get("id")?.asString
            ?: ""
        val pkg = args.get("package_name")?.asString
            ?: args.get("packageName")?.asString
            ?: ""
        val timeoutMs = args.get("timeout")?.asLong
            ?: args.get("timeoutMs")?.asLong
            ?: 5000L

        val query = text.ifEmpty { desc }.ifEmpty { resId }.ifEmpty { "composer" }
        Log.d(TAG, "Executing WaitForUi: target='$query', package='$pkg', timeout=${timeoutMs}ms")

        val targetMatched = if (pkg.isNotEmpty() && query.isBlank()) {
            RuntimeStepVerifier.verifyAppForeground(context, pkg, timeoutMs)
        } else {
            // Check for composer / message input explicitly
            val isComposerTarget = query.equals("composer", ignoreCase = true) ||
                                   query.equals("message composer", ignoreCase = true) ||
                                   query.contains("chat", ignoreCase = true)
            if (isComposerTarget) {
                verifyComposerPresent(timeoutMs)
            } else {
                RuntimeStepVerifier.verifyTargetVisible(
                    context = context,
                    targetQuery = query,
                    timeoutMs = timeoutMs
                )
            }
        }

        return ToolResult(
            success = targetMatched,
            toolName = name,
            message = if (targetMatched) "Target '$query' verified on screen" else "Timed out waiting for '$query' on screen ($timeoutMs ms)",
            errorCode = if (!targetMatched) "TARGET_NOT_FOUND" else null,
            verificationRequired = true
        )
    }

    private suspend fun verifyComposerPresent(timeoutMs: Long): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val root = JarvisAccessibilityService.getActiveRoot()
            if (root != null) {
                if (hasChatComposer(root)) {
                    Log.d(TAG, "✓ Chat composer verified present on screen")
                    return true
                }
            }
            kotlinx.coroutines.delay(100L)
        }
        val finalRoot = JarvisAccessibilityService.getActiveRoot()
        return finalRoot?.let { hasChatComposer(it) } ?: false
    }

    private fun hasChatComposer(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val id = node.viewIdResourceName?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val hint = try { node.hintText?.toString()?.lowercase() ?: "" } catch (_: Throwable) { "" }
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        if (id.contains("composer_edittext") || id.contains("row_thread_composer") ||
            id.contains("chat_input") || id.contains("message_input") || id.contains("entry_edittext")) {
            return true
        }

        if (node.isEditable && (text.contains("message") || hint.contains("message") || desc.contains("message") ||
                                text.contains("type a message") || hint.contains("type a message"))) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
            if (hasChatComposer(child)) return true
        }

        return false
    }
}
