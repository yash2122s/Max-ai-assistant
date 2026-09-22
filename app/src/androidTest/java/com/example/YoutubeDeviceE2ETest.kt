package com.example

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.automation.YoutubeAutomation
import com.example.automation.engine.ExecutionEngine
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.DeviceContext
import com.example.automation.verification.YoutubeVerifier
import com.example.network.FunctionCall
import com.example.network.ToolDispatcher
import com.example.service.JarvisAccessibilityService
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YoutubeDeviceE2ETest {

    private lateinit var context: Context
    private val TAG = "YoutubeDeviceE2E"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        JarvisAccessibilityService.testRootProvider = { uiAutomation.rootInActiveWindow }
        JarvisAccessibilityService.testWindowsProvider = { uiAutomation.windows }
        Log.d(TAG, "=== SetUp for YoutubeDeviceE2ETest with UiAutomation bridge ===")
    }

    @Test
    fun test1_E2E_PlayBelieverOnYouTube() {
        runBlocking {
            Log.d(TAG, ">>> STARTING TEST 1: 'Play Believer on YouTube' <<<")
            val utterance = "Play Believer on YouTube"
            
            // 1. Tool Dispatching
            val fc = FunctionCall(name = "open_app", args = JsonObject().apply { addProperty("app_name", "YouTube") })
            val dispatchedJsonStr = ToolDispatcher.dispatch(fc, utterance)
            val dispatchedJson = JSONObject(dispatchedJsonStr)
            
            assertEquals("YOUTUBE_SEARCH", dispatchedJson.getString("action"))
            assertEquals("believer", dispatchedJson.getString("query"))
            
            // 2. Direct Automation Execution
            val automationSuccess = YoutubeAutomation.searchAndPlay(context, "believer")
            assertTrue("YoutubeAutomation.searchAndPlay('believer') must succeed on device", automationSuccess)

            // Let video start playing
            delay(3000)

            // 3. Verifier checks device context
            val verifier = YoutubeVerifier()
            val request = ExecutionRequest(
                action = "YOUTUBE_SEARCH",
                arguments = JsonObject().apply { addProperty("query", "believer") }
            )
            val result = com.example.automation.tools.ToolResult(success = true, toolName = "youtube")
            val currentDeviceContext = DeviceContext.capture(context)
            val verificationResult = verifier.verify(context, request, result, currentDeviceContext)

            Log.d(TAG, "Test 1 Verification: success=${verificationResult.success}, reason=${verificationResult.reason}")
            assertTrue("Verification must confirm playback controls/active video on screen", verificationResult.success)
            assertNull("Reason should be null on successful verification", verificationResult.reason)
            Log.d(TAG, ">>> TEST 1 PASSED SUCCESSFULLY <<<")
        }
    }

    @Test
    fun test2_E2E_QueryParsingVariations() {
        runBlocking {
            Log.d(TAG, ">>> STARTING TEST 2: Query Parsing & Telugu Routing <<<")
            
            // 1. Coldplay
            val fcColdplay = FunctionCall(name = "open_app", args = JsonObject().apply { addProperty("app_name", "YouTube") })
            val resColdplay = JSONObject(ToolDispatcher.dispatch(fcColdplay, "Play Coldplay songs on YouTube"))
            assertEquals("YOUTUBE_SEARCH", resColdplay.getString("action"))
            assertEquals("coldplay songs", resColdplay.getString("query"))

            // 2. Playlist 90s
            val resPlaylist = JSONObject(ToolDispatcher.dispatch(fcColdplay, "Play playlist 90s on YouTube"))
            assertEquals("YOUTUBE_SEARCH", resPlaylist.getString("action"))
            assertEquals("playlist 90s", resPlaylist.getString("query"))

            // 3. Telugu "యూట్యూబ్లో believer ప్లే చేయి"
            val fcTelugu = FunctionCall(name = "open_app", args = JsonObject().apply { addProperty("app_name", "యూట్యూబ్") })
            val resTelugu = JSONObject(ToolDispatcher.dispatch(fcTelugu, "యూట్యూబ్లో believer ప్లే చేయి"))
            assertEquals("YOUTUBE_SEARCH", resTelugu.getString("action"))
            assertEquals("believer", resTelugu.getString("query"))

            // 4. "Open YouTube" (No search trigger)
            val resOpen = JSONObject(ToolDispatcher.dispatch(fcColdplay, "Open YouTube"))
            assertEquals("OPEN_APP", resOpen.getString("action"))
            assertEquals("YouTube", resOpen.getString("app"))

            Log.d(TAG, ">>> TEST 2 PASSED SUCCESSFULLY <<<")
        }
    }

    @Test
    fun test3_E2E_OpenYouTube() {
        runBlocking {
            Log.d(TAG, ">>> STARTING TEST 3: 'Open YouTube' via Tool Execution <<<")
            val tool = com.example.automation.tools.YoutubeTool()
            val request = ExecutionRequest(action = "OPEN_YOUTUBE")
            
            val result = tool.execute(context, request)
            assertTrue("OPEN_YOUTUBE tool execution must succeed", result.success)

            var snapshot = DeviceContext.capture(context)
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 4000L && snapshot.ui.packageName != "com.google.android.youtube") {
                delay(300)
                snapshot = DeviceContext.capture(context)
            }

            val verifier = YoutubeVerifier()
            val verification = verifier.verify(context, request, result, snapshot)
            assertTrue("OPEN_YOUTUBE verification must succeed when YouTube is foreground", verification.success)
            Log.d(TAG, ">>> TEST 3 PASSED SUCCESSFULLY <<<")
        }
    }

    @Test
    fun test4_E2E_YoutubeVerifierRejectsSearchResultsWithoutPlayback() {
        val verifier = YoutubeVerifier()
        val request = ExecutionRequest(
            action = "YOUTUBE_SEARCH",
            arguments = JsonObject().apply { addProperty("query", "Believer") }
        )
        val result = com.example.automation.tools.ToolResult(success = true, toolName = "youtube")
        
        // Dummy snapshot where YouTube package is present but only search cards exist
        val dummySearchOnlySnapshot = DeviceContext(
            ui = com.example.automation.verification.UiSnapshot(
                packageName = "com.google.android.youtube",
                nodes = listOf(
                    com.example.automation.verification.UiNode(
                        id = "com.google.android.youtube:id/search_query",
                        text = "Believer"
                    )
                )
            )
        )
        val verification = verifier.verify(context, request, result, dummySearchOnlySnapshot)
        assertFalse("Verifier must reject search results screen when playback is not confirmed", verification.success)
        assertTrue("Retry must be recommended on search result false positives", verification.retryRecommended)
        assertNotNull(verification.reason)
    }
}
