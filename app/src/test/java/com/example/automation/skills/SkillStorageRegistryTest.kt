package com.example.automation.skills

import com.example.automation.skills.model.SkillDefinition
import com.example.automation.skills.registry.SkillRegistry
import com.example.automation.skills.storage.SkillStorage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SkillStorageRegistryTest {

    private lateinit var tempDir: File
    private lateinit var storage: SkillStorage
    private lateinit var registry: SkillRegistry

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("max_skills_test").toFile()
        storage = SkillStorage(tempDir)
        registry = SkillRegistry(storage)
    }

    @After
    fun teardown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `test sanitize skill id prevents path traversal`() {
        val dangerousId = "../../etc/passwd/malicious..id"
        val sanitized = storage.sanitizeSkillId(dangerousId)
        assertFalse(sanitized.contains("/"))
        assertFalse(sanitized.contains("\\"))
        assertEquals(".._.._etc_passwd_malicious..id", sanitized)
    }

    @Test
    fun `test install valid json skill to storage and registry`() {
        val jsonFile = File("../shared/skills/fixtures/instagram_search_chat.json")
        val content = if (jsonFile.exists()) jsonFile.readText() else File("shared/skills/fixtures/instagram_search_chat.json").readText()

        val importResult = registry.install(content, targetPlatform = "android")
        assertTrue("Install should succeed: ${importResult.errorMessage}", importResult.success)
        assertNotNull(importResult.skill)
        assertFalse(importResult.isEphemeral)

        // Verify in storage
        assertTrue(storage.contains("com.max.automation.instagram.search_chat"))
        val loaded = storage.loadSkill("com.max.automation.instagram.search_chat")
        assertNotNull(loaded)
        assertEquals("Instagram Direct Message Search", loaded?.name)

        // Verify in registry
        val fromReg = registry.get("com.max.automation.instagram.search_chat")
        assertNotNull(fromReg)
        assertEquals("com.max.automation.instagram.search_chat", fromReg?.skillId)
    }

    @Test
    fun `test install markdown frontmatter skill`() {
        val mdFile = File("../shared/skills/fixtures/instagram_search_chat.md")
        val content = if (mdFile.exists()) mdFile.readText() else File("shared/skills/fixtures/instagram_search_chat.md").readText()

        val importResult = registry.install(content, targetPlatform = "android")
        assertTrue("Install markdown skill should succeed: ${importResult.errorMessage}", importResult.success)
        assertTrue(importResult.documentationMarkdown.contains("Instagram Direct Message Search Skill"))

        val source = storage.loadRawSource("com.max.automation.instagram.search_chat")
        assertNotNull(source)
        assertTrue(source?.contains("Instagram Direct Message Search Skill") == true)
    }

    @Test
    fun `test ephemeral skill registration without filesystem persistence`() {
        val mdFile = File("../shared/skills/fixtures/instagram_search_chat.md")
        val content = if (mdFile.exists()) mdFile.readText() else File("shared/skills/fixtures/instagram_search_chat.md").readText()

        val importResult = registry.registerEphemeral(content, targetPlatform = "android")
        assertTrue("Ephemeral registration should succeed", importResult.success)
        assertTrue(importResult.isEphemeral)

        // Present in registry
        val skill = registry.get("com.max.automation.instagram.search_chat")
        assertNotNull(skill)

        // NOT present in filesystem storage
        assertFalse("Ephemeral skill must not be stored on disk", storage.contains("com.max.automation.instagram.search_chat"))

        // Clear ephemeral
        registry.clearEphemeral()
        assertNull(registry.get("com.max.automation.instagram.search_chat"))
    }

    @Test
    fun `test disable and enable skill`() {
        val jsonFile = File("../shared/skills/fixtures/instagram_search_chat.json")
        val content = if (jsonFile.exists()) jsonFile.readText() else File("shared/skills/fixtures/instagram_search_chat.json").readText()
        registry.install(content, targetPlatform = "android")

        assertTrue(registry.contains("com.max.automation.instagram.search_chat"))
        registry.disable("com.max.automation.instagram.search_chat")

        assertNull("Disabled skill should not be returned by get()", registry.get("com.max.automation.instagram.search_chat"))
        assertEquals(0, registry.listInstalled(includeDisabled = false).size)
        assertEquals(1, registry.listInstalled(includeDisabled = true).size)

        registry.enable("com.max.automation.instagram.search_chat")
        assertNotNull("Enabled skill should be returned by get()", registry.get("com.max.automation.instagram.search_chat"))
        assertTrue(registry.isSkillEnabled("com.max.automation.instagram.search_chat"))

        // Test persistence across reloads
        registry.disable("com.max.automation.instagram.search_chat")
        assertFalse(registry.isSkillEnabled("com.max.automation.instagram.search_chat"))

        // Create fresh registry pointing to same storage
        val reloadedRegistry = SkillRegistry(storage)
        assertFalse("Disabled state must persist across registry reloads", reloadedRegistry.isSkillEnabled("com.max.automation.instagram.search_chat"))
        assertEquals(0, reloadedRegistry.listInstalled(includeDisabled = false).size)
        assertEquals(1, reloadedRegistry.listInstalled(includeDisabled = true).size)
    }

    @Test
    fun `test delete skill`() {
        val jsonFile = File("../shared/skills/fixtures/instagram_search_chat.json")
        val content = if (jsonFile.exists()) jsonFile.readText() else File("shared/skills/fixtures/instagram_search_chat.json").readText()
        registry.install(content, targetPlatform = "android")

        assertTrue(registry.delete("com.max.automation.instagram.search_chat"))
        assertFalse(registry.contains("com.max.automation.instagram.search_chat"))
        assertFalse(storage.contains("com.max.automation.instagram.search_chat"))
    }
}
