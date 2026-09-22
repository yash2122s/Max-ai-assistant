package com.example.automation.skills

import android.app.Application
import com.example.automation.skills.registry.SkillRegistry
import com.example.automation.skills.storage.SkillStorage
import com.example.ui.skills.PlatformSupport
import com.example.ui.skills.SkillsViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SkillsImportViewModelTest {

    private lateinit var tempDir: File
    private lateinit var storage: SkillStorage
    private lateinit var registry: SkillRegistry

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("max_skills_vm_test").toFile()
        storage = SkillStorage(tempDir)
        registry = SkillRegistry(storage)
    }

    @After
    fun teardown() {
        tempDir.deleteRecursively()
    }

    private fun waitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("Condition not met within ${timeoutMs}ms", condition())
    }

    @Test
    fun `test viewmodel loads skills and maps platform support correctly`() {
        val jsonFile = File("../shared/skills/fixtures/instagram_search_chat.json")
        val content = if (jsonFile.exists()) jsonFile.readText() else File("shared/skills/fixtures/instagram_search_chat.json").readText()

        registry.install(content, targetPlatform = "android")
        val viewModel = SkillsViewModel(Application(), registry)

        viewModel.loadSkills()
        waitUntil { viewModel.uiState.value.installedSkills.isNotEmpty() }

        val state = viewModel.uiState.value
        assertEquals(1, state.installedSkills.size)

        val item = state.installedSkills[0]
        assertEquals("com.max.automation.instagram.search_chat", item.skillId)
        assertEquals("Instagram Direct Message Search", item.name)
        assertEquals(PlatformSupport.BOTH, item.platformSupport)
        assertTrue(item.isEnabled)
        assertEquals(6, item.stepCount)
        assertEquals(1, item.inputsCount)
    }

    @Test
    fun `test viewmodel toggle enable and disable`() {
        val jsonFile = File("../shared/skills/fixtures/instagram_search_chat.json")
        val content = if (jsonFile.exists()) jsonFile.readText() else File("shared/skills/fixtures/instagram_search_chat.json").readText()

        registry.install(content, targetPlatform = "android")
        val viewModel = SkillsViewModel(Application(), registry)

        viewModel.toggleSkillEnabled("com.max.automation.instagram.search_chat", false)
        waitUntil { !registry.isSkillEnabled("com.max.automation.instagram.search_chat") && viewModel.uiState.value.installedSkills.firstOrNull()?.isEnabled == false }

        val state = viewModel.uiState.value
        assertEquals(1, state.installedSkills.size)
        assertFalse(state.installedSkills[0].isEnabled)

        viewModel.toggleSkillEnabled("com.max.automation.instagram.search_chat", true)
        waitUntil { registry.isSkillEnabled("com.max.automation.instagram.search_chat") && viewModel.uiState.value.installedSkills.firstOrNull()?.isEnabled == true }

        assertTrue(registry.isSkillEnabled("com.max.automation.instagram.search_chat"))
        assertTrue(viewModel.uiState.value.installedSkills[0].isEnabled)
    }

    @Test
    fun `test viewmodel delete confirmation and execution`() {
        val jsonFile = File("../shared/skills/fixtures/instagram_search_chat.json")
        val content = if (jsonFile.exists()) jsonFile.readText() else File("shared/skills/fixtures/instagram_search_chat.json").readText()

        registry.install(content, targetPlatform = "android")
        val viewModel = SkillsViewModel(Application(), registry)
        viewModel.loadSkills()
        waitUntil { viewModel.uiState.value.installedSkills.isNotEmpty() }

        val item = viewModel.uiState.value.installedSkills[0]
        viewModel.requestDelete(item)
        assertNotNull(viewModel.uiState.value.deleteConfirmationSkill)

        viewModel.confirmDelete()
        waitUntil { !registry.contains("com.max.automation.instagram.search_chat") && viewModel.uiState.value.installedSkills.isEmpty() }

        assertNull(viewModel.uiState.value.deleteConfirmationSkill)
        assertEquals(0, viewModel.uiState.value.installedSkills.size)
        assertFalse(registry.contains("com.max.automation.instagram.search_chat"))
    }
}
