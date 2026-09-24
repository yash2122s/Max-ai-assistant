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

        val action = request.action.uppercase()
        if (action == "OPEN_YOUTUBE" || action == "LAUNCH_YOUTUBE") {
            return VerificationResult(
                success = isYoutube,
                reason = if (isYoutube) null else "Not currently on YouTube",
                retryRecommended = !isYoutube,
                snapshot = snapshot
            )
        }

        if (action in setOf(
                "PAUSE_YOUTUBE", "YOUTUBE_PAUSE",
                "RESUME_YOUTUBE", "YOUTUBE_RESUME",
                "NEXT_YOUTUBE_VIDEO", "YOUTUBE_NEXT",
                "PREVIOUS_YOUTUBE_VIDEO", "YOUTUBE_PREVIOUS",
                "SKIP_AD", "YOUTUBE_SKIP_AD",
                "FULLSCREEN_YOUTUBE", "YOUTUBE_FULLSCREEN"
            )) {
            return VerificationResult(
                success = isYoutube,
                reason = if (isYoutube) null else "YouTube is not foreground during media control",
                retryRecommended = !isYoutube,
                snapshot = snapshot
            )
        }

        val hasPlayerControls = snapshot.ui.nodes.any { node ->
            val desc = node.contentDescription?.lowercase().orEmpty()
            val text = node.text?.lowercase().orEmpty()
            val id = node.id?.lowercase().orEmpty()
            val cls = node.className.lowercase()

            // English player controls
            desc.contains("pause video") || desc.contains("play video") || 
            desc.contains("player controls") || desc.contains("video player") ||
            desc.contains("replay video") || desc.contains("seek bar") ||
            desc.contains("watch in full screen") || desc.contains("enter full screen") ||
            desc.contains("like this video") || desc.contains("dislike this video") ||
            desc.contains("share this video") || desc.contains("save to playlist") ||
            text.contains("pause") ||

            // Telugu & Hindi controls
            desc.contains("పాజ్ చేయండి") || desc.contains("ప్లే చేయండి") ||
            desc.contains("వీడియో పాజ్") || desc.contains("వీడియో ప్లే") ||
            desc.contains("పూర్తి స్క్రీన్") || desc.contains("లైక్") ||
            desc.contains("वीडियो रोकें") || desc.contains("वीडियो चलाएं") ||
            desc.contains("रोकें") || desc.contains("लाइक") ||

            // Player View / Watch Layout resource IDs
            id.contains("player_view") || id.contains("watch_panel") ||
            id.contains("exo_") || id.contains("player_control") ||
            id.contains("movie_player") || id.contains("watch_while") ||
            id.contains("next_gen_watch") || id.contains("video_player") ||
            id.contains("player_fragment_container") || id.contains("inline_player") ||
            id.contains("engagement_panel") || id.contains("video_metadata") ||
            id.contains("fullscreen_button") || id.contains("like_button") ||
            id.contains("dislike_button") || id.contains("comments_entry_point") ||

            // Video player surface class names
            cls.contains("playerview") || cls.contains("surfaceview") ||
            cls.contains("videoview") || cls.contains("textureview")
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

