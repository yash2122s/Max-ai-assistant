package com.example.security

import android.app.KeyguardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.ExecutionRequest
import com.example.automation.engine.ExecutionSource
import com.example.automation.tools.UnlockTool
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowKeyguardManager

/**
 * Phase F: Unlock & Security-Critical Execution Gates Test Suite.
 *
 * Verifies:
 * 1. Unlock attempt != unlock success.
 * 2. Sensitive downstream actions (calls, messages, UI clicks) are blocked when keyguard is locked.
 * 3. Non-sensitive actions and UNLOCK_DEVICE are permitted.
 * 4. UnlockTool reports success ONLY when keyguard dismissal is verified.
 * 5. UnlockTool validates auto-unlock settings, PIN configuration, and accessibility state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UnlockSecurityGatesUnitTest {

    private lateinit var context: Context
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var shadowKeyguardManager: ShadowKeyguardManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowKeyguardManager = shadowOf(keyguardManager)
    }

    @Test
    fun testSensitiveActions_BlockedWhenKeyguardLocked() {
        shadowKeyguardManager.setKeyguardLocked(true)
        assertTrue(UnlockVerifier.isKeyguardLocked(context))

        val sensitiveActions = listOf(
            "CALL_PHONE",
            "SEND_WHATSAPP",
            "SEND_TELEGRAM",
            "SEND_SMS",
            "CLICK_NODE",
            "CLICK_ELEMENT",
            "TYPE_TEXT",
            "COMPUTER_USE",
            "TASK_INTENT"
        )

        for (action in sensitiveActions) {
            val result = UnlockVerifier.assertUnlockedForAction(context, action)
            assertTrue("Action $action must be blocked when keyguard is locked", result.isFailure)
            assertTrue(result.exceptionOrNull() is SecurityException)
        }
    }

    @Test
    fun testNonSensitiveActions_PermittedWhenKeyguardLocked() {
        shadowKeyguardManager.setKeyguardLocked(true)

        val nonSensitiveActions = listOf(
            "UNLOCK_DEVICE",
            "SET_VOLUME",
            "SET_RINGER_MODE",
            "NONE"
        )

        for (action in nonSensitiveActions) {
            val result = UnlockVerifier.assertUnlockedForAction(context, action)
            assertTrue("Action $action should not be blocked by keyguard", result.isSuccess)
        }
    }

    @Test
    fun testSensitiveActions_PermittedWhenKeyguardUnlocked() {
        shadowKeyguardManager.setKeyguardLocked(false)
        assertFalse(UnlockVerifier.isKeyguardLocked(context))

        val sensitiveActions = listOf(
            "CALL_PHONE",
            "SEND_WHATSAPP",
            "CLICK_NODE",
            "TYPE_TEXT",
            "COMPUTER_USE"
        )

        for (action in sensitiveActions) {
            val result = UnlockVerifier.assertUnlockedForAction(context, action)
            assertTrue("Action $action must be permitted when keyguard is unlocked", result.isSuccess)
        }
    }

    @Test
    fun testVerifyUnlocked_SucceedsWhenUnlocked() = runBlocking {
        shadowKeyguardManager.setKeyguardLocked(false)
        val verified = UnlockVerifier.verifyUnlocked(context, timeoutMs = 1000)
        assertTrue(verified)
    }

    @Test
    fun testVerifyUnlocked_FailsWhenLockedPastTimeout() = runBlocking {
        shadowKeyguardManager.setKeyguardLocked(true)
        val verified = UnlockVerifier.verifyUnlocked(context, timeoutMs = 400, pollIntervalMs = 100)
        assertFalse("Verification must fail if device remains locked past timeout", verified)
    }

    @Test
    fun testUnlockTool_AlreadyUnlocked_ReturnsSuccessImmediately() = runBlocking {
        shadowKeyguardManager.setKeyguardLocked(false)

        val settings = SecuritySettings(context)
        settings.autoUnlockEnabled = true
        settings.encryptedPin = "1234"

        val unlockTool = UnlockTool()
        val request = ExecutionRequest(action = "UNLOCK_DEVICE", arguments = JsonObject(), source = ExecutionSource.MANUAL)

        val result = unlockTool.execute(context, request)
        assertTrue(result.success)
        assertEquals("Device is already unlocked.", result.message)
    }

    @Test
    fun testUnlockTool_AutoUnlockDisabled_Fails() = runBlocking {
        shadowKeyguardManager.setKeyguardLocked(true)

        val settings = SecuritySettings(context)
        settings.autoUnlockEnabled = false

        val unlockTool = UnlockTool()
        val request = ExecutionRequest(action = "UNLOCK_DEVICE", arguments = JsonObject(), source = ExecutionSource.MANUAL)

        val result = unlockTool.execute(context, request)
        assertFalse(result.success)
        assertEquals("AUTO_UNLOCK_DISABLED", result.errorCode)
    }

    @Test
    fun testUnlockTool_PinNotSet_Fails() = runBlocking {
        shadowKeyguardManager.setKeyguardLocked(true)

        val settings = SecuritySettings(context)
        settings.autoUnlockEnabled = true
        settings.encryptedPin = ""

        val unlockTool = UnlockTool()
        val request = ExecutionRequest(action = "UNLOCK_DEVICE", arguments = JsonObject(), source = ExecutionSource.MANUAL)

        val result = unlockTool.execute(context, request)
        assertFalse(result.success)
        assertEquals("PIN_NOT_SET", result.errorCode)
    }

    @Test
    fun testUnlockTool_AccessibilityServiceOff_Fails() = runBlocking {
        shadowKeyguardManager.setKeyguardLocked(true)

        val settings = SecuritySettings(context)
        settings.autoUnlockEnabled = true
        settings.encryptedPin = "1234"

        val unlockTool = UnlockTool()
        val request = ExecutionRequest(action = "UNLOCK_DEVICE", arguments = JsonObject(), source = ExecutionSource.MANUAL)

        val result = unlockTool.execute(context, request)
        assertFalse(result.success)
        assertEquals("ACCESSIBILITY_SERVICE_OFF", result.errorCode)
    }
}
