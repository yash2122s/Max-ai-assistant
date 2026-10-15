package com.example.automation

import android.app.KeyguardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.ActionDispatcher
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowKeyguardManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActionDispatcherUnlockGateUnitTest {

    private lateinit var context: Context
    private lateinit var shadowKeyguardManager: ShadowKeyguardManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowKeyguardManager = shadowOf(keyguardManager)
        shadowKeyguardManager.setKeyguardLocked(false)
    }

    @After
    fun tearDown() {
        shadowKeyguardManager.setKeyguardLocked(false)
    }

    @Test
    fun `locked CALL_PHONE is blocked before execution`() = runBlocking {
        assertDeviceLockedBlocked(
            JSONObject().apply {
                put("action", "CALL_PHONE")
                put("contact", "9876543210")
            }
        )
    }

    @Test
    fun `locked TYPE_TEXT is blocked before execution`() = runBlocking {
        assertDeviceLockedBlocked(
            JSONObject().apply {
                put("action", "TYPE_TEXT")
                put("text", "hello")
            }
        )
    }

    @Test
    fun `locked UI_AGENT is blocked before execution`() = runBlocking {
        assertDeviceLockedBlocked(
            JSONObject().apply {
                put("action", "UI_AGENT")
                put("goal", "open the settings screen")
            }
        )
    }

    @Test
    fun `unlocked safe action is allowed`() = runBlocking {
        shadowKeyguardManager.setKeyguardLocked(false)

        val result = JSONObject(
            ActionDispatcher.dispatchWithResult(
                context,
                JSONObject().apply { put("action", "GET_DEVICE_STATUS") }
            )
        )

        assertTrue(result.optBoolean("success", false))
        assertEquals("success", result.optString("status"))
        assertFalse(result.has("error") && result.optJSONObject("error")?.optString("code") == "DEVICE_LOCKED")
    }

    private suspend fun assertDeviceLockedBlocked(request: JSONObject) {
        shadowKeyguardManager.setKeyguardLocked(true)

        val result = JSONObject(ActionDispatcher.dispatchWithResult(context, request))

        assertFalse(result.optBoolean("success", true))
        assertEquals("blocked", result.optString("status"))
        assertEquals("BLOCKED", result.optString("executionState"))
        assertEquals("DEVICE_LOCKED", result.optString("errorCode"))
        assertEquals("DEVICE_LOCKED", result.optJSONObject("error")?.optString("code"))
    }
}
