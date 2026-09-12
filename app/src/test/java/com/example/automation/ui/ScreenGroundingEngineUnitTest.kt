package com.example.automation.ui

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.example.automation.core.actions.GenericAction
import com.example.automation.core.actions.GenericActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScreenGroundingEngineUnitTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun createNode(
        text: String? = null,
        desc: String? = null,
        resId: String? = null,
        className: String = "android.widget.Button",
        isClickable: Boolean = true,
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
            this.isEnabled = isEnabled
            this.isVisibleToUser = isVisible
            this.setBoundsInScreen(bounds)
        }
    }

    @Test
    fun test1_ExactTextMatch() {
        val root = createNode(text = "5", resId = "com.google.android.calculator:id/digit_5")

        val result = ScreenGroundingEngine.groundTarget("5", root)

        assertEquals(GroundingStatus.FOUND, result.status)
        assertEquals("5", result.matchedNode?.text?.toString())
        assertEquals(3, result.matchPriority)
        assertEquals(1.0f, result.confidence)
    }

    @Test
    fun test2_CaseInsensitiveMatch() {
        val root = createNode(text = "Display", resId = "android:id/title")

        val result = ScreenGroundingEngine.groundTarget("display", root)

        assertEquals(GroundingStatus.FOUND, result.status)
        assertEquals("Display", result.matchedNode?.text?.toString())
        assertEquals(4, result.matchPriority)
    }

    @Test
    fun test3_ContentDescriptionMatch() {
        val root = createNode(desc = "Search", resId = "com.google.android.youtube:id/menu_item_0")

        val result = ScreenGroundingEngine.groundTarget("Search", root)

        assertEquals(GroundingStatus.FOUND, result.status)
        assertEquals("Search", result.matchedNode?.contentDescription?.toString())
        assertEquals(2, result.matchPriority)
    }

    @Test
    fun test4_ResourceIdMatch() {
        val root = createNode(text = "Submit", resId = "com.example.app:id/btn_login_submit")

        val result = ScreenGroundingEngine.groundTarget("btn_login_submit", root)

        assertEquals(GroundingStatus.FOUND, result.status)
        assertEquals(1, result.matchPriority)
        assertEquals("btn_login_submit", result.matchedNode?.viewIdResourceName?.substringAfterLast('/'))
    }

    @Test
    fun test5_MultipleCandidateAmbiguity() {
        val root = createNode(className = "android.widget.LinearLayout", bounds = Rect(0, 0, 500, 500))
        val child1 = createNode(text = "Settings and privacy", bounds = Rect(10, 10, 200, 100))
        val child2 = createNode(text = "Settings backup", bounds = Rect(10, 110, 200, 200))

        org.robolectric.Shadows.shadowOf(root).addChild(child1)
        org.robolectric.Shadows.shadowOf(root).addChild(child2)

        val result = ScreenGroundingEngine.groundTarget("Settings", root)
        assertEquals(GroundingStatus.AMBIGUOUS, result.status)
    }

    @Test
    fun test5b_ExactMatchWinsOverAmbiguousCandidates() {
        val root = createNode(className = "android.widget.LinearLayout", bounds = Rect(0, 0, 500, 500))
        val child1 = createNode(text = "Settings", bounds = Rect(10, 10, 200, 100))
        val child2 = createNode(text = "Settings and privacy", bounds = Rect(10, 110, 200, 200))

        org.robolectric.Shadows.shadowOf(root).addChild(child1)
        org.robolectric.Shadows.shadowOf(root).addChild(child2)

        val result = ScreenGroundingEngine.groundTarget("Settings", root)
        assertEquals(GroundingStatus.FOUND, result.status)
        assertEquals("Settings", result.matchedNode?.text?.toString())
    }

    @Test
    fun test6_NoMatchingElement() {
        val root = createNode(text = "Home")

        val result = ScreenGroundingEngine.groundTarget("Profile", root)

        assertEquals(GroundingStatus.NOT_FOUND, result.status)
        assertNull(result.matchedNode)
    }

    @Test
    fun test7_DisabledElementRejection() {
        val root = createNode(text = "Submit", isEnabled = false)

        val result = ScreenGroundingEngine.groundTarget("Submit", root)

        assertEquals(GroundingStatus.NOT_FOUND, result.status)
    }

    @Test
    fun test8_NonVisibleElementRejection() {
        val root = createNode(text = "5", isVisible = false)

        val result = ScreenGroundingEngine.groundTarget("5", root)

        assertEquals(GroundingStatus.NOT_FOUND, result.status)
    }

    @Test
    fun test9_FuzzyMatching() {
        val root = createNode(text = "Sound & Vibration Settings")

        val result = ScreenGroundingEngine.groundTarget("Sound Vibration", root)

        assertEquals(GroundingStatus.FOUND, result.status)
        assertEquals(6, result.matchPriority)
        assertTrue(result.confidence >= 0.75f)
    }

    @Test
    fun test10_GenericActionClickElementResolution() {
        val root = createNode(text = "Login", resId = "com.app:id/login_button")

        val action = GenericAction(
            type = GenericActionType.CLICK_ELEMENT,
            target = "Login"
        )

        val result = ScreenGroundingEngine.groundAction(action, root)

        assertEquals(GroundingStatus.FOUND, result.status)
        assertNotNull(result.matchedNode)
        assertEquals("Login", result.metadata?.text)
    }
}
