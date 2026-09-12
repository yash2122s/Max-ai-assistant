package com.example.voice.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.ExecutionState
import com.example.automation.event.AutomationEvent
import com.example.automation.event.AutomationEventBus
import com.example.automation.tools.DiagnosticsTool
import com.example.automation.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * P0-1 regression: voice tool execution MUST converge on the canonical
 * ExecutionEngine spine (ActionDispatcher -> ExecutionEngine). The previous
 * implementation resolved a tool and called tool.execute() directly, which
 * skipped interpolation/retry/verification/timeout/metrics/events.
 *
 * Proof mechanism (observable behavior, no production changes):
 * ExecutionEngine publishes AutomationEventBus lifecycle events —
 * ExecutionStarted / StateChanged / ExecutionFinished. A direct
 * tool.execute() bypass publishes NONE of them. This test dispatches a
 * voice tool call through the real AssistantToolRegistry + AuthorizationManager
 * (authorization is NOT bypassed: MAX_DIAGNOSTICS passes the default
 * permission branch and the ANDROID_API execution policy) and asserts those
 * canonical lifecycle events are emitted and the toolResponse contract
 * {status: success|error} is preserved.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceExecutionConvergenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ToolRegistry.resetForTests()
        ToolRegistry.register(DiagnosticsTool())
    }

    @After
    fun tearDown() {
        ToolRegistry.resetForTests()
    }

    @Test
    fun `voice dispatched tool emits canonical ExecutionEngine lifecycle events`() {
        val latch = CountDownLatch(1)
        val lifecycleStarted = AtomicBoolean(false)
        val succeededState = AtomicBoolean(false)

        // Real engine-side effects only ExecutionEngine publishes. A direct
        // tool.execute() bypass would never produce these events.
        val subscription = AutomationEventBus.events.collectInBackground { event ->
            when (event) {
                is AutomationEvent.ExecutionStarted -> lifecycleStarted.set(true)
                is AutomationEvent.StateChanged -> if (event.newState == ExecutionState.SUCCEEDED) succeededState.set(true)
                is AutomationEvent.ExecutionFinished -> if (event.success) latch.countDown()
                else -> Unit
            }
        }

        // Real production constructor — authorization runs normally.
        val authManager = AuthorizationManager(context, ExecutionPolicy(CapabilityProvider(context)))
        val registry = AssistantToolRegistry(context, authManager)

        val callbackLatch = CountDownLatch(1)
        var resultJson: String? = null
        val args = JSONObject().apply { put("action", "MAX_DIAGNOSTICS") }
        registry.executeToolAsync("MAX_DIAGNOSTICS", args) { json ->
            resultJson = json
            callbackLatch.countDown()
        }

        assertTrue("ExecutionFinished event never emitted", latch.await(10, TimeUnit.SECONDS))
        assertTrue("Tool callback never fired", callbackLatch.await(10, TimeUnit.SECONDS))
        assertTrue(
            "ExecutionEngine lifecycle events were NOT emitted - voice tool bypassed the engine (direct tool.execute)",
            lifecycleStarted.get()
        )
        assertTrue("ExecutionEngine never reached SUCCEEDED", succeededState.get())

        val parsed = JSONObject(resultJson ?: "{}")
        assertEquals("success", parsed.optString("status"))

        subscription.cancel()
    }

    @Test
    fun `voice dispatcher handles tool without action key via normalization`() {
        val latch = CountDownLatch(1)
        val authManager = AuthorizationManager(context, ExecutionPolicy(CapabilityProvider(context)))
        val registry = AssistantToolRegistry(context, authManager)

        var resultJson: String? = null
        // Legacy Live function-call shape: no "action" key (normalized by ActionDispatcher).
        val args = JSONObject()
        registry.executeToolAsync("MAX_DIAGNOSTICS", args) { json ->
            resultJson = json
            latch.countDown()
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        val parsed = JSONObject(resultJson)
        assertEquals("success", parsed.optString("status"))
    }
}

/** In-process SharedFlow collector on a background dispatcher. */
private fun SharedFlow<AutomationEvent>.collectInBackground(
    onEvent: (AutomationEvent) -> Unit
): Job {
    return CoroutineScope(Dispatchers.Default).launch {
        this@collectInBackground.collect { onEvent(it) }
    }
}