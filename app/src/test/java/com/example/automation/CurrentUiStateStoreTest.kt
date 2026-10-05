package com.example.automation

import android.graphics.Rect
import com.example.automation.grounding.ScoredTargetGroundingEngine
import com.example.automation.state.CurrentUiStateStore
import com.example.automation.state.SemanticNode
import com.example.automation.state.UiScreenSnapshot
import com.example.automation.state.UiStateSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class CurrentUiStateStoreTest {

    @Before
    fun setUp() {
        CurrentUiStateStore.reset()
    }

    @Test
    fun testUpdateAndRetrieveSnapshot() {
        val node1 = SemanticNode(
            id = "node_1",
            text = "Create a new sticker",
            contentDescription = null,
            resourceId = "com.whatsapp:id/create_sticker",
            className = "android.widget.Button",
            bounds = Rect(100, 200, 300, 250),
            isClickable = true
        )
        val node2 = SemanticNode(
            id = "node_2",
            text = "Chats",
            contentDescription = "Chats tab",
            resourceId = "com.whatsapp:id/tab_chats",
            className = "android.widget.TextView",
            bounds = Rect(0, 0, 100, 50),
            isClickable = true
        )

        val snapshot = UiScreenSnapshot(
            packageName = "com.whatsapp",
            activityName = "com.whatsapp.HomeActivity",
            windowId = 10,
            nodes = listOf(node1, node2)
        )

        CurrentUiStateStore.updateSnapshot(snapshot)

        val retrieved = CurrentUiStateStore.getCurrentSnapshot()
        assertEquals("com.whatsapp", retrieved.packageName)
        assertEquals("com.whatsapp.HomeActivity", retrieved.activityName)
        assertEquals(2, retrieved.nodes.size)
        assertTrue(retrieved.stateVersion > 0L)
        assertTrue(retrieved.isFresh(maxAgeMs = 5000L, expectedPackage = "com.whatsapp"))
        assertFalse(retrieved.isStale)
    }

    @Test
    fun testFreshnessValidation_DetectsStalenessAndPackageMismatch() {
        val snapshot = UiScreenSnapshot(
            packageName = "com.whatsapp",
            activityName = "HomeActivity",
            timestamp = System.currentTimeMillis() - 3000L, // 3 seconds ago
            nodes = emptyList()
        )
        CurrentUiStateStore.updateSnapshot(snapshot)

        val retrieved = CurrentUiStateStore.getCurrentSnapshot()
        // Should be stale when maxAgeMs is 1500L
        assertFalse("Snapshot older than maxAge must not be fresh", retrieved.isFresh(maxAgeMs = 1500L))
        // Should not be fresh if wrong package expected
        assertFalse("Package mismatch must not be fresh", retrieved.isFresh(maxAgeMs = 10000L, expectedPackage = "com.google.android.youtube"))

        // Explicit markStale
        CurrentUiStateStore.markStale()
        assertTrue(CurrentUiStateStore.getCurrentSnapshot().isStale)
        assertFalse(CurrentUiStateStore.getCurrentSnapshot().isFresh(maxAgeMs = 10000L))
    }

    @Test
    fun testScoredTargetGrounding_ExactMatchScoresHigherThanPartial() {
        val searchBox = SemanticNode(
            id = "search_box",
            text = "Search here",
            contentDescription = "Search",
            resourceId = "com.google.android.apps.maps:id/search_box",
            className = "android.widget.EditText",
            bounds = Rect(20, 40, 200, 80),
            isClickable = true,
            isEditable = true
        )
        val searchResultItem = SemanticNode(
            id = "item_1",
            text = "Search result: Coffee Shop",
            contentDescription = null,
            resourceId = "com.google.android.apps.maps:id/result_title",
            className = "android.widget.TextView",
            bounds = Rect(20, 100, 200, 140),
            isClickable = true
        )

        val snapshot = UiScreenSnapshot(
            packageName = "com.google.android.apps.maps",
            nodes = listOf(searchResultItem, searchBox)
        )
        CurrentUiStateStore.updateSnapshot(snapshot)

        val result = ScoredTargetGroundingEngine.groundTarget(snapshot, "Search")
        assertNotNull(result.bestCandidate)
        // searchBox has exact contentDescription = "Search" (+90) + clickable (+15) + editable (+15)
        assertEquals("search_box", result.bestCandidate?.id)
        assertTrue("Confidence must exceed 0.85 threshold", result.confidence >= 0.85f)
    }

    @Test
    fun testScoredTargetGrounding_ArbitraryTargetStickerClick() {
        val stickerBtn = SemanticNode(
            id = "wa_sticker",
            text = "Create a new sticker",
            contentDescription = null,
            resourceId = "com.whatsapp:id/create_custom_sticker",
            className = "android.widget.Button",
            bounds = Rect(50, 150, 250, 200),
            isClickable = true
        )
        val otherBtn = SemanticNode(
            id = "wa_emoji",
            text = "Emojis",
            contentDescription = null,
            resourceId = "com.whatsapp:id/emoji_tab",
            className = "android.widget.Button",
            bounds = Rect(50, 220, 250, 270),
            isClickable = true
        )

        val snapshot = UiScreenSnapshot(
            packageName = "com.whatsapp",
            nodes = listOf(otherBtn, stickerBtn)
        )

        val result = ScoredTargetGroundingEngine.groundTarget(snapshot, "Create a new sticker")
        assertNotNull(result.bestCandidate)
        assertEquals("wa_sticker", result.bestCandidate?.id)
        assertTrue(result.confidence > 0.90f)
    }

    @Test
    fun testWaitForStateChange_SuspendsUntilPredicateSatisfied() = runBlocking {
        CurrentUiStateStore.updateSnapshot(
            UiScreenSnapshot(packageName = "com.whatsapp", activityName = "CallsActivity")
        )

        launch {
            kotlinx.coroutines.delay(80)
            CurrentUiStateStore.updateSnapshot(
                UiScreenSnapshot(packageName = "com.whatsapp", activityName = "ChatsActivity")
            )
        }

        val changed = CurrentUiStateStore.waitForStateChange(timeoutMs = 500L) {
            it.activityName == "ChatsActivity"
        }
        assertTrue("waitForStateChange must return true when predicate met", changed)
        assertEquals("ChatsActivity", CurrentUiStateStore.getCurrentSnapshot().activityName)
    }

    @Test
    fun testFuzzyGrounding_MatchesWithTypoOrSpeechError() {
        val stickerBtn = SemanticNode(
            id = "wa_sticker",
            text = "Create a new sticker",
            isClickable = true
        )
        val snapshot = UiScreenSnapshot(
            packageName = "com.whatsapp",
            nodes = listOf(stickerBtn)
        )

        // Query has typo: "Creat a new stickr" (Levenshtein similarity > 0.85)
        val result = ScoredTargetGroundingEngine.groundTarget(snapshot, "Creat a new stickr")
        assertNotNull(result.bestCandidate)
        assertEquals("wa_sticker", result.bestCandidate?.id)
        assertTrue("Fuzzy confidence should exceed 0.50", result.confidence >= 0.50f)
        assertTrue(result.reason.contains("Fuzzy match"))
    }

    @Test
    fun testWaitForPredicate_DecoupledFreshnessTtl() = runBlocking {
        // Snapshot timestamp 600ms ago
        val pastSnapshot = UiScreenSnapshot(
            packageName = "com.whatsapp",
            activityName = "ChatsActivity",
            timestamp = System.currentTimeMillis() - 600L
        )
        CurrentUiStateStore.updateSnapshot(pastSnapshot)

        // Even if timeoutMs is short (300ms), maxStateAgeMs default (2000ms) allows
        // the 600ms old state to be accepted immediately without waiting or failing
        val satisfied = CurrentUiStateStore.waitForPredicate(
            com.example.automation.state.ExpectedPredicate.ScreenEquals("ChatsActivity"),
            timeoutMs = 300L,
            maxStateAgeMs = 1500L
        )
        assertTrue("Snapshot within maxStateAgeMs must satisfy predicate immediately", satisfied)
    }
}
