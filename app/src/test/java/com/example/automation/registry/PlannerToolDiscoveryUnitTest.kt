package com.example.automation.registry

import android.content.Context
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.Tool
import com.example.automation.tools.ToolCapabilities
import com.example.automation.tools.ToolRegistry
import com.example.automation.tools.ToolResult
import com.example.automation.verification.RetryPolicy
import com.example.network.PromptBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlannerToolDiscoveryUnitTest {

    private class FakeTool(
        override val name: String,
        override val supportedActions: Set<String>,
        val plannerVisible: Boolean = true
    ) : Tool {
        override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
        override val capabilities: ToolCapabilities = ToolCapabilities(supportsPlanner = plannerVisible)
        override fun validate(request: ExecutionRequest): Boolean = true
        override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult =
            ToolResult.success(toolName = name)
    }

    @Before
    fun setUp() {
        ToolRegistry.resetForTests()
    }

    @After
    fun tearDown() {
        ToolRegistry.resetForTests()
    }

    // 1. registry returns planner-visible tools
    @Test
    fun `registry lists only planner-visible tools`() {
        ToolRegistry.register(FakeTool("alpha_tool", setOf("ALPHA_DO_X", "ALPHA_DO_Y")))
        ToolRegistry.register(FakeTool("beta_tool", setOf("BETA_DO_Z")))

        val visible = PlannerToolDiscovery.getPlannerVisibleTools()
        assertEquals(2, visible.size)
        assertEquals(setOf("ALPHA_DO_X", "ALPHA_DO_Y", "BETA_DO_Z").sorted(), PlannerToolDiscovery.getPlannerVisibleActionTypes())
    }

    // 2. supportsPlanner=false excluded
    @Test
    fun `non-planner tools are excluded from planner capability list`() {
        ToolRegistry.register(FakeTool("hidden_tool", setOf("HIDDEN_ACTION"), plannerVisible = false))
        ToolRegistry.register(FakeTool("shown_tool", setOf("SHOWN_ACTION")))

        val descriptors = PlannerToolDiscovery.buildDescriptors()
        assertEquals(listOf("SHOWN_ACTION"), descriptors.map { it.actionType })
        assertFalse(descriptors.any { it.actionType == "HIDDEN_ACTION" })
    }

    // 3. tool metadata serialized correctly
    @Test
    fun `descriptors carry tool metadata`() {
        ToolRegistry.register(FakeTool("alpha_tool", setOf("ALPHA_DO_X")))

        val descriptor = PlannerToolDiscovery.buildDescriptors().first()
        assertEquals("alpha_tool", descriptor.toolName)
        assertEquals("ALPHA_DO_X", descriptor.actionType)
        assertTrue(descriptor.plannerVisible)
        assertNotNull(descriptor.description)
        assertNotNull(descriptor.category)
        assertTrue(descriptor.description.isNotBlank())
    }

    // 4. parameters represented correctly (canonical catalog entries)
    @Test
    fun `parameter schemas are represented`() {
        ToolRegistry.register(FakeTool("wa_tool", setOf("SEND_WHATSAPP_MESSAGE")))
        ToolRegistry.register(FakeTool("sys_tool", setOf("SYSTEM_ACTION")))

        val descriptors = PlannerToolDiscovery.buildDescriptors()
        val wa = descriptors.first { it.actionType == "SEND_WHATSAPP_MESSAGE" }
        assertEquals(listOf("contact", "message"), wa.parameters.map { it.name })
        assertTrue(wa.parameters.first { it.name == "contact" }.required)

        val sys = descriptors.first { it.actionType == "SYSTEM_ACTION" }
        assertTrue(sys.parameters.first().allowedValues.contains("home"))
    }

    // 5 + 7. planner receives dynamic capability list; no hardcoded counts
    @Test
    fun `planning instruction is generated from the registry with no hardcoded count`() {
        ToolRegistry.register(FakeTool("alpha_tool", setOf("ALPHA_DO_X", "ALPHA_DO_Y")))
        ToolRegistry.register(FakeTool("beta_tool", setOf("BETA_DO_Z")))

        val instruction = PromptBuilder.buildPlanningSystemInstruction()
        assertTrue(instruction.contains("ALPHA_DO_X"))
        assertTrue(instruction.contains("BETA_DO_Z"))
        // Derived, not hardcoded:
        assertTrue(instruction.contains("3 actions across 2 tools"))
        assertFalse(instruction.contains("40 tools"))
        assertFalse(instruction.contains("46 tools"))
        assertFalse(instruction.contains("108 actions"))
        assertFalse(instruction.contains("46 actions"))
    }

    // 6. adding/removing a tool changes the planner schema
    @Test
    fun `adding and removing tools changes planner schema dynamically`() {
        ToolRegistry.register(FakeTool("one_tool", setOf("ONE_ACTION")))
        assertEquals(listOf("ONE_ACTION"), PlannerToolDiscovery.getPlannerVisibleActionTypes())

        ToolRegistry.register(FakeTool("two_tool", setOf("TWO_ACTION")))
        assertEquals(listOf("ONE_ACTION", "TWO_ACTION").sorted(), PlannerToolDiscovery.getPlannerVisibleActionTypes())

        ToolRegistry.resetForTests()
        ToolRegistry.register(FakeTool("three_tool", setOf("THREE_ACTION")))
        assertEquals(listOf("THREE_ACTION"), PlannerToolDiscovery.getPlannerVisibleActionTypes())
    }

    // Risk metadata classification
    @Test
    fun `risk classification is informational and stable`() {
        assertEquals(ToolRiskLevel.SAFE, PlannerToolDiscovery.classifyRisk("OPEN_APP"))
        assertEquals(ToolRiskLevel.CAUTION, PlannerToolDiscovery.classifyRisk("SEND_SMS"))
        assertEquals(ToolRiskLevel.CRITICAL, PlannerToolDiscovery.classifyRisk("RUN_ADB_COMMAND"))
        assertEquals(ToolRiskLevel.CRITICAL, PlannerToolDiscovery.classifyRisk("SEND_PAYMENT"))
        assertEquals(ToolRiskLevel.SAFE, PlannerToolDiscovery.classifyRisk("GET_BATTERY_STATUS"))
    }
}
