package com.example.automation.skills

import com.example.automation.skills.parser.SkillParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SkillParserUnitTest {

    @Test
    fun `test parse valid JSON skill`() {
        val jsonFile = File("../shared/skills/fixtures/valid_instagram_skill.json")
        val content = if (jsonFile.exists()) jsonFile.readText() else File("shared/skills/fixtures/valid_instagram_skill.json").readText()

        val result = SkillParser.parse(content)
        assertTrue("Expected parse success: ${result.errorMessage}", result.success)
        assertNotNull(result.skill)
        assertEquals("com.max.automation.instagram.search_chat", result.skill?.skillId)
        assertEquals("Instagram Direct Message Search", result.skill?.name)
        assertEquals(3, result.skill?.steps?.size)
        assertEquals("app.open", result.skill?.steps?.get(0)?.action)
        assertEquals(listOf("step_open_app"), result.skill?.steps?.get(1)?.dependsOn)
    }

    @Test
    fun `test parse valid Markdown frontmatter skill`() {
        val mdFile = File("../shared/skills/fixtures/valid_instagram_skill.md")
        val content = if (mdFile.exists()) mdFile.readText() else File("shared/skills/fixtures/valid_instagram_skill.md").readText()

        val result = SkillParser.parse(content)
        assertTrue("Expected parse success: ${result.errorMessage}", result.success)
        assertNotNull(result.skill)
        assertEquals("com.max.automation.instagram.search_chat", result.skill?.skillId)
        assertEquals("Instagram Direct Message Search", result.skill?.name)
        assertEquals(3, result.skill?.steps?.size)
        assertTrue("Documentation markdown should be preserved", result.documentationMarkdown.contains("Instagram Direct Message Search Skill"))
    }

    @Test
    fun `test parse empty skill fails`() {
        val result = SkillParser.parse("")
        assertFalse(result.success)
        assertEquals("Skill content is empty.", result.errorMessage)
    }

    @Test
    fun `test parse unsupported format fails`() {
        val result = SkillParser.parse("Hello world this is not a skill")
        assertFalse(result.success)
        assertTrue(result.errorMessage?.contains("Unsupported skill format") == true)
    }

    @Test
    fun `test parse oversized content fails`() {
        val hugeString = "{" + "a".repeat(600 * 1024) + "}"
        val result = SkillParser.parse(hugeString)
        assertFalse(result.success)
        assertTrue(result.errorMessage?.contains("exceeds maximum allowed size") == true)
    }
}
