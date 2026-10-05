package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.*
import com.example.automation.engine.execution.LongTaskOrchestrator
import com.example.automation.engine.truth.ExecutionTruthCoordinator
import com.example.automation.state.ActionStep
import com.example.automation.state.WorldStateManager
import com.example.automation.tools.*
import com.example.automation.verification.*
import com.example.core.lifecycle.MaxRuntimeManager
import com.example.data.local.AppDatabase
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class BasicExecutionRegressionTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        MaxRuntimeManager.setGlobalEnabled(context, true)
        GoalPlanner.clearGoal()
        WorldStateManager.reset()
        ToolRegistry.resetForTests()
        VerificationRegistry.resetForTests()
        ExecutionTruthCoordinator.clearAllExecutions()
        com.example.service.JarvisAccessibilityService.testRootProvider = null
        org.robolectric.shadows.ShadowCameraManager.reset()
        com.example.knowledge.apps.InstalledAppsRepository.setCachedAppsForTesting(listOf(
            com.example.data.local.InstalledApp("com.google.android.youtube", "YouTube"),
            com.example.data.local.InstalledApp("com.whatsapp", "WhatsApp")
        ))
    }

    @After fun cleanup() {
        com.example.service.JarvisAccessibilityService.testRootProvider = null
        TaskCancellationCoordinator.unregisterActiveTask()
        GoalPlanner.clearGoal()
    }

    @Test fun rejectedPlanDoesNotLeaveGlobalGoalRunning() = runBlocking {
        GoalPlanner.initializeGoalPlan("Turn on flashlight", listOf(ActionStep("s1", "FLASHLIGHT_ON", dependsOn = listOf("missing"))))
        val result = GoalPlanner.executePlan(context)
        assertFalse(result.success)
        assertFalse("A rejected plan must release the goal", GoalPlanner.isGoalActive())
    }

    @Test fun cancellationBeforeDispatchNeverInvokesTool() = runBlocking {
        var calls = 0
        register("FLASHLIGHT_ON") { calls++; ToolResult(true, "fake", verificationRequired = false) }
        val result = ExecutionEngine.execute(context, ExecutionRequest(action = "FLASHLIGHT_ON", cancellationToken = CancellationToken().apply { cancel() }))
        assertFalse(result.success)
        assertEquals("Cancelled request reached Android executor", 0, calls)
        assertEquals("CANCELLED", ExecutionTruthCoordinator.getLastExecution()?.status)
    }

    @Test fun missingToolRecordsTerminalTruth() = runBlocking {
        val result = ExecutionEngine.execute(context, ExecutionRequest(action = "MISSING"))
        assertEquals("TOOL_NOT_FOUND", result.errorCode)
        assertEquals("FAILED", ExecutionTruthCoordinator.getLastExecution()?.status)
    }

    @Test fun cancellationDuringWaitPersistsCancelledAndSkipsNextStep() = runBlocking {
        ToolRegistry.register(WaitTool())
        var calls = 0
        register("FLASHLIGHT_ON") { calls++; ToolResult(true, "fake", verificationRequired = false) }
        val token = CancellationToken()
        val job = async {
            LongTaskOrchestrator(context).executePlan(context, "Wait 20 seconds then turn on flashlight", listOf(
                ActionStep("wait", "WAIT", targetText = "20 seconds"),
                ActionStep("next", "FLASHLIGHT_ON", dependsOn = listOf("wait"))
            ), token)
        }
        delay(300)
        token.cancel()
        val result = withTimeout(5000) { job.await() }
        assertTrue("Cancellation must not enter failure recovery", result.isCancelled)
        assertEquals(0, calls)
        val task = AppDatabase.getDatabase(context).agentTaskDao().getAllTasks().single { it.goal == "Wait 20 seconds then turn on flashlight" }
        assertEquals("CANCELLED", task.status)
        assertFalse(TaskCancellationCoordinator.isTaskActive())
    }

    @Test fun genericSuccessClaimWithoutExecutionTruthIsRejected() {
        assertNotEquals("Flashlight turned on successfully.", ExecutionTruthCoordinator.validateOrCorrectAssistantResponse("Flashlight turned on successfully."))
    }

    @Test fun flashlightRequiresObservedTorchState() = runBlocking {
        val result = FlashlightTool().execute(context, ExecutionRequest(action = "FLASHLIGHT_ON"))
        assertFalse("Torch dispatch acceptance cannot count as verified device state", result.isVerifiedSuccess)
    }

    @Test fun flashlightOnExecutesAndVerifiesCallback() = runBlocking { torch(true) }
    @Test fun flashlightOffExecutesAndVerifiesCallback() = runBlocking { torch(false) }

    private suspend fun torch(enabled: Boolean) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val shadow = org.robolectric.Shadows.shadowOf(manager)
        val front = org.robolectric.shadows.ShadowCameraCharacteristics.newCameraCharacteristics()
        org.robolectric.Shadows.shadowOf(front).set(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE, false)
        shadow.addCamera("front", front)
        val rear = org.robolectric.shadows.ShadowCameraCharacteristics.newCameraCharacteristics()
        org.robolectric.Shadows.shadowOf(rear).set(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE, true)
        shadow.addCamera("rear", rear)
        ToolRegistry.register(FlashlightTool())
        val result = GoalPlanner.planAndExecute(context, "Turn ${if (enabled) "on" else "off"} flashlight")
        assertTrue("${result.errorMessage}; ${result.stepResults}", result.success)
        assertEquals(enabled, shadow.getTorchMode("rear"))
        assertTrue(result.stepResults.single().verification?.reason?.contains("TorchCallback") == true)
        assertEquals("COMPLETED", ExecutionTruthCoordinator.getLastExecution()?.status)
        assertFalse(GoalPlanner.isGoalActive())
        assertFalse(TaskCancellationCoordinator.isTaskActive())
        printTrace("Turn ${if (enabled) "on" else "off"} flashlight", result.stepResults.single(),
            "CameraManager.setTorchMode; ShadowCameraManager rear=$enabled", "flashlight")
    }

    @Test fun openYouTubeReachesAndroidLaunchAndVerifiedTerminal() = runBlocking { open("YouTube", "com.google.android.youtube") }
    @Test fun openWhatsAppReachesAndroidLaunchAndVerifiedTerminal() = runBlocking { open("WhatsApp", "com.whatsapp") }

    private suspend fun open(app: String, pkg: String) {
        val component = android.content.ComponentName(pkg, "$pkg.Main")
        org.robolectric.Shadows.shadowOf(context.packageManager).apply {
            addActivityIfNotPresent(component)
            addIntentFilterForActivity(component, android.content.IntentFilter(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            })
        }
        val root = android.view.accessibility.AccessibilityNodeInfo.obtain().apply { packageName = pkg }
        com.example.service.JarvisAccessibilityService.testRootProvider = { root }
        ToolRegistry.register(OpenAppTool(com.example.automation.actions.OpenAppAction()))
        VerificationRegistry.register(OpenAppVerifier())
        val result = GoalPlanner.planAndExecute(context, "Open $app")
        assertTrue("${result.errorMessage}; ${result.stepResults}", result.success)
        val launched = org.robolectric.Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull("No Android activity launch", launched)
        assertEquals(pkg, launched.component?.packageName ?: launched.`package`)
        assertTrue(launched.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(result.stepResults.single().verification?.reason?.contains(pkg) == true)
        assertFalse(GoalPlanner.isGoalActive())
        val truth = ExecutionTruthCoordinator.getLastExecution()!!
        assertNotNull(truth.taskId)
        assertNotNull(truth.stepId)
        assertEquals("COMPLETED", AppDatabase.getDatabase(context).agentTaskDao().getTask(truth.taskId!!)!!.status)
        printTrace("Open $app", result.stepResults.single(), "Context.startActivity; ShadowApplication package=$pkg NEW_TASK=true", "open_app")
    }

    @Test fun simpleSuccessPersistsAndClearsActivity() = runBlocking {
        register("FLASHLIGHT_ON") { ToolResult(true, "fake", verificationRequired = false) }
        val result = GoalPlanner.planAndExecute(context, "Turn on flashlight")
        assertTrue(result.success)
        val truth = ExecutionTruthCoordinator.getLastExecution()!!
        val task = AppDatabase.getDatabase(context).agentTaskDao().getTask(truth.taskId!!)!!
        assertEquals("COMPLETED", task.status)
        assertFalse(TaskCancellationCoordinator.isTaskActive())
    }

    @Test fun simpleFailurePersistsFailed() = runBlocking {
        register("FLASHLIGHT_ON") { ToolResult.failure("fake", "DEVICE_REJECTED", "Device rejected request") }
        val result = GoalPlanner.planAndExecute(context, "Turn on flashlight")
        assertFalse(result.success)
        assertEquals("FAILED", ExecutionTruthCoordinator.getLastExecution()?.status)
        assertEquals("FAILED", lastTaskStatus())
    }

    @Test fun toolExceptionProducesTerminalFailed() = runBlocking {
        register("FLASHLIGHT_ON") { throw IllegalStateException("hardware exception") }
        val result = GoalPlanner.planAndExecute(context, "Turn on flashlight")
        assertFalse(result.success)
        assertTrue(result.errorMessage?.contains("hardware exception") == true)
        assertEquals("FAILED", lastTaskStatus())
    }

    @Test fun requiredVerificationFailureIsUnverified() = runBlocking {
        register("FLASHLIGHT_ON") { ToolResult(true, "fake", verificationRequired = true) }
        val result = GoalPlanner.planAndExecute(context, "Turn on flashlight")
        assertFalse(result.success)
        assertEquals("VERIFICATION_FAILED", result.stepResults.single().errorCode)
        assertEquals("UNVERIFIED", lastTaskStatus())
        assertTrue(ExecutionTruthCoordinator.getLastExecution()!!.executorSuccess)
        assertFalse(ExecutionTruthCoordinator.getLastExecution()!!.verifiedSuccess)
    }

    @Test fun simpleTimeoutHasTerminalOutcome() = runBlocking {
        register("FLASHLIGHT_ON") { awaitCancellation() }
        val result = withTimeout(35_000L) { GoalPlanner.planAndExecute(context, "Turn on flashlight") }
        assertFalse(result.success)
        assertEquals("EXECUTION_TIMEOUT", result.stepResults.single().errorCode)
        assertEquals("UNVERIFIED", lastTaskStatus())
        assertFalse(TaskCancellationCoordinator.isTaskActive())
    }

    @Test fun repeatedSimpleRequestsDoNotInheritFailedOrRunningState() = runBlocking {
        var calls = 0
        register("FLASHLIGHT_ON") { calls++; ToolResult(calls > 1, "fake", verificationRequired = false) }
        val ids = mutableSetOf<String>()
        repeat(4) { index ->
            val result = GoalPlanner.planAndExecute(context, "Turn on flashlight")
            assertEquals(index > 0, result.success)
            ids += ExecutionTruthCoordinator.getLastExecution()!!.taskId!!
            assertFalse(GoalPlanner.isGoalActive())
        }
        assertEquals(4, calls)
        assertEquals(4, ids.size)
    }

    @Test fun simpleRequestCannotReplaceOrWaitForGlobalLongGoal() = runBlocking {
        GoalPlanner.initializeGoalPlan("Wait 20 seconds", listOf(ActionStep("long", "WAIT", targetText = "20 seconds")))
        register("FLASHLIGHT_ON") { ToolResult(true, "fake", verificationRequired = false) }
        val result = withTimeout(5000L) { GoalPlanner.planAndExecute(context, "Turn on flashlight") }
        assertTrue(result.success)
        assertEquals("Wait 20 seconds", WorldStateManager.getCurrentState().currentGoal)
        assertEquals("long", GoalPlanner.actionQueue.getActiveSteps().single().stepId)
    }

    @Test fun stalePersistedRunningTaskCannotBlockNewSimpleAction() = runBlocking {
        val store = com.example.automation.engine.state.AgentTaskStore(context)
        store.create("stale", "Old task", listOf(ActionStep("old", "WAIT")))
        store.recordStepStarted("stale", "old", "old-exec")
        register("FLASHLIGHT_ON") { ToolResult(true, "fake", verificationRequired = false) }
        assertTrue(withTimeout(5000L) { GoalPlanner.planAndExecute(context, "Turn on flashlight") }.success)
        assertEquals("RUNNING", store.getTask("stale")!!.status)
    }

    @Test fun longWaitRunsNextStepOnlyAfterCompletion() = runBlocking {
        ToolRegistry.register(WaitTool())
        var calls = 0
        register("FLASHLIGHT_ON") { calls++; ToolResult(true, "fake", verificationRequired = false) }
        val start = System.nanoTime()
        val result = LongTaskOrchestrator(context).executePlan(context, "Wait then turn on flashlight", listOf(
            ActionStep("wait", "WAIT", targetText = "200ms"), ActionStep("next", "FLASHLIGHT_ON", dependsOn = listOf("wait"))))
        assertTrue("${result.errorMessage}", result.success)
        assertEquals(2, result.executedStepsCount)
        assertEquals(1, calls)
        assertTrue((System.nanoTime() - start) / 1_000_000 >= 200L)
    }

    @Test fun tokenCancelledAfterPriorStepCannotExecuteNextStep() = runBlocking {
        val token = CancellationToken()
        var calls = 0
        register("FLASHLIGHT_ON") { token.cancel(); ToolResult(true, "fake", verificationRequired = false) }
        register("FLASHLIGHT_OFF") { calls++; ToolResult(true, "fake", verificationRequired = false) }
        val result = LongTaskOrchestrator(context).executePlan(context, "Turn on flashlight then turn off flashlight", listOf(
            ActionStep("first", "FLASHLIGHT_ON"), ActionStep("future", "FLASHLIGHT_OFF", dependsOn = listOf("first"))), token)
        assertTrue(result.isCancelled)
        assertEquals(0, calls)
    }

    @Test fun cancelledTaskCannotBeReplayedWithSameIdentity() = runBlocking {
        val store = com.example.automation.engine.state.AgentTaskStore(context)
        store.create("cancelled", "Turn on flashlight", listOf(ActionStep("s", "FLASHLIGHT_ON")))
        store.terminatePending("cancelled", "CANCELLED", "Stopped")
        var calls = 0
        register("FLASHLIGHT_ON") { calls++; ToolResult(true, "fake", verificationRequired = false) }
        val result = ExecutionEngine.execute(context, ExecutionRequest(action = "FLASHLIGHT_ON", taskId = "cancelled", stepId = "s"))
        assertFalse(result.success)
        assertEquals(0, calls)
        assertEquals("CANCELLED", store.getTask("cancelled")!!.status)
    }

    @Test fun explicitWaitIsNotInsertedIntoSimplePlans() {
        for (command in listOf("Turn on flashlight", "Turn off flashlight", "Open YouTube", "Open WhatsApp")) {
            val steps = GoalPlanner.resolveFastPathPlan(command, context)!!
            assertTrue(GoalPlanner.isSimplePlan(steps))
            assertFalse(steps.any { it.actionType == "WAIT" })
        }
        val long = GoalPlanner.resolveFastPathPlan("Open WhatsApp, wait 10 seconds, open YouTube, wait 10 seconds.", context)!!
        assertEquals(listOf("OPEN_APP", "WAIT", "OPEN_APP", "WAIT"), long.map { it.actionType })
        assertFalse(GoalPlanner.isSimplePlan(long))
    }

    @Test fun unrelatedSuccessCannotSatisfyScopedResponseClaim() {
        ExecutionTruthCoordinator.recordExecution(taskId = "old", executionId = "old-exec", actionName = "OPEN_APP", executorSuccess = true, verifiedSuccess = true)
        assertNotEquals("YouTube opened successfully.", ExecutionTruthCoordinator.validateOrCorrectAssistantResponse("YouTube opened successfully.", taskId = "new"))
    }

    @Test fun lateCancellationRequestCannotResurrectTerminalTask() = runBlocking {
        val store = com.example.automation.engine.state.AgentTaskStore(context)
        store.create("finished", "Completed action", listOf(ActionStep("s", "FLASHLIGHT_ON")))
        store.recordStepStarted("finished", "s", "exec")
        store.recordStepResult("finished", "s", "exec", true, true, verificationEvidence = "Observed state")
        store.deriveAndPersistTaskCompletion("finished")
        store.requestCancellation("finished", "Late STOP")
        assertEquals("COMPLETED", store.getTask("finished")!!.status)
    }

    @Test fun newLiveTurnCannotBorrowPriorSuccess() {
        ExecutionTruthCoordinator.recordExecution(executionId = "old-exec", actionName = "OPEN_APP", executorSuccess = true, verifiedSuccess = true)
        val client = com.example.network.GeminiWebSocketClient("", onMessageReceived = {}, onAudioReceived = {},
            onConnectionError = {}, onConnectionStateChanged = {}, onExecuteAutomation = { "{}" })
        try {
            client.sendText("Open WhatsApp")
            assertNotEquals("WhatsApp opened successfully.", ExecutionTruthCoordinator.validateOrCorrectAssistantResponse("WhatsApp opened successfully."))
        } finally { client.disconnect() }
    }

    @Test fun alreadyStartedCorrelatedApprovalDispatchCompletesWithoutRewinding() = runBlocking {
        val store = com.example.automation.engine.state.AgentTaskStore(context)
        val id = java.util.UUID.randomUUID()
        store.create("approved", "Turn on flashlight", listOf(ActionStep("s", "FLASHLIGHT_ON")))
        store.transitionStep("approved", "s", id.toString(), "DISPATCHING", "ExecutionEngine")
        store.transitionStep("approved", "s", id.toString(), "RUNNING", "ExecutionEngine")
        var calls = 0
        register("FLASHLIGHT_ON") { calls++; ToolResult(true, "fake", verificationRequired = false) }
        val request = ExecutionRequest(executionId = id, action = "FLASHLIGHT_ON", taskId = "approved", stepId = "s")
        assertTrue(ExecutionEngine.execute(context, request).isVerifiedSuccess)
        assertEquals(1, calls)
        assertEquals("COMPLETED", store.getTask("approved")!!.status)
        assertFalse("Completed identity cannot dispatch again", ExecutionEngine.execute(context, request).success)
        assertEquals(1, calls)
        assertEquals("COMPLETED", store.getTask("approved")!!.status)
    }

    private suspend fun lastTaskStatus(): String {
        val taskId = ExecutionTruthCoordinator.getLastExecution()!!.taskId!!
        return AppDatabase.getDatabase(context).agentTaskDao().getTask(taskId)!!.status
    }

    private suspend fun printTrace(command: String, result: ToolResult, androidApi: String, tool: String) {
        val truth = ExecutionTruthCoordinator.getLastExecution()!!
        val task = AppDatabase.getDatabase(context).agentTaskDao().getTask(truth.taskId!!)!!
        println("JVM_EXECUTION_TRACE " + org.json.JSONObject().apply {
            put("environment", "Robolectric Android API shadows; NOT physical device")
            put("command", command)
            put("taskId", truth.taskId)
            put("stepId", truth.stepId)
            put("executionId", truth.executionId)
            put("plannerOutput", com.google.gson.Gson().toJson(GoalPlanner.resolveFastPathPlan(command, context)))
            put("selectedTool", tool)
            put("selectedAction", truth.actionName)
            put("actionDispatcherReached", false)
            put("dispatchState", "DISPATCHING -> RUNNING")
            put("androidApiReached", androidApi)
            put("executorSuccess", truth.executorSuccess)
            put("verificationResult", result.verification?.reason)
            put("executionTruth", truth.status)
            put("finalTaskState", task.status)
            put("activeExecution", TaskCancellationCoordinator.isTaskActive())
            put("uiProcessing", "Not instantiated by this JVM test; must be device verified")
            put("nativeFinalResponse", result.message ?: "Action completed and verified.")
        })
    }

    private fun register(action: String, block: suspend () -> ToolResult) {
        ToolRegistry.register(object : Tool {
            override val name = "fake"
            override val supportedActions = setOf(action)
            override val retryPolicy = RetryPolicy.NoRetry
            override val capabilities = ToolCapabilities(supportsPlanner = true)
            override fun validate(request: ExecutionRequest) = true
            override suspend fun execute(context: Context, request: ExecutionRequest) = block()
        })
    }
}
