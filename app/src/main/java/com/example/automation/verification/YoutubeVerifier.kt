package com.example.automation.verification

import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.ToolResult

class YoutubeVerifier : Verifier {
    override val supportedTools: Set<String> = setOf("youtube", "youtube_search")

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

        if (request.action == "OPEN_YOUTUBE") {
            return VerificationResult(
                success = isYoutube,
                reason = if (isYoutube) null else "Not currently on YouTube",
                retryRecommended = !isYoutube,
                snapshot = snapshot
            )
        }

        val hasPlayerControls = snapshot.ui.nodes.any { node ->
            val desc = node.contentDescription?.lowercase().orEmpty()
            val text = node.text?.lowercase().orEmpty()
            val id = node.id?.lowercase().orEmpty()
            val cls = node.className.lowercase()

            desc.contains("pause video") || desc.contains("play video") || 
            desc.contains("player controls") || desc.contains("video player") ||
            desc.contains("replay video") || desc.contains("seek bar") ||
            desc.contains("watch in full screen") || desc.contains("like this video") ||
            text.contains("pause") ||
            id.contains("player_view") || id.contains("watch_panel") ||
            id.contains("exo_") || id.contains("player_control") ||
            id.contains("movie_player") || id.contains("watch_while") ||
            id.contains("next_gen_watch") || id.contains("video_player") ||
            cls.contains("playerview") || cls.contains("surfaceview") || cls.contains("videoview")
        }

        Log.d("YoutubeVerifier", "Verification match for YouTube: package check = $isYoutube, hasPlayerControls = $hasPlayerControls")

        return if (hasPlayerControls) {
            VerificationResult(
                success = true,
                reason = null,
                retryRecommended = false,
                snapshot = snapshot
            )
        } else {
            VerificationResult(
                success = false,
                reason = "YouTube opened but video playback controls were not detected on screen",
                retryRecommended = true,
                snapshot = snapshot
            )
        }
    }
}
