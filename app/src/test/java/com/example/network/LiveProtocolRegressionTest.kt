package com.example.network

import com.example.network.model.LiveThinkingLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveProtocolRegressionTest {
    private fun client(
        events: MutableList<String> = mutableListOf(),
        level: LiveThinkingLevel = LiveThinkingLevel.HIGH,
        preferredModel: String = "gemini-3.8-live-extended-thinking"
    ) = GeminiWebSocketClient("", preferredModel = preferredModel, onMessageReceived = { events += it }, onAudioReceived = {},
        onConnectionError = {}, onConnectionStateChanged = {}, onExecuteAutomation = { "{}" },
        onTurnComplete = { events += "IDLE_CALLBACK" },
        onThoughtSummaryDelta = { _, full -> events += full }, thinkingLevel = level)

    @Test fun finalContentIsDeliveredBeforeIdleAndIdleIsNotRepeated() {
        val events = mutableListOf<String>()
        val client = client(events)
        client.handleIncomingMessage("""{"serverContent":{"modelTurn":{"parts":[{"text":"Final answer"}]},"turnComplete":true,"interactionStatus":"IDLE"}}""")
        client.handleIncomingMessage("""{"serverContent":{"turnComplete":true,"interactionStatus":"IDLE"}}""")
        assertEquals(listOf("Final answer", "IDLE_CALLBACK"), events)
    }

    @Test fun deepTurnWithoutStatusDoesNotPretendInteractionIsFinished() {
        val events = mutableListOf<String>()
        val client = client(events)
        client.handleIncomingMessage("""{"serverContent":{"modelTurn":{"parts":[{"text":"Still working"}]},"turnComplete":true}}""")
        client.handleIncomingMessage("""{"serverContent":{"turnComplete":true,"interaction_status":"IN_PROGRESS"}}""")
        assertEquals(listOf("Still working"), events)
        client.handleIncomingMessage("""{"serverContent":{"turnComplete":true,"interaction_status":"IDLE"}}""")
        assertEquals("IDLE_CALLBACK", events.last())
    }

    @Test fun privateThoughtNeverReachesAnyTextCallback() {
        val events = mutableListOf<String>()
        client(events).handleIncomingMessage("""{"serverContent":{"modelTurn":{"parts":[{"text":"PRIVATE_REASONING_SENTINEL","thought":true}]}}}""")
        assertEquals(listOf("Working on your request…"), events)
        assertFalse(events.joinToString().contains("PRIVATE_REASONING_SENTINEL"))
    }

    @Test fun setupUsesEveryThinkingLevelAndNonblockingDeclarations() {
        LiveThinkingLevel.entries.forEach { level ->
            val setup = JSONObject(client(level = level).buildSetupMessage()).getJSONObject("setup")
            assertEquals("models/gemini-3.8-live-extended-thinking", setup.getString("model"))
            val thinking = setup.getJSONObject("generationConfig").getJSONObject("thinkingConfig")
            assertEquals(level.name, thinking.getString("thinkingLevel"))
            assertFalse(thinking.getBoolean("includeThoughts"))
            val declarations = setup.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
            assertTrue(declarations.length() > 0)
            for (i in 0 until declarations.length()) assertEquals("NON_BLOCKING", declarations.getJSONObject(i).getString("behavior"))
            assertTrue(setup.has("sessionResumption"))
            assertTrue(setup.getJSONObject("contextWindowCompression").has("slidingWindow"))
        }
    }

    @Test fun resumptionInvalidatesOldHandleAndNeverCrossesModels() {
        val state = LiveSessionState()
        state.updateResumption("deep", SessionResumptionUpdate("handle", true))
        assertEquals("handle", state.resumptionHandle("deep"))
        assertNull(state.resumptionHandle("fast"))
        state.updateResumption("deep", SessionResumptionUpdate(resumable = false))
        assertNull(state.resumptionHandle("deep"))
    }

    @Test fun duplicateToolAndCancelledToolNeverExecuteAgain() = runTest {
        var executions = 0
        val wait = CompletableDeferred<Unit>()
        val client = GeminiWebSocketClient("", onMessageReceived = {}, onAudioReceived = {}, onConnectionError = {},
            onConnectionStateChanged = {}, onExecuteAutomation = { executions++; wait.await(); "{}" }, clientScope = this)
        val call = """{"toolCall":{"functionCalls":[{"id":"call_1","name":"get_battery_status","args":{}}]}}"""
        client.handleIncomingMessage(call)
        client.handleIncomingMessage(call)
        runCurrent()
        assertEquals(1, executions)
        client.handleIncomingMessage("""{"toolCallCancellation":{"ids":["call_1"]}}""")
        runCurrent()
        client.handleIncomingMessage(call)
        runCurrent()
        assertEquals(1, executions)
    }

    @Test fun realTaskStopCancelsRunningAndQueuedLiveCallsAndRejectsContinuation() = runTest {
        var executions = 0
        val client = GeminiWebSocketClient("", onMessageReceived = {}, onAudioReceived = {}, onConnectionError = {},
            onConnectionStateChanged = {}, onExecuteAutomation = { executions++; CompletableDeferred<Unit>().await(); "{}" }, clientScope = backgroundScope)
        try {
            client.handleIncomingMessage("""{"toolCall":{"functionCalls":[{"id":"running","name":"get_battery_status","args":{}}]}}""")
            runCurrent()
            assertEquals(1, executions)
            client.handleIncomingMessage("""{"toolCall":{"functionCalls":[{"id":"queued","name":"get_battery_status","args":{}}]}}""")
            com.example.automation.engine.TaskCancellationCoordinator.cancelActiveTask(reason = "Test STOP")
            runCurrent()
            client.handleIncomingMessage("""{"toolCall":{"functionCalls":[{"id":"future","name":"open_app","args":{"app":"YouTube"}}]}}""")
            runCurrent()
            assertEquals("STOP must cancel queue and reject future model continuation", 1, executions)
        } finally { client.disconnect() }
    }

    @Test fun cancellingQueuedCallPreventsDispatchAndDisconnectCancelsRunningCall() = runTest {
        var executions = 0
        val client = GeminiWebSocketClient("", onMessageReceived = {}, onAudioReceived = {}, onConnectionError = {},
            onConnectionStateChanged = {}, onExecuteAutomation = { executions++; CompletableDeferred<Unit>().await(); "{}" }, clientScope = backgroundScope)
        client.handleIncomingMessage("""{"toolCall":{"functionCalls":[{"id":"a","name":"get_battery_status"},{"id":"b","name":"get_battery_status"}]}}""")
        runCurrent()
        client.handleIncomingMessage("""{"toolCallCancellation":{"ids":["b"]}}""")
        runCurrent()
        assertEquals(1, executions)
        client.disconnect()
        runCurrent()
        assertEquals(1, executions)
    }
}
