package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.CancellationToken
import com.example.automation.engine.GenericUiStepOrchestrator
import com.example.automation.models.UiStep
import com.example.automation.state.WorldStateManager
import com.example.core.lifecycle.MaxRuntimeManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GenericUiOrchestratorUnitTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorldStateManager.reset()
        MaxRuntimeManager.setGlobalEnabled(context, true)
        val root = android.view.accessibility.AccessibilityNodeInfo.obtain().apply {
            packageName = "com.google.android.apps.maps"
        }
        com.example.service.JarvisAccessibilityService.testRootProvider = { root }
    }

    @After
    fun tearDown() {
        com.example.service.JarvisAccessibilityService.testRootProvider = null
    }

    @Test
    fun testSuccessfulSequence() = runBlocking {
        WorldStateManager.setForegroundApp("com.google.android.apps.maps")

        val steps = listOf(
            UiStep.WaitForUi(packageName = "com.google.android.apps.maps", timeoutMs = 200L),
            UiStep.Finish(success = true, message = "Navigated")
        )

        val result = GenericUiStepOrchestrator.executeSequence(context, steps)

        assertTrue(result.success)
        assertEquals(2, result.executedCount)
        assertEquals(2, result.totalCount)
        assertFalse(result.isCancelled)
    }

    @Test
    fun testCancellationWhenMaxIsOff() = runBlocking {
        MaxRuntimeManager.setGlobalEnabled(context, false)

        val steps = listOf(
            UiStep.WaitForUi(packageName = "com.google.android.apps.maps", timeoutMs = 200L),
            UiStep.Finish(success = true)
        )

        val result = GenericUiStepOrchestrator.executeSequence(context, steps)

        assertFalse(result.success)
        assertTrue(result.isCancelled)
        assertEquals(0, result.executedCount)
    }

    @Test
    fun testExplicitCancellation() = runBlocking {
        val token = CancellationToken().apply { cancel() }

        val steps = listOf(
            UiStep.WaitForUi(packageName = "com.google.android.apps.maps", timeoutMs = 200L),
            UiStep.Finish(success = true)
        )

        val result = GenericUiStepOrchestrator.executeSequence(context, steps, cancellationToken = token)

        assertFalse(result.success)
        assertTrue(result.isCancelled)
    }

    @Test
    fun testFailedStepTermination() = runBlocking {
        // Step expecting package "com.nonexistent.app" will fail after timeout
        val steps = listOf(
            UiStep.WaitForUi(packageName = "com.nonexistent.app", timeoutMs = 100L),
            UiStep.Finish(success = true)
        )

        val result = GenericUiStepOrchestrator.executeSequence(context, steps)

        assertFalse(result.success)
        assertEquals(0, result.executedCount)
        assertEquals(2, result.totalCount)
    }
}
