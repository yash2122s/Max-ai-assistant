package com.example.automation

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.example.automation.state.WorldStateManager
import com.example.automation.verification.LightweightUiObserver
import com.example.automation.verification.RuntimeStepVerifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UiObservationVerificationUnitTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorldStateManager.reset()
        com.example.service.JarvisAccessibilityService.testRootProvider = null
    }

    @After
    fun tearDown() {
        com.example.service.JarvisAccessibilityService.testRootProvider = null
    }

    private fun createNode(
        text: String? = null,
        desc: String? = null,
        resId: String? = null,
        className: String = "android.widget.TextView",
        isClickable: Boolean = true,
        isEditable: Boolean = false,
        isEnabled: Boolean = true,
        isVisible: Boolean = true,
        bounds: Rect = Rect(10, 10, 100, 100)
    ): AccessibilityNodeInfo {
        return AccessibilityNodeInfo.obtain().apply {
            this.text = text
            this.contentDescription = desc
            this.viewIdResourceName = resId
            this.className = className
            this.isClickable = isClickable
            this.isEditable = isEditable
            this.isEnabled = isEnabled
            this.isVisibleToUser = isVisible
            this.setBoundsInScreen(bounds)
        }
    }

    @Test
    fun testLightweightUiObserver() {
        WorldStateManager.setForegroundApp("com.google.android.apps.maps")
        val root = createNode(text = "Search", resId = "com.google.android.apps.maps:id/search_box")

        val observation = LightweightUiObserver.observe(
            context = context,
            targetQuery = "Search",
            rootNode = root
        )

        assertEquals("com.google.android.apps.maps", observation.foregroundPackage)
        assertNotNull(observation.targetResolved)
        assertEquals("Search", observation.targetResolved?.text)
    }

    @Test
    fun testRuntimeVerifierTargetVisible() = runBlocking {
        val root = createNode(text = "Directions", isClickable = true)

        val success = RuntimeStepVerifier.verifyTargetVisible(
            context = context,
            targetQuery = "Directions",
            timeoutMs = 500L,
            rootNode = root
        )

        assertTrue(success)
    }

    @Test
    fun testRuntimeVerifierTargetNotFoundTimeout() = runBlocking {
        val root = createNode(text = "Home")

        val success = RuntimeStepVerifier.verifyTargetVisible(
            context = context,
            targetQuery = "Directions",
            timeoutMs = 300L,
            rootNode = root
        )

        assertFalse(success)
    }

    @Test
    fun testRuntimeVerifierTextEntered() = runBlocking {
        val root = createNode(text = "Palakollu to Bhimavaram", isEditable = true)

        val success = RuntimeStepVerifier.verifyTextEntered(
            context = context,
            expectedText = "Palakollu to Bhimavaram",
            timeoutMs = 500L,
            rootNode = root
        )

        assertTrue(success)
    }

    @Test
    fun testRuntimeVerifierAppForeground() = runBlocking {
        WorldStateManager.setForegroundApp("com.google.android.apps.maps")
        val root = createNode().apply { packageName = "com.google.android.apps.maps" }
        com.example.service.JarvisAccessibilityService.testRootProvider = { root }

        val success = RuntimeStepVerifier.verifyAppForeground(
            context = context,
            expectedPackage = "com.google.android.apps.maps",
            timeoutMs = 500L
        )

        assertTrue(success)
    }
}
