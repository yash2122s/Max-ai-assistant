package com.example.automation.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ClipboardTool : Tool {
    private val TAG = "ClipboardTool"
    override val name: String = "clipboard"
    override val supportedActions: Set<String> = setOf("GET_CLIPBOARD", "SET_CLIPBOARD")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult = withContext(Dispatchers.Main) {
        val action = request.action
        val args = JSONObject(request.arguments.toString())

        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            when (action) {
                "GET_CLIPBOARD" -> {
                    val clipText = if (clipboard.hasPrimaryClip()) {
                        val clip = clipboard.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            clip.getItemAt(0).text?.toString() ?: ""
                        } else {
                            ""
                        }
                    } else {
                        ""
                    }

                    if (clipText.isEmpty()) {
                        ToolResult(
                            success = true,
                            toolName = name,
                            message = "Clipboard is empty or access restricted by Android (the app must be in the foreground to read clipboard content).",
                            metadata = JSONObject().apply { put("text", "") },
                            verificationRequired = false
                        )
                    } else {
                        ToolResult(
                            success = true,
                            toolName = name,
                            message = "Retrieved clipboard text successfully.",
                            metadata = JSONObject().apply { put("text", clipText) },
                            verificationRequired = false
                        )
                    }
                }
                "SET_CLIPBOARD" -> {
                    val text = args.optString("text", "").trim()
                    if (text.isEmpty()) {
                        return@withContext ToolResult(
                            success = false,
                            toolName = name,
                            errorCode = "INVALID_ARGUMENTS",
                            message = "Copy text cannot be empty."
                        )
                    }

                    val clip = ClipData.newPlainText("MAX Clipboard", text)
                    clipboard.setPrimaryClip(clip)

                    ToolResult(
                        success = true,
                        toolName = name,
                        message = "Copied text to clipboard successfully."
                    )
                }
                else -> {
                    ToolResult(
                        success = false,
                        toolName = name,
                        errorCode = "UNSUPPORTED_ACTION",
                        message = "Unsupported clipboard action: $action"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing clipboard action: $action", e)
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "CLIPBOARD_ERROR",
                message = e.message ?: "Failed to perform clipboard operation"
            )
        }
    }
}
