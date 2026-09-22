package com.example.automation.skills

import com.example.automation.skills.registry.SkillRegistry
import com.example.automation.skills.runtime.SkillResolver
import com.example.automation.skills.storage.SkillStorage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SkillRuntimeExecutionTest {

    private lateinit var tempDir: File
    private lateinit var storage: SkillStorage
    private lateinit var registry: SkillRegistry
    private lateinit var resolver: SkillResolver

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("max_runtime_test").toFile()
        storage = SkillStorage(tempDir)
        registry = SkillRegistry(storage)
        resolver = SkillResolver(registry)

        val jsonFile = File("../shared/skills/fixtures/instagram_search_chat.json")
        val content = if (jsonFile.exists()) jsonFile.readText() else File("shared/skills/fixtures/instagram_search_chat.json").readText()
        registry.install(content, targetPlatform = "android")
    }

    @After
    fun teardown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `test resolve prompt by exact phrase and extract user slot`() {
        val prompt = "Instagram lo yaswanth.1432 chat open cheyi"
        val match = resolver.resolvePrompt(prompt)

        assertTrue("Prompt should resolve to skill", match.matched)
        assertEquals("com.max.automation.instagram.search_chat", match.skill?.skillId)
        assertEquals("yaswanth.1432", match.extractedInputs["target_user"])
    }

    @Test
    fun `test resolve prompt with for keyword slot extraction`() {
        val prompt = "Open instagram chat for rahul_sharma"
        val match = resolver.resolvePrompt(prompt)

        assertTrue(match.matched)
        assertEquals("com.max.automation.instagram.search_chat", match.skill?.skillId)
        assertEquals("rahul_sharma", match.extractedInputs["target_user"])
    }

    @Test
    fun `test resolve explicit embedded skill in prompt`() {
        val mdFile = File("../shared/skills/fixtures/instagram_search_chat.md")
        val mdContent = if (mdFile.exists()) mdFile.readText() else File("shared/skills/fixtures/instagram_search_chat.md").readText()

        val prompt = "Use this skill for user john_wick:\n$mdContent"
        val match = resolver.resolvePrompt(prompt)

        assertTrue(match.matched)
        assertEquals("EXPLICIT_EMBEDDED", match.matchType)
        assertEquals("com.max.automation.instagram.search_chat", match.skill?.skillId)
        assertEquals("john_wick", match.extractedInputs["target_user"])
    }
}
