package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.ToolResult
import com.example.automation.tools.YoutubeTool
import com.example.automation.verification.DeviceContext
import com.example.automation.verification.UiNode
import com.example.automation.verification.UiSnapshot
import com.example.automation.verification.YoutubeVerifier
import com.example.network.FunctionCall
import com.example.network.ToolDispatcher
import com.google.gson.JsonObject
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YoutubeAutomationUnitTest {

    @Test
    fun testYoutubeVerifier_FailsWithoutPlayerControls() {
        val verifier = YoutubeVerifier()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val args = JsonObject().apply { addProperty("query", "Believer") }
        val request = ExecutionRequest(action = "YOUTUBE_SEARCH", arguments = args)
        val result = ToolResult(success = true, toolName = "youtube")
        
        // Snapshot with YouTube foreground, but only search results/cards (no player controls)
        val snapshot = DeviceContext(
            ui = UiSnapshot(
                packageName = "com.google.android.youtube",
                nodes = listOf(
                    UiNode(text = "Believer - Imagine Dragons", id = "com.google.android.youtube:id/title"),
                    UiNode(text = "ImagineDragonsVEVO", id = "com.google.android.youtube:id/channel_name")
                )
            )
        )

        val verification = verifier.verify(context, request, result, snapshot)
        assertFalse("Verification must fail when player controls are absent", verification.success)
        assertTrue("Retry recommended must be true", verification.retryRecommended)
        assertNotNull(verification.reason)
    }

    @Test
    fun testYoutubeVerifier_SucceedsWithPlayerControls() {
        val verifier = YoutubeVerifier()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val args = JsonObject().apply { addProperty("query", "Believer") }
        val request = ExecutionRequest(action = "YOUTUBE_SEARCH", arguments = args)
        val result = ToolResult(success = true, toolName = "youtube")

        // Snapshot with YouTube playback controls active
        val snapshot = DeviceContext(
            ui = UiSnapshot(
                packageName = "com.google.android.youtube",
                nodes = listOf(
                    UiNode(contentDescription = "Pause video", id = "com.google.android.youtube:id/player_control_play_pause"),
                    UiNode(id = "com.google.android.youtube:id/player_view")
                )
            )
        )

        val verification = verifier.verify(context, request, result, snapshot)
        assertTrue("Verification must succeed when player controls are present", verification.success)
        assertFalse("Retry should not be recommended on success", verification.retryRecommended)
        assertNull(verification.reason)
    }

    @Test
    fun testYoutubeVerifier_OpenYoutube_SucceedsOnForeground() {
        val verifier = YoutubeVerifier()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val request = ExecutionRequest(action = "OPEN_YOUTUBE")
        val result = ToolResult(success = true, toolName = "youtube")

        val snapshot = DeviceContext(
            ui = UiSnapshot(
                packageName = "com.google.android.youtube",
                nodes = emptyList()
            )
        )

        val verification = verifier.verify(context, request, result, snapshot)
        assertTrue("OPEN_YOUTUBE should succeed on package match without requiring player controls", verification.success)
    }

    @Test
    fun testToolDispatcher_WordAwareNormalization_PreservesSubstrings() {
        val args = JsonObject().apply { addProperty("app_name", "YouTube") }
        val fc = FunctionCall(name = "open_app", args = args)

        // 1. Coldplay
        val resColdplay = ToolDispatcher.dispatch(fc, "play coldplay songs on youtube")
        val jsonColdplay = JSONObject(resColdplay)
        assertEquals("YOUTUBE_SEARCH", jsonColdplay.getString("action"))
        assertEquals("coldplay songs", jsonColdplay.getString("query"))

        // 2. Playlist
        val resPlaylist = ToolDispatcher.dispatch(fc, "play playlist 90s on youtube")
        val jsonPlaylist = JSONObject(resPlaylist)
        assertEquals("YOUTUBE_SEARCH", jsonPlaylist.getString("action"))
        assertEquals("playlist 90s", jsonPlaylist.getString("query"))

        // 3. Gameplay
        val resGameplay = ToolDispatcher.dispatch(fc, "play gameplay video on youtube")
        val jsonGameplay = JSONObject(resGameplay)
        assertEquals("YOUTUBE_SEARCH", jsonGameplay.getString("action"))
        assertEquals("gameplay video", jsonGameplay.getString("query"))

        // 4. Display
        val resDisplay = ToolDispatcher.dispatch(fc, "search display settings on youtube")
        val jsonDisplay = JSONObject(resDisplay)
        assertEquals("YOUTUBE_SEARCH", jsonDisplay.getString("action"))
        assertEquals("display settings", jsonDisplay.getString("query"))
    }

    @Test
    fun testToolDispatcher_TeluguAlias_HandlesYouTubeSearch() {
        // Test Telugu app_name with search triggers
        val argsTelugu = JsonObject().apply { addProperty("app_name", "యూట్యూబ్") }
        val fcTelugu = FunctionCall(name = "open_app", args = argsTelugu)
        val resTelugu = ToolDispatcher.dispatch(fcTelugu, "యూట్యూబ్లో believer ప్లే చేయి")
        val jsonTelugu = JSONObject(resTelugu)
        assertEquals("YOUTUBE_SEARCH", jsonTelugu.getString("action"))
        assertEquals("believer", jsonTelugu.getString("query"))

        // Test Telugu app_name with open only
        val resOpen = ToolDispatcher.dispatch(fcTelugu, "యూట్యూబ్ ఓపెన్ చేయి")
        val jsonOpen = JSONObject(resOpen)
        assertEquals("OPEN_APP", jsonOpen.getString("action"))
        assertEquals("YouTube", jsonOpen.getString("app"))
    }

    @Test
    fun testYoutubeTool_Validation_DisallowsAppNameAsQuery() {
        val tool = YoutubeTool()

        // Missing query on YOUTUBE_SEARCH should fail validation
        val invalidArgs = JsonObject().apply { addProperty("app", "YouTube") }
        val invalidReq = ExecutionRequest(
            action = "YOUTUBE_SEARCH",
            arguments = invalidArgs
        )
        assertFalse("YoutubeTool must reject request with only app argument", tool.validate(invalidReq))

        // Valid query on YOUTUBE_SEARCH
        val validArgs = JsonObject().apply { addProperty("query", "Starboy") }
        val validReq = ExecutionRequest(
            action = "YOUTUBE_SEARCH",
            arguments = validArgs
        )
        assertTrue("YoutubeTool must accept request with valid query", tool.validate(validReq))

        // OPEN_YOUTUBE validates without query
        val openReq = ExecutionRequest(action = "OPEN_YOUTUBE")
        assertTrue("YoutubeTool must accept OPEN_YOUTUBE without query", tool.validate(openReq))
    }
}
