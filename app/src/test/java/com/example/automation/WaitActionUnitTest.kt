package com.example.automation

import com.example.automation.actions.WaitAction
import com.example.automation.engine.CancellationToken
import com.example.automation.tools.WaitTool
import com.example.automation.engine.ExecutionRequest
import com.example.automation.engine.ExecutionSource
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WaitActionUnitTest {

    @Test
    fun testParseDurationMs() {
        assertEquals(10_000L, WaitAction.parseDurationMs("10s"))
        assertEquals(10_000L, WaitAction.parseDurationMs("10 seconds"))
        assertEquals(10_000L, WaitAction.parseDurationMs("10 sec"))
        assertEquals(10_000L, WaitAction.parseDurationMs("10 సెకన్లు"))
        assertEquals(120_000L, WaitAction.parseDurationMs("2 min"))
        assertEquals(120_000L, WaitAction.parseDurationMs("2 నిమిషాలు"))
        assertEquals(5_000L, WaitAction.parseDurationMs("5000ms"))
        assertEquals(15_000L, WaitAction.parseDurationMs("15")) // plain number <= 120 treated as seconds
        assertEquals(5_000L, WaitAction.parseDurationMs(null)) // default
        assertEquals(5_000L, WaitAction.parseDurationMs("")) // default
    }

    @Test
    fun testExecuteWaitCompletes() = runBlocking {
        val action = WaitAction()
        var progressCount = 0
        val success = action.executeWait(300L) { elapsed, remaining ->
            progressCount++
        }
        assertTrue("Wait must succeed", success)
        assertTrue("Progress should have been reported", progressCount > 0)
    }

    @Test
    fun testExecuteWaitCancelsImmediately() = runBlocking {
        val action = WaitAction()
        val token = CancellationToken()

        // Launch a coroutine to cancel the token after 150ms
        launch(Dispatchers.Default) {
            kotlinx.coroutines.delay(150L)
            token.cancel()
        }

        val startTime = System.currentTimeMillis()
        val success = action.executeWait(5000L, token)
        val elapsed = System.currentTimeMillis() - startTime

        assertFalse("Wait must return false when cancelled", success)
        assertTrue("Must have cancelled quickly (< 1000ms), took $elapsed ms", elapsed < 1000L)
    }

    @Test
    fun testWaitToolExecute() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val tool = WaitTool()
        val args = JsonObject().apply {
            addProperty("duration", "200ms")
        }
        val request = ExecutionRequest(
            action = "WAIT",
            arguments = args,
            source = ExecutionSource.PLANNER
        )

        val result = tool.execute(context, request)
        assertTrue("Tool execution must succeed", result.success)
        assertTrue("Tool must be verified success", result.isVerifiedSuccess)
        assertFalse("Verification should not be required for wait", result.verificationRequired)
    }

    @Test
    fun testWaitToolCancelled() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val tool = WaitTool()
        val token = CancellationToken()
        token.cancel() // already cancelled

        val args = JsonObject().apply {
            addProperty("duration", "5s")
        }
        val request = ExecutionRequest(
            action = "WAIT",
            arguments = args,
            source = ExecutionSource.PLANNER,
            cancellationToken = token
        )

        val result = tool.execute(context, request)
        assertFalse("Tool execution must fail when cancelled", result.success)
        assertEquals("WAIT_CANCELLED", result.errorCode)
    }
}
