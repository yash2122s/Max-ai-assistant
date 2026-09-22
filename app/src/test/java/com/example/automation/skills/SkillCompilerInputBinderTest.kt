package com.example.automation.skills

import com.example.automation.skills.compiler.SkillCompiler
import com.example.automation.skills.compiler.SkillInputBinder
import com.example.automation.skills.parser.SkillParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SkillCompilerInputBinderTest {

    @Test
    fun `test bind required inputs successfully`() {
        val jsonFile = File("../shared/skills/fixtures/instagram_search_chat.json")
        val content = if (jsonFile.exists()) jsonFile.readText() else File("shared/skills/fixtures/instagram_search_chat.json").readText()
        val parseResult = SkillParser.parse(content)
        assertTrue(parseResult.success)
        val skill = parseResult.skill!!

        val provided = mapOf("target_user" to "yaswanth.1432")
        val bindResult = SkillInputBinder.bindInputs(skill, provided)

        assertTrue(bindResult.success)
        assertEquals("yaswanth.1432", bindResult.boundValues["target_user"])
    }

    @Test
    fun `test reject missing required input`() {
        val jsonFile = File("../shared/skills/fixtures/instagram_search_chat.json")
        val content = if (jsonFile.exists()) jsonFile.readText() else File("shared/skills/fixtures/instagram_search_chat.json").readText()
        val skill = SkillParser.parse(content).skill!!

        val provided = emptyMap<String, Any?>()
        val bindResult = SkillInputBinder.bindInputs(skill, provided)

        assertFalse(bindResult.success)
        assertTrue(bindResult.errorMessage?.contains("Missing mandatory input parameter: 'target_user'") == true)
    }

    @Test
    fun `test placeholder interpolation in strings and arguments`() {
        val bound = mapOf("target_user" to "john_doe", "count" to 5)
        val text = "Search for {{target_user}} and select ${'$'}{inputs.count} items"
        val interpolated = SkillInputBinder.interpolateString(text, bound)
        assertEquals("Search for john_doe and select 5 items", interpolated)

        val args = mapOf(
            "text" to "{{target_user}}",
            "nested" to mapOf("user" to "${'$'}{inputs.target_user}"),
            "list" to listOf("prefix_{{target_user}}")
        )
        val interpolatedArgs = SkillInputBinder.interpolateArguments(args, bound)
        assertEquals("john_doe", interpolatedArgs["text"])
        assertEquals("john_doe", (interpolatedArgs["nested"] as Map<*, *>)["user"])
        assertEquals("prefix_john_doe", (interpolatedArgs["list"] as List<*>)[0])
    }

    @Test
    fun `test compile skill to bound execution plan`() {
        val jsonFile = File("../shared/skills/fixtures/instagram_search_chat.json")
        val content = if (jsonFile.exists()) jsonFile.readText() else File("shared/skills/fixtures/instagram_search_chat.json").readText()
        val skill = SkillParser.parse(content).skill!!

        val boundInputs = mapOf("target_user" to "yaswanth.1432")
        val plan = SkillCompiler.compile(skill, boundInputs, targetPlatform = "android")

        assertEquals("com.max.automation.instagram.search_chat", plan.skillId)
        assertEquals("android", plan.targetPlatform)
        assertEquals(6, plan.steps.size)

        // Verify topological execution order
        assertEquals(
            listOf("step_open_app", "step_open_search", "step_search_user", "step_open_profile", "step_click_message", "step_verify_chat"),
            plan.executionOrder
        )

        // Verify step interpolation
        val searchStep = plan.steps.first { it.stepId == "step_search_user" }
        assertEquals("yaswanth.1432", searchStep.arguments["text"])
        assertEquals("yaswanth.1432", searchStep.verification?.expectedValue)
    }
}
