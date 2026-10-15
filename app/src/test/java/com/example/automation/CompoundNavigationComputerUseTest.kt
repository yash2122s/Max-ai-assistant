package com.example.automation

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.example.automation.computeruse.ComputerAction
import com.example.automation.computeruse.ComputerUseEngine
import com.example.automation.computeruse.GoalClassifier
import com.example.automation.computeruse.GoalState
import com.example.automation.computeruse.TaskContext
import com.example.automation.computeruse.TaskIntentType
import com.example.automation.computeruse.TaskStatus
import com.example.automation.engine.GenericActionExecutor
import com.example.automation.engine.LiveNodeResolver
import com.example.automation.state.CurrentUiStateStore
import com.example.automation.state.SemanticNode
import com.example.automation.state.UiScreenSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CompoundNavigationComputerUseTest {

    private lateinit var context: Context

    private fun createNode(
        text: String? = null,
        desc: String? = null,
        resId: String? = null,
        className: String = "android.view.View",
        isClickable: Boolean = false,
        bounds: Rect = Rect(10, 10, 100, 100)
    ): AccessibilityNodeInfo {
        return AccessibilityNodeInfo.obtain().apply {
            this.text = text
            this.contentDescription = desc
            this.viewIdResourceName = resId
            this.className = className
            this.isClickable = isClickable
            if (isClickable) {
                this.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            }
            this.isVisibleToUser = true
            this.setBoundsInScreen(bounds)
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        CurrentUiStateStore.reset()
        GenericActionExecutor.testGestureDispatcher = null
        GenericActionExecutor.testGlobalActionDispatcher = null
        com.example.service.JarvisAccessibilityService.testRootProvider = null
        LiveNodeResolver.rootProvider = { null }
    }

    @org.junit.After
    fun tearDown() {
        CurrentUiStateStore.reset()
        GenericActionExecutor.testGestureDispatcher = null
        GenericActionExecutor.testGlobalActionDispatcher = null
        com.example.service.JarvisAccessibilityService.testRootProvider = null
        LiveNodeResolver.rootProvider = { null }
    }

    @Test
    fun testGoalClassification_OpenAppOnly() {
        val result = GoalClassifier.classifyGoal("Open WhatsApp")
        assertEquals(TaskIntentType.OPEN_APP_ONLY, result.intentType)
        assertEquals("WhatsApp", result.targetApp)
        assertTrue(result.navigationSteps.isEmpty())

        val teluguSimple = GoalClassifier.classifyGoal("WhatsApp open chey")
        assertEquals(TaskIntentType.OPEN_APP_ONLY, teluguSimple.intentType)
        assertEquals("WhatsApp", teluguSimple.targetApp)
        assertTrue(teluguSimple.navigationSteps.isEmpty())
    }

    @Test
    fun testGoalClassification_TeluguCompoundNavigation() {
        val result = GoalClassifier.classifyGoal("WhatsApp open chesi Calls nundi Chats ki vellu")
        assertEquals(TaskIntentType.COMPOUND_UI_NAVIGATION, result.intentType)
        assertEquals("WhatsApp", result.targetApp)
        assertEquals(listOf("Calls", "Chats"), result.navigationSteps.map { it.target })
    }

    @Test
    fun testGoalClassification_EnglishCompoundNavigation() {
        val result = GoalClassifier.classifyGoal("Open YouTube and go to Library")
        assertEquals(TaskIntentType.COMPOUND_UI_NAVIGATION, result.intentType)
        assertEquals("YouTube", result.targetApp)
        assertEquals(listOf("Library"), result.navigationSteps.map { it.target })
    }

    @Test
    fun testGoalClassification_SearchGoal() {
        val result = GoalClassifier.classifyGoal("Search Python tutorial on YouTube")
        assertEquals(TaskIntentType.SEARCH, result.intentType)
        assertEquals("YouTube", result.targetApp)
        assertEquals("Python tutorial", result.searchQuery)
    }

    @Test
    fun testGoalClassification_AdditionalRealDeviceCases() {
        val settings = GoalClassifier.classifyGoal("Settings open chesi Display ki vellu")
        assertEquals(TaskIntentType.COMPOUND_UI_NAVIGATION, settings.intentType)
        assertEquals("Settings", settings.targetApp)
        assertEquals(listOf("Display"), settings.navigationSteps.map { it.target })

        val chrome = GoalClassifier.classifyGoal("Chrome open chesi Downloads ki vellu")
        assertEquals(TaskIntentType.COMPOUND_UI_NAVIGATION, chrome.intentType)
        assertEquals("Chrome", chrome.targetApp)
        assertEquals(listOf("Downloads"), chrome.navigationSteps.map { it.target })
    }

    @Test
    fun testTaskContext_StepProgressTracking() {
        val classification = GoalClassifier.classifyGoal("WhatsApp open chesi Calls nundi Chats ki vellu")
        val taskContext = TaskContext(
            goal = "WhatsApp open chesi Calls nundi Chats ki vellu",
            targetApp = classification.targetApp,
            intentType = classification.intentType,
            navigationSteps = classification.navigationSteps
        )

        // Initial state before LaunchApp
        assertEquals(0, taskContext.currentStepIndex)
        assertEquals("Calls", taskContext.currentTarget)
        assertEquals(listOf("Calls", "Chats"), taskContext.remainingSteps)

        // After LaunchApp completes: must STILL point to first target (Calls)
        assertFalse("Compound navigation must not be complete after LaunchApp", taskContext.remainingSteps.isEmpty())
        assertEquals("Calls", taskContext.currentTarget)

        // Simulate Calls completed and verified
        taskContext.completedSteps.add("Calls")
        taskContext.currentStepIndex++
        assertEquals(1, taskContext.currentStepIndex)
        assertEquals("Chats", taskContext.currentTarget)
        assertEquals(listOf("Chats"), taskContext.remainingSteps)

        // Simulate Chats completed and verified
        taskContext.completedSteps.add("Chats")
        taskContext.currentStepIndex++
        assertEquals(2, taskContext.currentStepIndex)
        assertTrue(taskContext.remainingSteps.isEmpty())
    }

    @Test
    fun testCompoundNavigation_EndToEndExecutionSequence() = runBlocking {
        // Set up live node tree in LiveNodeResolver
        val callsNode = createNode(
            text = "Calls",
            resId = "com.whatsapp:id/tab_calls",
            isClickable = true,
            bounds = Rect(200, 50, 350, 100)
        )
        val chatsNode = createNode(
            text = "Chats",
            resId = "com.whatsapp:id/tab_chats",
            isClickable = true,
            bounds = Rect(50, 50, 200, 100)
        )
        val rootNode = createNode(resId = "com.whatsapp:id/root").apply {
            packageName = "com.whatsapp"
            className = "com.whatsapp.HomeActivity"
        }
        org.robolectric.Shadows.shadowOf(rootNode).addChild(callsNode)
        org.robolectric.Shadows.shadowOf(rootNode).addChild(chatsNode)

        org.robolectric.Shadows.shadowOf(callsNode).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                callsNode.isSelected = true
                chatsNode.isSelected = false
                true
            } else false
        }
        org.robolectric.Shadows.shadowOf(chatsNode).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                chatsNode.isSelected = true
                callsNode.isSelected = false
                true
            } else false
        }
        GenericActionExecutor.testGestureDispatcher = { x, y ->
            if (Rect(200, 50, 350, 100).contains(x, y)) {
                callsNode.isSelected = true
                chatsNode.isSelected = false
            } else if (Rect(50, 50, 200, 100).contains(x, y)) {
                chatsNode.isSelected = true
                callsNode.isSelected = false
            }
            true
        }

        LiveNodeResolver.rootProvider = { rootNode }
        com.example.service.JarvisAccessibilityService.testRootProvider = { rootNode }

        // Initial snapshot: WhatsApp is in foreground with Calls and Chats tabs
        val callsSemantic = SemanticNode(
            id = "com.whatsapp:id/tab_calls",
            text = "Calls",
            resourceId = "com.whatsapp:id/tab_calls",
            bounds = Rect(200, 50, 350, 100),
            isClickable = true
        )
        val chatsSemantic = SemanticNode(
            id = "com.whatsapp:id/tab_chats",
            text = "Chats",
            resourceId = "com.whatsapp:id/tab_chats",
            bounds = Rect(50, 50, 200, 100),
            isClickable = true
        )

        CurrentUiStateStore.updateSnapshot(
            UiScreenSnapshot(
                packageName = "com.whatsapp",
                activityName = "HomeActivity",
                nodes = listOf(callsSemantic, chatsSemantic),
                allVisibleTexts = listOf("Chats", "Calls")
            )
        )

        val goal = "WhatsApp open chesi Calls nundi Chats ki vellu"
        val result = ComputerUseEngine.execute(
            context = context,
            goal = goal,
            targetApp = "WhatsApp",
            maxSteps = 8
        )

        println("TEST_DEBUG: success=${result.success} steps=${result.stepsExecuted} message=${result.message} error=${result.error} phases=${result.executedPhases}")
        assertTrue("Execution must succeed: ${result.message} (error=${result.error})", result.success)
        assertEquals(TaskStatus.VERIFIED_SUCCESS, result.taskStatus)
        assertTrue("Steps executed must be >= 2, actual: ${result.stepsExecuted}", result.stepsExecuted >= 2)
        assertTrue("Must include DESTINATION_VERIFIED phase", result.executedPhases.contains(GoalState.DESTINATION_VERIFIED))
        assertNotNull(result.verifiedOutcome)
    }

    @Test
    fun testCompoundNavigation_StartsFromLauncher_FullExecutionSequence() = runBlocking {
        // Register WhatsApp in ShadowPackageManager so LaunchApp succeeds
        val shadowPm = org.robolectric.Shadows.shadowOf(context.packageManager)
        val comp = android.content.ComponentName("com.whatsapp", "com.whatsapp.HomeActivity")
        shadowPm.addActivityIfNotPresent(comp)
        shadowPm.addIntentFilterForActivity(
            comp,
            android.content.IntentFilter(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
        )

        // Set up WhatsApp UI nodes
        val callsNode = createNode(
            text = "Calls",
            resId = "com.whatsapp:id/tab_calls",
            isClickable = true,
            bounds = Rect(200, 50, 350, 100)
        )
        val chatsNode = createNode(
            text = "Chats",
            resId = "com.whatsapp:id/tab_chats",
            isClickable = true,
            bounds = Rect(50, 50, 200, 100)
        )
        val whatsappRoot = createNode(resId = "com.whatsapp:id/root").apply {
            packageName = "com.whatsapp"
            className = "com.whatsapp.HomeActivity"
        }
        org.robolectric.Shadows.shadowOf(whatsappRoot).addChild(callsNode)
        org.robolectric.Shadows.shadowOf(whatsappRoot).addChild(chatsNode)

        // Track executed actions
        var callsExecuted = false
        var chatsExecuted = false

        org.robolectric.Shadows.shadowOf(callsNode).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                callsExecuted = true
                callsNode.isSelected = true
                chatsNode.isSelected = false
                true
            } else false
        }
        org.robolectric.Shadows.shadowOf(chatsNode).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                chatsExecuted = true
                chatsNode.isSelected = true
                callsNode.isSelected = false
                true
            } else false
        }
        GenericActionExecutor.testGestureDispatcher = { x, y ->
            if (Rect(200, 50, 350, 100).contains(x, y)) {
                callsExecuted = true
                callsNode.isSelected = true
                chatsNode.isSelected = false
            } else if (Rect(50, 50, 200, 100).contains(x, y)) {
                chatsExecuted = true
                chatsNode.isSelected = true
                callsNode.isSelected = false
            }
            true
        }

        val launcherRoot = createNode(resId = "com.android.launcher:id/workspace").apply {
            packageName = "com.android.launcher3"
            className = "com.android.launcher3.Launcher"
        }

        var appLaunched = false
        LiveNodeResolver.rootProvider = {
            if (appLaunched) whatsappRoot else launcherRoot
        }
        com.example.service.JarvisAccessibilityService.testRootProvider = {
            if (appLaunched) whatsappRoot else launcherRoot
        }

        // Start on launcher
        CurrentUiStateStore.updateSnapshot(
            UiScreenSnapshot(
                packageName = "com.android.launcher3",
                activityName = "Launcher",
                nodes = emptyList(),
                allVisibleTexts = listOf("Home")
            )
        )

        // Listen for actions or switch UI when WhatsApp launch is detected
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val shadowApp = org.robolectric.Shadows.shadowOf(app)

        // Periodic/lazy check: when launch intent is started, switch tree to WhatsApp
        val dynamicRootProvider: () -> AccessibilityNodeInfo? = {
            if (shadowApp.peekNextStartedActivity() != null) {
                appLaunched = true
                CurrentUiStateStore.updateSnapshot(
                    UiScreenSnapshot(
                        packageName = "com.whatsapp",
                        activityName = "HomeActivity",
                        nodes = listOf(
                            SemanticNode(
                                id = "com.whatsapp:id/tab_calls",
                                text = "Calls",
                                resourceId = "com.whatsapp:id/tab_calls",
                                bounds = Rect(200, 50, 350, 100),
                                isClickable = true
                            ),
                            SemanticNode(
                                id = "com.whatsapp:id/tab_chats",
                                text = "Chats",
                                resourceId = "com.whatsapp:id/tab_chats",
                                bounds = Rect(50, 50, 200, 100),
                                isClickable = true
                            )
                        ),
                        allVisibleTexts = listOf("Chats", "Calls")
                    )
                )
                whatsappRoot
            } else {
                launcherRoot
            }
        }

        LiveNodeResolver.rootProvider = dynamicRootProvider
        com.example.service.JarvisAccessibilityService.testRootProvider = dynamicRootProvider

        val goal = "WhatsApp open chesi Calls nundi Chats ki vellu"
        val result = ComputerUseEngine.execute(
            context = context,
            goal = goal,
            targetApp = "WhatsApp",
            maxSteps = 8
        )

        println("INTEGRATION_TEST_DEBUG: success=${result.success} steps=${result.stepsExecuted} message=${result.message} phases=${result.executedPhases}")
        assertTrue("Task must succeed: ${result.message}", result.success)
        assertEquals(TaskStatus.VERIFIED_SUCCESS, result.taskStatus)
        assertTrue("Expected >= 3 steps (LaunchApp, Calls, Chats), got ${result.stepsExecuted}", result.stepsExecuted >= 3)
        assertTrue("Must include APP_READY phase from LaunchApp", result.executedPhases.contains(GoalState.APP_READY))
        assertTrue("Must include DESTINATION_VERIFIED phase", result.executedPhases.contains(GoalState.DESTINATION_VERIFIED))
        assertEquals("Chats open chesi verify chesanu.", result.message)
    }
}
