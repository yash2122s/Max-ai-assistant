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
        assertEquals(0.98f, result.confidence, 0.02f)
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

    @Test
    fun testResolveFirstResultOrdinal() {
        val root = createNode(className = "androidx.recyclerview.widget.RecyclerView")
        val item1 = createNode(text = "YouTube - First Video Title", bounds = Rect(10, 100, 500, 200))
        val item2 = createNode(text = "YouTube - Second Video Title", bounds = Rect(10, 220, 500, 320))
        org.robolectric.Shadows.shadowOf(root).addChild(item1)
        org.robolectric.Shadows.shadowOf(root).addChild(item2)

        val result = UiTargetResolver.resolve(
            query = "first result",
            rootNode = root
        )

        assertTrue(result.success)
        assertEquals(TargetResolutionMethod.SEMANTIC, result.method)
        assertEquals("YouTube - First Video Title", result.target?.text)
    }

    @Test
    fun testResolveSecondResultOrdinal() {
        val root = createNode(className = "androidx.recyclerview.widget.RecyclerView")
        val item1 = createNode(text = "Post Alpha", bounds = Rect(10, 100, 500, 200))
        val item2 = createNode(text = "Post Beta", bounds = Rect(10, 220, 500, 320))
        org.robolectric.Shadows.shadowOf(root).addChild(item1)
        org.robolectric.Shadows.shadowOf(root).addChild(item2)

        val result = UiTargetResolver.resolve(
            query = "second post",
            rootNode = root
        )

        assertTrue(result.success)
        assertEquals(TargetResolutionMethod.SEMANTIC, result.method)
        assertEquals("Post Beta", result.target?.text)
    }

    @Test
    fun testResolvePlaySemanticTarget() {
        val root = createNode(
            desc = "Play video",
            className = "android.widget.ImageView",
            isClickable = true
        )

        val result = UiTargetResolver.resolve(
            query = "Play",
            rootNode = root
        )

        assertTrue(result.success)
        assertTrue(result.confidence >= 0.85f)
        assertEquals("Play video", result.target?.contentDescription)
    }

    @Test
    fun testDirectionsSelectsExactDirectionsOverPreviewStreetView() {
        // Hierarchy has both a misleading Preview/Street View element and an exact Directions button
        val root = createNode(className = "android.widget.FrameLayout")
        val streetViewNode = createNode(
            text = "Preview route using Street view imagery and photo spheres",
            resId = "com.google.android.apps.maps:id/street_view_thumbnail",
            isClickable = true,
            bounds = Rect(0, 100, 1080, 500)
        )
        val directionsBtn = createNode(
            text = "Directions",
            resId = "com.google.android.apps.maps:id/place_details_directions_button",
            className = "android.widget.Button",
            isClickable = true,
            bounds = Rect(50, 600, 300, 700)
        )
        org.robolectric.Shadows.shadowOf(root).addChild(streetViewNode)
        org.robolectric.Shadows.shadowOf(root).addChild(directionsBtn)

        val result = UiTargetResolver.resolve(
            query = "Directions",
            rootNode = root
        )

        assertTrue("Directions resolution must succeed", result.success)
        assertNotNull(result.target)
        assertEquals("Directions", result.target?.text)
        assertEquals("com.google.android.apps.maps:id/place_details_directions_button", result.target?.resourceId)
        assertTrue("Confidence must be high", result.confidence >= 0.95f)
    }

    @Test
    fun testDirectionsRejectsPreviewWhenNoDirectionsControlExists() {
        // Only Street View / Preview exists, Directions button does NOT exist
        val root = createNode(className = "android.widget.FrameLayout")
        val streetViewNode = createNode(
            text = "Preview route using Street view imagery",
            resId = "com.google.android.apps.maps:id/street_view_thumbnail",
            isClickable = true,
            bounds = Rect(0, 100, 1080, 500)
        )
        org.robolectric.Shadows.shadowOf(root).addChild(streetViewNode)

        val result = UiTargetResolver.resolve(
            query = "Directions",
            rootNode = root
        )

        assertFalse("Resolution should fail because Preview/Street View is negative filtered", result.success)
        assertNull("Target must be null to prevent false positive clicks", result.target)
    }

    @Test
    fun testPlaySelectsPlayButtonOverPlaylists() {
        val root = createNode(className = "android.widget.FrameLayout")
        val playlistsTab = createNode(
            text = "Playlists",
            resId = "com.google.android.youtube:id/tab_playlists",
            isClickable = true,
            bounds = Rect(0, 100, 300, 200)
        )
        val playButton = createNode(
            desc = "Play video",
            resId = "com.google.android.youtube:id/player_play_pause_button",
            className = "android.widget.ImageButton",
            isClickable = true,
            bounds = Rect(400, 400, 600, 600)
        )
        org.robolectric.Shadows.shadowOf(root).addChild(playlistsTab)
        org.robolectric.Shadows.shadowOf(root).addChild(playButton)

        val result = UiTargetResolver.resolve(
            query = "Play",
            rootNode = root
        )

        assertTrue(result.success)
        assertNotNull(result.target)
        assertEquals("Play video", result.target?.contentDescription)
        assertEquals("com.google.android.youtube:id/player_play_pause_button", result.target?.resourceId)
    }

    @Test
    fun testSendSelectsSendButtonOverSharePost() {
        val root = createNode(className = "android.widget.FrameLayout")
        val shareBtn = createNode(
            text = "Share with friends",
            className = "android.widget.Button",
            isClickable = true,
            bounds = Rect(10, 100, 200, 200)
        )
        val sendBtn = createNode(
            desc = "Send",
            resId = "com.whatsapp:id/send",
            className = "android.widget.ImageButton",
            isClickable = true,
            bounds = Rect(800, 800, 1000, 1000)
        )
        org.robolectric.Shadows.shadowOf(root).addChild(shareBtn)
        org.robolectric.Shadows.shadowOf(root).addChild(sendBtn)

        val result = UiTargetResolver.resolve(
            query = "Send",
            rootNode = root
        )

        assertTrue(result.success)
        assertNotNull(result.target)
        assertEquals("Send", result.target?.contentDescription)
        assertEquals("com.whatsapp:id/send", result.target?.resourceId)
    }

    @Test
    fun testSearchSelectsSearchControlOverUnrelatedTextContainingSearch() {
        val root = createNode(className = "android.widget.FrameLayout")
        val historyWarning = createNode(
            text = "Search history is paused for your account",
            className = "android.widget.TextView",
            isClickable = false,
            bounds = Rect(10, 100, 800, 200)
        )
        val searchBox = createNode(
            desc = "Search or type URL",
            resId = "com.android.chrome:id/search_box_text",
            className = "android.widget.EditText",
            isClickable = true,
            isEditable = true,
            bounds = Rect(10, 300, 1000, 400)
        )
        org.robolectric.Shadows.shadowOf(root).addChild(historyWarning)
        org.robolectric.Shadows.shadowOf(root).addChild(searchBox)

        val result = UiTargetResolver.resolve(
            query = "Search",
            rootNode = root
        )

        assertTrue(result.success)
        assertNotNull(result.target)
        assertEquals("Search or type URL", result.target?.contentDescription)
        assertEquals("com.android.chrome:id/search_box_text", result.target?.resourceId)
    }

    @Test
    fun testNoCandidateBelowSafeConfidenceThresholdIsAutoExecuted() {
        val root = createNode(className = "android.widget.FrameLayout")
        val randomNode = createNode(
            text = "Terms and Privacy Policy",
            className = "android.widget.TextView",
            isClickable = false
        )
        org.robolectric.Shadows.shadowOf(root).addChild(randomNode)

        val result = UiTargetResolver.resolve(
            query = "Book Ticket",
            rootNode = root
        )

        assertFalse(result.success)
        assertNull(result.target)
        assertTrue(result.confidence < UiTargetResolver.SAFE_CONFIDENCE_THRESHOLD)
    }

    @Test
    fun testDirectionsRejectsPreferenceActionButtonAndSelectsDirections() {
        val root = createNode(className = "android.widget.FrameLayout")
        val optionsBtn = createNode(
            desc = "Options",
            resId = "com.google.android.apps.maps:id/directions_header_preference_action_button",
            className = "android.widget.ImageButton",
            isClickable = true,
            bounds = Rect(650, 100, 780, 200)
        )
        val directionsBtn = createNode(
            text = "Directions",
            resId = "com.google.android.apps.maps:id/place_details_directions_button",
            className = "android.widget.Button",
            isClickable = true,
            bounds = Rect(50, 600, 300, 700)
        )
        org.robolectric.Shadows.shadowOf(root).addChild(optionsBtn)
        org.robolectric.Shadows.shadowOf(root).addChild(directionsBtn)

        val result = UiTargetResolver.resolve(
            query = "Directions",
            rootNode = root
        )

        assertTrue(result.success)
        assertNotNull(result.target)
        assertEquals("Directions", result.target?.text)
        assertEquals("com.google.android.apps.maps:id/place_details_directions_button", result.target?.resourceId)
    }
}
