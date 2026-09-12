package com.example.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.models.TargetResolutionMethod
import com.example.automation.resolver.UiTargetResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UiTargetResolverUnitTest {

    private fun createNode(
        text: String? = null,
        desc: String? = null,
        resId: String? = null,
        className: String = "android.widget.Button",
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
    fun testResolveByResourceId() {
        val root = createNode(
            text = "Search here",
            resId = "com.google.android.apps.maps:id/search_omnibox_text_box",
            isEditable = true
        )

        val result = UiTargetResolver.resolve(
            query = "search_omnibox_text_box",
            rootNode = root
        )

        assertTrue(result.success)
        assertEquals(TargetResolutionMethod.RESOURCE_ID, result.method)
        assertTrue(result.confidence >= 0.99f)
        assertNotNull(result.target)
        assertEquals("com.google.android.apps.maps:id/search_omnibox_text_box", result.target?.resourceId)
    }

    @Test
    fun testResolveByExactText() {
        val root = createNode(
            text = "Directions",
            isClickable = true
        )

        val result = UiTargetResolver.resolve(
            query = "Directions",
            rootNode = root
        )

        assertTrue(result.success)
        assertEquals(TargetResolutionMethod.TEXT, result.method)
        assertEquals(0.98f, result.confidence, 0.01f)
        assertEquals("Directions", result.target?.text)
    }

    @Test
    fun testResolveByContentDescription() {
        val root = createNode(
            desc = "Search Google Maps",
            isClickable = true
        )

        val result = UiTargetResolver.resolve(
            query = "Search Google Maps",
            rootNode = root
        )

        assertTrue(result.success)
        assertEquals(TargetResolutionMethod.CONTENT_DESCRIPTION, result.method)
        assertTrue(result.confidence >= 0.92f)
        assertEquals("Search Google Maps", result.target?.contentDescription)
    }

    @Test
    fun testResolveBySemanticAlias() {
        val root = createNode(
            resId = "com.google.android.apps.maps:id/search_button",
            isClickable = true
        )

        val result = UiTargetResolver.resolve(
            query = "Find location",
            rootNode = root
        )

        assertTrue(result.success)
        assertEquals(TargetResolutionMethod.SEMANTIC, result.method)
        assertTrue(result.confidence >= 0.88f)
    }

    @Test
    fun testResolveEditableField() {
        val root = createNode(
            className = "android.widget.EditText",
            isEditable = true
        )

        val result = UiTargetResolver.resolve(
            query = "Search bar",
            requireEditable = true,
            rootNode = root
        )

        assertTrue(result.success)
        assertEquals(TargetResolutionMethod.EDITABLE, result.method)
        assertTrue(result.confidence >= 0.86f)
    }

    @Test
    fun testResolveNotFound() {
        val root = createNode(
            text = "Welcome to Android"
        )

        val result = UiTargetResolver.resolve(
            query = "NonExistentButtonXYZ",
            rootNode = root
        )

        assertFalse(result.success)
        assertNull(result.target)
        assertEquals(0f, result.confidence, 0.001f)
    }

    @Test
    fun testSafeConfidenceThreshold() {
        assertTrue(UiTargetResolver.SAFE_CONFIDENCE_THRESHOLD >= 0.85f)
    }
}
