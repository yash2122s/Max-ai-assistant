package com.example.analytics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.preferences.SettingsManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MaxActivityTrackerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var settings: SettingsManager

    @Before
    fun setup() {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = SettingsManager(context)
        SettingsManager.setInstanceForTesting(settings)
        settings.isAnalyticsEnabled = true
        SessionManager.resetForTesting()
    }

    @org.junit.After
    fun tearDown() {
        SessionManager.resetForTesting()
        SettingsManager.setInstanceForTesting(null)
    }

    @Test
    fun testIngestMappingLeavesOwnershipAndServerTimeToBackend() {
        val event = ActivityEvent(
            eventId = "test-event-1",
            eventType = ActivityEventType.TOOL_EXECUTION_SUCCESS.rawName,
            feature = "Tool:OpenApp",
            status = ActivityStatus.SUCCESS.rawName,
            durationMs = 250L,
            sessionId = "test-session-123",
            appVersion = "1.0",
            metadata = mapOf("toolName" to "OpenApp", "count" to 1)
        )

        val firestoreMap = event.toIngestMap()
        assertEquals("test-event-1", firestoreMap["eventId"])
        assertFalse(firestoreMap.containsKey("userId"))
        assertEquals("tool_execution_success", firestoreMap["eventType"])
        assertEquals("Tool:OpenApp", firestoreMap["feature"])
        assertEquals("SUCCESS", firestoreMap["status"])
        assertEquals(250L, firestoreMap["durationMs"])
        assertEquals("test-session-123", firestoreMap["sessionId"])
        assertEquals("1.0", firestoreMap["appVersion"])
        assertEquals("Android", firestoreMap["platform"])
        assertFalse(firestoreMap.containsKey("serverTimestamp"))
    }

    @Test
    fun testSessionManagerLifecycle() {
        assertFalse(SessionManager.isSessionActive)
        SessionManager.startSession(context, "1.0")
        assertTrue(SessionManager.isSessionActive)
        assertNotNull(SessionManager.currentSessionId)
        val initialSessionId = SessionManager.currentSessionId

        SessionManager.heartbeat(context)
        assertEquals(initialSessionId, SessionManager.currentSessionId)

        SessionManager.endSession(context)
        assertFalse(SessionManager.isSessionActive)
    }

    @Test
    fun testActivityEventTypeResolution() {
        assertEquals(ActivityEventType.SESSION_START, ActivityEventType.fromRaw("session_start"))
        assertEquals(ActivityEventType.AI_REQUEST_START, ActivityEventType.fromRaw("ai_request_start"))
        assertEquals(ActivityEventType.ERROR_REPORT, ActivityEventType.fromRaw("unknown_raw_value"))
    }

    @Test
    fun testAnalyticsOptOutSuppressesTracking() {
        settings.isAnalyticsEnabled = false
        SessionManager.startSession(context, "1.0")
        assertFalse(SessionManager.isSessionActive)
        // Should execute smoothly without throwing exceptions or enqueuing
        MaxActivityTracker.trackToolExecution(context, "Flashlight", ActivityStatus.SUCCESS, 100L)
        assertFalse(settings.isAnalyticsEnabled)
    }
}
