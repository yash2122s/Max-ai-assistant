package com.example.automation

import com.example.automation.engine.planning.TaskIntent
import com.example.automation.engine.planning.TaskPlanner
import com.example.automation.engine.state.ExpectedOutcome
import com.example.automation.models.UiStep
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TaskPlannerTest {

    private lateinit var planner: TaskPlanner

    @Before
    fun setUp() {
        planner = TaskPlanner()
    }

    @Test
    fun testReelsNavigationPlan() {
        val utterance = "Open Instagram and go to Reels"
        val intent = TaskIntent(goal = utterance)
        val task = planner.createPlan(utterance, intent)

        assertEquals(2, task.steps.size)
        assertTrue(task.steps[0].action is UiStep.OpenApp)
        assertEquals("com.instagram.android", (task.steps[0].action as UiStep.OpenApp).appName)
        assertEquals(ExpectedOutcome.PackageForeground("com.instagram.android"), task.steps[0].expectedOutcome)

        assertTrue(task.steps[1].action is UiStep.Tap)
        assertEquals("Reels", (task.steps[1].action as UiStep.Tap).target)
        assertEquals(ExpectedOutcome.ElementPresent("Reels"), task.steps[1].expectedOutcome)
    }

    @Test
    fun testChatsNavigationPlan() {
        val utterance = "Open Instagram and go to chats"
        val intent = TaskIntent(goal = utterance)
        val task = planner.createPlan(utterance, intent)

        assertEquals(2, task.steps.size)
        assertTrue(task.steps[0].action is UiStep.OpenApp)
        assertEquals("com.instagram.android", (task.steps[0].action as UiStep.OpenApp).appName)

        assertTrue(task.steps[1].action is UiStep.Tap)
        assertEquals("Messages", (task.steps[1].action as UiStep.Tap).target)
        assertEquals(ExpectedOutcome.ElementPresent("Messages"), task.steps[1].expectedOutcome)
    }

    @Test
    fun testSearchAndOpenMessagePlan_withYaswanth() {
        val utterance = "Open Instagram, search yaswanth.1432, open the first account, open Message and wait"
        val intent = TaskIntent(goal = utterance)
        val task = planner.createPlan(utterance, intent)

        assertEquals(8, task.steps.size)

        // Step 1: Open app
        assertTrue(task.steps[0].action is UiStep.OpenApp)
        assertEquals("com.instagram.android", (task.steps[0].action as UiStep.OpenApp).appName)

        // Step 2: Tap Search
        assertTrue(task.steps[1].action is UiStep.Tap)
        assertEquals("Search", (task.steps[1].action as UiStep.Tap).target)

        // Step 3: Tap Search input
        assertTrue(task.steps[2].action is UiStep.Tap)
        assertEquals("Search input", (task.steps[2].action as UiStep.Tap).target)

        // Step 4: Type username
        assertTrue(task.steps[3].action is UiStep.TypeText)
        assertEquals("yaswanth.1432", (task.steps[3].action as UiStep.TypeText).text)
        assertEquals("Search input", (task.steps[3].action as UiStep.TypeText).target)

        // Step 5: Wait for search results
        assertTrue(task.steps[4].action is UiStep.WaitForUi)
        assertEquals("yaswanth.1432", (task.steps[4].action as UiStep.WaitForUi).targetText)

        // Step 6: Tap first account (semantic ordinal, NOT coordinate)
        assertTrue(task.steps[5].action is UiStep.Tap)
        assertEquals("first account", (task.steps[5].action as UiStep.Tap).target)

        // Step 7: Tap Message
        assertTrue(task.steps[6].action is UiStep.Tap)
        assertEquals("Message", (task.steps[6].action as UiStep.Tap).target)

        // Step 8: Wait for composer (wait without sending)
        assertTrue(task.steps[7].action is UiStep.WaitForUi)
        assertEquals("Message composer", (task.steps[7].action as UiStep.WaitForUi).targetText)
    }

    @Test
    fun testSearchAndOpenMessagePlan_withDifferentUsername() {
        val utterance = "Open Instagram, search natgeo, open the first account, open Message and wait"
        val intent = TaskIntent(goal = utterance)
        val task = planner.createPlan(utterance, intent)

        assertEquals(8, task.steps.size)
        // Verify natgeo is extracted and yaswanth.1432 is NEVER used as fallback
        val typeStep = task.steps[3].action as UiStep.TypeText
        assertEquals("natgeo", typeStep.text)
        assertNotEquals("yaswanth.1432", typeStep.text)
    }

    @Test
    fun testSearchWithoutUsername_doesNotFabricateUsername() {
        val utterance = "Open Instagram and search"
        val intent = TaskIntent(goal = utterance)
        val task = planner.createPlan(utterance, intent)

        // Should only open app and tap search, never invent a username
        assertEquals(2, task.steps.size)
        assertTrue(task.steps[0].action is UiStep.OpenApp)
        assertTrue(task.steps[1].action is UiStep.Tap)
        assertEquals("Search", (task.steps[1].action as UiStep.Tap).target)
        // Verify no type text step was added
        assertFalse(task.steps.any { it.action is UiStep.TypeText })
    }

    @Test
    fun testNoMessageSend_unlessExplicitlyRequested() {
        val utterance = "Open Instagram, search yaswanth.1432, open the first account, open Message and wait"
        val intent = TaskIntent(goal = utterance)
        val task = planner.createPlan(utterance, intent)

        // No send step exists
        assertFalse(task.steps.any { (it.action as? UiStep.Tap)?.target == "Send" })

        // Now test when user explicitly requests send
        val explicitUtterance = "Open Instagram, search yaswanth.1432, open account, message saying Hello!"
        val explicitIntent = TaskIntent(
            goal = explicitUtterance,
            parameters = mapOf("username" to "yaswanth.1432", "message" to "Hello!")
        )
        val explicitTask = planner.createPlan(explicitUtterance, explicitIntent)
        assertTrue(explicitTask.steps.any { (it.action as? UiStep.Tap)?.target == "Send" })
        assertTrue(explicitTask.steps.any { (it.action as? UiStep.TypeText)?.text == "Hello!" })
    }
}
