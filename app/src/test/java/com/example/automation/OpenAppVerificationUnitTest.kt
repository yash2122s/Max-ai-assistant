package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.actions.OpenAppAction
import com.example.automation.engine.ExecutionRequest
import com.example.automation.state.WorldStateManager
import com.example.automation.tools.OpenAppTool
import com.example.automation.tools.ToolResult
import com.example.automation.verification.DeviceContext
import com.example.automation.verification.OpenAppVerifier
import com.example.automation.verification.RetryPolicy
import com.example.automation.verification.UiSnapshot
import com.example.knowledge.apps.InstalledAppsRepository
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenAppVerificationUnitTest {

    private lateinit var context: Context
    private lateinit var verifier: OpenAppVerifier

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        verifier = OpenAppVerifier()
        WorldStateManager.setForegroundApp("")

        val testApps = listOf(
            com.example.data.local.InstalledApp(packageName = "com.google.android.youtube", appName = "YouTube"),
            com.example.data.local.InstalledApp(packageName = "com.android.settings", appName = "Settings"),
            com.example.data.local.InstalledApp(packageName = "com.android.camera", appName = "Camera")
        )
        InstalledAppsRepository.setCachedAppsForTesting(testApps)
    }

    @Test
    fun test1_ImmediateMatch_ReturnsSuccessImmediately() {
        val request = ExecutionRequest(
            action = "OPEN_APP",
            arguments = JsonObject().apply { addProperty("app_name", "YouTube") }
        )
        val initialSnapshot = DeviceContext(ui = UiSnapshot(packageName = "com.google.android.youtube"))

        val start = System.currentTimeMillis()
        val result = verifier.verify(context, request, ToolResult(success = true, toolName = "open_app"), initialSnapshot)
        val elapsed = System.currentTimeMillis() - start

        assertTrue("Verification should succeed for matching package", result.success)
        assertNull(result.reason)
        assertFalse(result.retryRecommended)
        assertTrue("Immediate match should take < 500ms, took ${elapsed}ms", elapsed < 500)
    }

    @Test
    fun test2_InitiallyEmptySnapshot_SucceedsViaWorldStateFallback() {
        val request = ExecutionRequest(
            action = "OPEN_APP",
            arguments = JsonObject().apply { addProperty("app_name", "YouTube") }
        )
        // Initial snapshot has empty package name
        val initialSnapshot = DeviceContext(ui = UiSnapshot(packageName = ""))
        // WorldStateManager was updated by window transition
        WorldStateManager.setForegroundApp("com.google.android.youtube")

        val result = verifier.verify(context, request, ToolResult(success = true, toolName = "open_app"), initialSnapshot)

        assertTrue("Verification should succeed via fallback signal", result.success)
        assertNull(result.reason)
        assertFalse(result.retryRecommended)
    }

    @Test
    fun test3_AdaptivePolling_SucceedsWhenPackageAppearsDuringTransition() {
        val request = ExecutionRequest(
            action = "OPEN_APP",
            arguments = JsonObject().apply { addProperty("app_name", "YouTube") }
        )
        val initialSnapshot = DeviceContext(ui = UiSnapshot(packageName = ""))

        // Simulate app window transition appearing after 200ms
        CoroutineScope(Dispatchers.Default).launch {
            delay(200)
            WorldStateManager.setForegroundApp("com.google.android.youtube")
        }

        val start = System.currentTimeMillis()
        val result = verifier.verify(context, request, ToolResult(success = true, toolName = "open_app"), initialSnapshot)
        val elapsed = System.currentTimeMillis() - start

        assertTrue("Verification should succeed once package appears in poll loop", result.success)
        assertNull(result.reason)
        assertTrue("Elapsed time should be bounded and finish shortly after transition (~200-800ms), was ${elapsed}ms", elapsed in 150..1500)
    }

    @Test
    fun test4_PackageNeverAppears_TimesOutCleanlyWithRealReason() {
        val request = ExecutionRequest(
            action = "OPEN_APP",
            arguments = JsonObject().apply { addProperty("app_name", "YouTube") }
        )
        val initialSnapshot = DeviceContext(ui = UiSnapshot(packageName = "com.different.app"))

        val start = System.currentTimeMillis()
        val result = verifier.verify(context, request, ToolResult(success = true, toolName = "open_app"), initialSnapshot)
        val elapsed = System.currentTimeMillis() - start

        assertFalse("Verification should fail if expected package never appears", result.success)
        assertFalse("Retry recommended should be false after bounded window expires", result.retryRecommended)
        assertTrue("Elapsed time should be near bounded window (>= 2500ms), was ${elapsed}ms", elapsed >= 2400)
        assertTrue("Failure reason should be clear: ${result.reason}", result.reason?.contains("does not match") == true || result.reason?.contains("did not enter foreground") == true)
    }

    @Test
    fun test5_UnresolvableApp_FailsImmediately() {
        val request = ExecutionRequest(
            action = "OPEN_APP",
            arguments = JsonObject().apply { addProperty("app_name", "NonExistentUnknownApp123") }
        )
        val initialSnapshot = DeviceContext(ui = UiSnapshot(packageName = ""))

        val result = verifier.verify(context, request, ToolResult(success = true, toolName = "open_app"), initialSnapshot)

        assertFalse(result.success)
        assertEquals("Could not resolve app package name for 'NonExistentUnknownApp123'", result.reason)
        assertFalse(result.retryRecommended)
    }

    @Test
    fun test6_OpenAppTool_RetryPolicyIsNoRetry_PreventsDuplicateLaunch() {
        val tool = OpenAppTool(OpenAppAction())
        assertEquals("Retry policy must be NoRetry to prevent duplicate app launches", RetryPolicy.NoRetry, tool.retryPolicy)
    }

    @Test
    fun test7_CalculatorLaunch_VerifiedSuccessfully() {
        val request = ExecutionRequest(
            action = "OPEN_APP",
            arguments = JsonObject().apply { addProperty("app_name", "Calculator") }
        )
        val snapshot = DeviceContext(ui = UiSnapshot(packageName = "com.google.android.calculator"))
        val result = verifier.verify(context, request, ToolResult(success = true, toolName = "open_app"), snapshot)
        assertTrue("Calculator launch should be verified successfully", result.success)

        // Also test alias 'calc'
        val requestCalc = ExecutionRequest(
            action = "OPEN_APP",
            arguments = JsonObject().apply { addProperty("app_name", "calc") }
        )
        val resultCalc = verifier.verify(context, requestCalc, ToolResult(success = true, toolName = "open_app"), snapshot)
        assertTrue("Calculator alias 'calc' should be verified successfully", resultCalc.success)
    }
}
