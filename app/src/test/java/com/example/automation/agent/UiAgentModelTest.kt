package com.example.automation.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.preferences.SettingsManager
import com.example.network.model.GeminiLiveModels
import com.example.network.model.UiAgentModels
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UiAgentModelTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testCentralizedModelDefaults() {
        // Verify UI Agent models
        assertEquals("gemini-2.5-flash", UiAgentModels.GEMINI_UI_AGENT_DEFAULT)
        assertEquals("gemini-3.5-flash", UiAgentModels.GEMINI_UI_AGENT_FALLBACK)
        assertEquals("gemini-2.5-flash", GeminiUiAgentProvider.DEFAULT_MODEL)
        assertEquals("gemini-3.5-flash", GeminiUiAgentProvider.FALLBACK_MODEL)

        // Verify Live voice models remain isolated and distinct
        assertEquals("gemini-3.8-live", GeminiLiveModels.FAST)
        assertEquals("gemini-3.8-live-extended-thinking", GeminiLiveModels.DEEP)
        assertNotEquals(GeminiLiveModels.FAST, UiAgentModels.GEMINI_UI_AGENT_DEFAULT)
    }

    @Test
    fun testObsoleteGemini20And15ModelsDisallowedAndSanitized() {
        val obsoleteInputs = listOf(
            "gemini-2.0-flash",
            "gemini-2.0-flash-001",
            "gemini-2.0-flash-exp",
            "models/gemini-2.0-flash",
            "models/gemini-2.0-flash-001",
            "google/gemini-2.0-flash-001",
            "2.0-flash",
            "GEMINI-2.0-FLASH",
            "gemini-1.5-flash",
            "gemini-1.5-flash-8b",
            "gemini-1.5-pro",
            "models/gemini-1.5-flash",
            "",
            "   "
        )

        for (input in obsoleteInputs) {
            val sanitized = UiAgentModels.sanitizeModelName(input)
            assertEquals(
                "Input '$input' should be sanitized to default 'gemini-2.5-flash'",
                UiAgentModels.GEMINI_UI_AGENT_DEFAULT,
                sanitized
            )
        }
        val nullSanitized = UiAgentModels.sanitizeModelName(null)
        assertEquals("Null input should be sanitized to default 'gemini-2.5-flash'", UiAgentModels.GEMINI_UI_AGENT_DEFAULT, nullSanitized)
    }

    @Test
    fun testValidModelsPreservedBySanitizer() {
        val validInputs = listOf(
            "gemini-2.5-flash",
            "gemini-3.5-flash",
            "gemini-2.5-pro",
            "stealth/ox-alpha",
            "anthropic/claude-3.5-sonnet",
            "openai/gpt-4o"
        )

        for (valid in validInputs) {
            val sanitized = UiAgentModels.sanitizeModelName(valid)
            assertEquals("Valid model '$valid' should be preserved", valid, sanitized)
        }
    }

    @Test
    fun testGeminiUiAgentProviderCompatibleModels() = runBlocking {
        val provider = GeminiUiAgentProvider()
        val models = provider.getCompatibleModels(context = context, apiKey = "")

        assertFalse("Compatible models list should not be empty", models.isEmpty())
        assertTrue("Should contain gemini-2.5-flash", models.any { it.id == "gemini-2.5-flash" && it.isPreferred })
        assertTrue("Should contain gemini-3.5-flash fallback", models.any { it.id == "gemini-3.5-flash" && !it.isPreferred })
        assertFalse("Must NOT contain gemini-2.0-flash", models.any { it.id.contains("2.0-flash") })
        assertFalse("Must NOT contain gemini-1.5-flash", models.any { it.id.contains("1.5-flash") })
    }

    @Test
    fun testOpenRouterCuratedModelsExcludesObsoleteGemini20() {
        val models = OpenRouterUiAgentProvider.CURATED_COMPATIBLE_MODELS
        assertTrue("Should contain stealth/ox-alpha", models.any { it.id == "stealth/ox-alpha" && it.isPreferred })
        assertTrue("Should contain google/gemini-2.5-flash", models.any { it.id == "google/gemini-2.5-flash" })
        assertFalse("Must NOT contain google/gemini-2.0-flash-001", models.any { it.id.contains("2.0-flash") })
    }

    @Test
    fun testUiAgentRouterFallbackToGemini25Flash() {
        val settings = SettingsManager(context)
        settings.uiAgentApiKey = "" // Unconfigured OpenRouter
        settings.geminiApiKey = "test-gemini-key"

        val router = UiAgentRouter(context)
        val selected = router.selectProvider(context)

        assertEquals("Should route to Gemini provider", GeminiUiAgentProvider.PROVIDER_ID, selected.provider.id)
        assertEquals("Should route to gemini-2.5-flash", "gemini-2.5-flash", selected.model)
        assertEquals("test-gemini-key", selected.apiKey)
    }

    @Test
    fun testSettingsManagerSanitizesObsoleteModel() {
        val settings = SettingsManager(context)
        settings.uiAgentModel = "gemini-2.0-flash"
        assertEquals(SettingsManager.DEFAULT_UI_AGENT_MODEL, settings.uiAgentModel)
    }
}
