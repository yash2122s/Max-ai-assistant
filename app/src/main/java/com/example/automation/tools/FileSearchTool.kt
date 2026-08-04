package com.example.automation.tools

import android.content.Context
import android.os.Environment
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class FileSearchTool : Tool {
    private val TAG = "FileSearchTool"
    override val name: String = "file_search"
    override val supportedActions: Set<String> = setOf("SEARCH_FILES")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        val args = JSONObject(request.arguments.toString())
        val query = args.optString("query", "").trim()
        val fileType = args.optString("file_type", "any").lowercase().trim()

        return try {
            val results = JSONArray()
            val roots = listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            )

            val targetExtensions = when (fileType) {
                "pdf" -> listOf(".pdf")
                "image" -> listOf(".jpg", ".jpeg", ".png", ".webp")
                "doc" -> listOf(".doc", ".docx", ".pdf", ".txt", ".xls", ".xlsx", ".ppt", ".pptx")
                else -> emptyList()
            }

            for (root in roots) {
                if (root.exists() && root.isDirectory) {
                    searchDirectory(root, query, targetExtensions, results, maxResults = 10)
                }
            }

            ToolResult(
                success = true,
                toolName = name,
                message = "Found ${results.length()} matching files.",
                metadata = JSONObject().apply { put("files", results) },
                verificationRequired = false
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Storage permission denied for file search", e)
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "PERMISSION_DENIED",
                message = "File search requires Storage permission. Please grant it in App Info Settings."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error executing file search", e)
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "FILE_SEARCH_ERROR",
                message = e.message ?: "Failed to perform local file search"
            )
        }
    }

    private fun searchDirectory(
        dir: File,
        query: String,
        extensions: List<String>,
        results: JSONArray,
        maxResults: Int
    ) {
        if (results.length() >= maxResults) return

        dir.listFiles()?.forEach { file ->
            if (results.length() >= maxResults) return
            
            if (file.isDirectory) {
                if (!file.name.startsWith(".")) {
                    searchDirectory(file, query, extensions, results, maxResults)
                }
            } else {
                val name = file.name.lowercase()
                val matchesQuery = query.isEmpty() || name.contains(query.lowercase())
                val matchesExt = extensions.isEmpty() || extensions.any { name.endsWith(it) }

                if (matchesQuery && matchesExt) {
                    val item = JSONObject().apply {
                        put("name", file.name)
                        put("path", file.absolutePath)
                        put("size", file.length())
                        put("lastModified", file.lastModified())
                    }
                    results.put(item)
                }
            }
        }
    }
}
