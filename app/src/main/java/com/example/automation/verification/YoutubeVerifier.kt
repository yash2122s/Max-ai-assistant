package com.example.automation.verification

import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.ToolResult

class YoutubeVerifier : Verifier {
    override val supportedTools: Set<String> = setOf("youtube_search")

    override fun verify(
        context: Context,
        request: ExecutionRequest,
        result: ToolResult,
        snapshot: DeviceContext
    ): VerificationResult {
        val currentPkg = snapshot.ui.packageName
        val isYoutube = currentPkg.equals("com.google.android.youtube", ignoreCase = true)
        
        if (!isYoutube) {
            return VerificationResult(
                success = false,
                reason = "Foreground package is '$currentPkg' instead of YouTube",
                retryRecommended = true,
                snapshot = snapshot
            )
        }

        val hasPlayerControls = snapshot.ui.nodes.any { node ->
            val desc = node.contentDescription?.lowercase().orEmpty()
            val text = node.text?.lowercase().orEmpty()
            desc.contains("pause video") || desc.contains("play video") || 
            desc.contains("player controls") || text.contains("pause")
        }

        Log.d("YoutubeVerifier", "Verification match for YouTube: package check = $isYoutube, hasPlayerControls = $hasPlayerControls")

        return VerificationResult(
            success = isYoutube,
            reason = if (isYoutube) null else "Not currently on YouTube",
            retryRecommended = !isYoutube,
            snapshot = snapshot
        )
    }
}
