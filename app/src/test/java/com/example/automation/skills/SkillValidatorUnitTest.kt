package com.example.automation.skills

import com.example.automation.skills.model.SkillDefinition
import com.example.automation.skills.model.SkillStep
import com.example.automation.skills.parser.SkillParser
import com.example.automation.skills.validation.SkillValidator
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SkillValidatorUnitTest {

    @Test
    fun `test valid Instagram skill passes validation on android`() {
        val jsonFile = File("../shared/skills/fixtures/valid_instagram_skill.json")
        val content = if (jsonFile.exists()) jsonFile.readText() else File("shared/skills/fixtures/valid_instagram_skill.json").readText()

        val parseResult = SkillParser.parse(content)
        assertTrue(parseResult.success)
        assertNotNull(parseResult.skill)

        val validationResult = SkillValidator.validate(parseResult.skill!!, targetPlatform = "android")
        assertTrue("Validation should pass: ${validationResult.errorSummary()}", validationResult.isValid)
        assertTrue(validationResult.errors.isEmpty())
    }

    @Test
    fun `test valid Instagram skill passes validation on windows`() {
        val jsonFile = File("../shared/skills/fixtures/valid_instagram_skill.json")
        val content = if (jsonFile.exists()) jsonFile.readText() else File("shared/skills/fixtures/valid_instagram_skill.json").readText()

        val parseResult = SkillParser.parse(content)
        assertTrue(parseResult.success)

        val validationResult = SkillValidator.validate(parseResult.skill!!, targetPlatform = "windows")
        assertTrue("Validation should pass on Windows: ${validationResult.errorSummary()}", validationResult.isValid)
    }

    @Test
    fun `test cyclic skill is rejected by Kahn algorithm`() {
        val jsonFile = File("../shared/skills/fixtures/invalid_cyclic_skill.json")
        val content = if (jsonFile.exists()) jsonFile.readText() else File("shared/skills/fixtures/invalid_cyclic_skill.json").readText()

        val parseResult = SkillParser.parse(content)
        assertTrue(parseResult.success)

        val validationResult = SkillValidator.validate(parseResult.skill!!, targetPlatform = "android")
        assertFalse("Cyclic skill must be rejected", validationResult.isValid)
        assertTrue(
            "Errors should report circular dependency: ${validationResult.errorSummary()}",
            validationResult.errors.any { it.contains("Circular dependency detected") }
        )
    }

    @Test
    fun `test duplicate step ID is rejected`() {
        val skill = SkillDefinition(
            skillId = "com.max.test.duplicate",
            name = "Duplicate Test",
            description = "Test duplicate step IDs",
            allowedCapabilities = listOf("ui.click"),
            steps = listOf(
                SkillStep(stepId = "step_1", action = "ui.click", target = "Button 1", dependsOn = emptyList()),
                SkillStep(stepId = "step_1", action = "ui.click", target = "Button 2", dependsOn = emptyList())
            )
        )

        val validationResult = SkillValidator.validate(skill, targetPlatform = "android")
        assertFalse(validationResult.isValid)
        assertTrue(validationResult.errors.any { it.contains("Duplicate step_id") })
    }

    @Test
    fun `test self dependency is rejected`() {
        val skill = SkillDefinition(
            skillId = "com.max.test.self_dep",
            name = "Self Dep Test",
            description = "Test self dependency",
            allowedCapabilities = listOf("ui.click"),
            steps = listOf(
                SkillStep(stepId = "step_1", action = "ui.click", target = "Button 1", dependsOn = listOf("step_1"))
            )
        )

        val validationResult = SkillValidator.validate(skill, targetPlatform = "android")
        assertFalse(validationResult.isValid)
        assertTrue(validationResult.errors.any { it.contains("self-dependency") })
    }

    @Test
    fun `test unknown dependency is rejected`() {
        val skill = SkillDefinition(
            skillId = "com.max.test.unknown_dep",
            name = "Unknown Dep Test",
            description = "Test unknown dependency",
            allowedCapabilities = listOf("ui.click"),
            steps = listOf(
                SkillStep(stepId = "step_1", action = "ui.click", target = "Button 1", dependsOn = listOf("non_existent_step"))
            )
        )

        val validationResult = SkillValidator.validate(skill, targetPlatform = "android")
        assertFalse(validationResult.isValid)
        assertTrue(validationResult.errors.any { it.contains("unknown dependency") })
    }

    @Test
    fun `test unregistered action is rejected`() {
        val jsonFile = File("../shared/skills/fixtures/invalid_unregistered_action.json")
        val content = if (jsonFile.exists()) jsonFile.readText() else File("shared/skills/fixtures/invalid_unregistered_action.json").readText()

        val parseResult = SkillParser.parse(content)
        assertTrue(parseResult.success)

        val validationResult = SkillValidator.validate(parseResult.skill!!, targetPlatform = "android")
        assertFalse("Unregistered action must be rejected", validationResult.isValid)
        assertTrue(validationResult.errors.any { it.contains("unrecognized") || it.contains("Unrecognized") })
    }

    @Test
    fun `test tampered checksum is rejected`() {
        val jsonFile = File("../shared/skills/fixtures/invalid_checksum_tampered.json")
        val content = if (jsonFile.exists()) jsonFile.readText() else File("shared/skills/fixtures/invalid_checksum_tampered.json").readText()

        val parseResult = SkillParser.parse(content)
        assertTrue(parseResult.success)

        val validationResult = SkillValidator.validate(parseResult.skill!!, targetPlatform = "android")
        assertFalse("Tampered checksum must be rejected", validationResult.isValid)
        assertTrue(validationResult.errors.any { it.contains("Checksum mismatch") })
    }
}
