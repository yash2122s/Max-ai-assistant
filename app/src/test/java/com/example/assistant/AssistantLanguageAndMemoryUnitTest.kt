package com.example.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.OfflineCommandEngine
import com.example.data.local.AppDatabase
import com.example.domain.policy.AssistantLanguage
import com.example.domain.policy.LanguagePolicy
import com.example.domain.usecase.AssistantUseCase
import com.example.memory.data.MemoryCategory
import com.example.memory.data.MemoryRepository
import com.example.memory.data.MemorySource
import com.example.memory.data.MemoryType
import com.example.memory.data.PermanentMemory
import com.example.rag.engine.RagEngine
import com.example.utils.InternalReasoningFilter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantLanguageAndMemoryUnitTest {

    private lateinit var context: Context
    private lateinit var memoryRepo: MemoryRepository
    private lateinit var ragEngine: RagEngine
    private lateinit var assistantUseCase: AssistantUseCase

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        com.example.core.lifecycle.MaxRuntimeManager.enableMax(context)
        memoryRepo = MemoryRepository.getInstance(context)
        memoryRepo.deleteAll()
        ragEngine = RagEngine.getInstance(context)
        ragEngine.clearIndex()
        assistantUseCase = AssistantUseCase(context)
    }

    @After
    fun tearDown() = runBlocking {
        memoryRepo.deleteAll()
        ragEngine.clearIndex()
    }

    // =========================================================================
    // 1. Language Policy Tests
    // =========================================================================

    @Test
    fun testDefaultLanguageIsTinglish() {
        val resolved = LanguagePolicy.resolveResponseLanguage("What is the weather today?")
        assertEquals(AssistantLanguage.TINGLISH, resolved)

        val resolvedFromTelugu = LanguagePolicy.resolveResponseLanguage("Eroju weather ela undi?")
        assertEquals(AssistantLanguage.TINGLISH, resolvedFromTelugu)

        val resolvedFromHindi = LanguagePolicy.resolveResponseLanguage("aaj mausam kaisa hai?")
        assertEquals(AssistantLanguage.TINGLISH, resolvedFromHindi)

        val resolvedNull = LanguagePolicy.resolveResponseLanguage(null)
        assertEquals(AssistantLanguage.TINGLISH, resolvedNull)
    }

    @Test
    fun testExplicitLanguageOverrides() {
        // English override
        assertEquals(AssistantLanguage.ENGLISH, LanguagePolicy.resolveResponseLanguage("English lo cheppu"))
        assertEquals(AssistantLanguage.ENGLISH, LanguagePolicy.resolveResponseLanguage("speak english please"))
        assertEquals(AssistantLanguage.ENGLISH, LanguagePolicy.resolveResponseLanguage("tell me in english"))
        assertEquals(AssistantLanguage.ENGLISH, LanguagePolicy.resolveResponseLanguage("English only"))

        // Telugu override
        assertEquals(AssistantLanguage.TELUGU, LanguagePolicy.resolveResponseLanguage("Telugu lo cheppu"))
        assertEquals(AssistantLanguage.TELUGU, LanguagePolicy.resolveResponseLanguage("తెలుగులో చెప్పు"))
        assertEquals(AssistantLanguage.TELUGU, LanguagePolicy.resolveResponseLanguage("speak telugu"))

        // Hindi override
        assertEquals(AssistantLanguage.HINDI, LanguagePolicy.resolveResponseLanguage("Hindi lo cheppu"))
        assertEquals(AssistantLanguage.HINDI, LanguagePolicy.resolveResponseLanguage("speak hindi"))
        assertEquals(AssistantLanguage.HINDI, LanguagePolicy.resolveResponseLanguage("hindi mein bolo"))

        // Tinglish override
        assertEquals(AssistantLanguage.TINGLISH, LanguagePolicy.resolveResponseLanguage("tinglish lo cheppu"))
        assertEquals(AssistantLanguage.TINGLISH, LanguagePolicy.resolveResponseLanguage("tenglish lo cheppu"))
    }

    @Test
    fun testSessionPreferenceResolution() {
        assertEquals(AssistantLanguage.TELUGU, LanguagePolicy.resolveResponseLanguage(null, "Telugu"))
        assertEquals(AssistantLanguage.TELUGU, LanguagePolicy.resolveResponseLanguage("Eroju weather ela undi?", "Telugu"))
        assertEquals(AssistantLanguage.TELUGU, LanguagePolicy.resolveResponseLanguage("What is the time?", "తెలుగు"))

        assertEquals(AssistantLanguage.ENGLISH, LanguagePolicy.resolveResponseLanguage(null, "English"))
        assertEquals(AssistantLanguage.ENGLISH, LanguagePolicy.resolveResponseLanguage("Eroju weather ela undi?", "English"))
        assertEquals(AssistantLanguage.ENGLISH, LanguagePolicy.resolveResponseLanguage("నా పేరు చెప్పు", "English"))

        assertEquals(AssistantLanguage.HINDI, LanguagePolicy.resolveResponseLanguage(null, "Hindi"))
        assertEquals(AssistantLanguage.HINDI, LanguagePolicy.resolveResponseLanguage("Eroju weather ela undi?", "Hindi"))
        assertEquals(AssistantLanguage.HINDI, LanguagePolicy.resolveResponseLanguage("నా పేరు చెప్పు", "हिंदी"))

        assertEquals(AssistantLanguage.TINGLISH, LanguagePolicy.resolveResponseLanguage(null, "Tinglish"))
        assertEquals(AssistantLanguage.TINGLISH, LanguagePolicy.resolveResponseLanguage(null, "Tenglish"))
    }

    // =========================================================================
    // 2. Memory Save & Retrieval Tests
    // =========================================================================

    @Test
    fun testMemorySaveDetection_Name_Tinglish() = runBlocking {
        val result = assistantUseCase.sendTextMessage("Naa peru Yash. Gurthupettuko.")
        assertNotNull(result)
        assertTrue(result!!.success)
        assertEquals("Sure sir, mee peru gurthupettukuntaanu.", result.message)

        val all = memoryRepo.getAll()
        assertTrue(all.any { it.title == "User Name" && it.content == "User name is Yash" })
    }

    @Test
    fun testMemorySaveDetection_Name_English() = runBlocking {
        val result = assistantUseCase.sendTextMessage("My name is Yash. Remember it.")
        assertNotNull(result)
        assertTrue(result!!.success)
        assertEquals("Sure sir, mee peru gurthupettukuntaanu.", result.message)

        val all = memoryRepo.getAll()
        assertTrue(all.any { it.title == "User Name" && it.content == "User name is Yash" })
    }

    @Test
    fun testMemorySaveDetection_Project() = runBlocking {
        val result = assistantUseCase.sendTextMessage("Remember that my main project is MAX AI.")
        assertNotNull(result)
        assertTrue(result!!.success)
        assertTrue(result.message!!.contains("MAX AI"))

        val all = memoryRepo.getAll()
        assertTrue(all.any { it.title == "Main Project" && it.content.contains("MAX AI") })
    }

    @Test
    fun testMemorySaveDetection_Laptop() = runBlocking {
        val result = assistantUseCase.sendTextMessage("My laptop is HP Victus.")
        assertNotNull(result)
        assertTrue(result!!.success)
        assertTrue(result.message!!.contains("HP Victus"))

        val all = memoryRepo.getAll()
        assertTrue(all.any { it.title == "Laptop" && it.content.contains("HP Victus") })
    }

    @Test
    fun testMultilingualMemoryRetrieval() = runBlocking {
        // Save user name memory
        val session = com.example.memory.account.MemoryAccountScope.capture()
        val memory = PermanentMemory(
            title = "User Name",
            content = "User name is Yash",
            category = MemoryCategory.PERSONAL.displayName,
            type = MemoryType.FACT,
            source = MemorySource.AUTO,
            tags = "name,identity",
            ownerKey = session.ownerKey
        )
        memoryRepo.addMemory(memory)
        ragEngine.indexMemory(memory)

        // 1. English query
        val enContext = ragEngine.buildContextForQuery("What is my name?")
        assertTrue("English query should retrieve name memory", enContext.contains("User name is Yash"))

        // 2. Telugu / Tinglish query
        val teContext = ragEngine.buildContextForQuery("Naa peru enti?")
        assertTrue("Tinglish query should retrieve name memory", teContext.contains("User name is Yash"))

        // 3. Telugu script query
        val teScriptContext = ragEngine.buildContextForQuery("నా పేరు ఏంటి?")
        assertTrue("Telugu script query should retrieve name memory", teScriptContext.contains("User name is Yash"))

        // 4. Hindi query
        val hiContext = ragEngine.buildContextForQuery("Mera naam kya hai?")
        assertTrue("Hindi query should retrieve name memory", hiContext.contains("User name is Yash"))
    }

    @Test
    fun testMissingMemory_ReturnsEmptyContextForStandardRag() = runBlocking {
        // When asking for something not in memory
        val context = ragEngine.buildContextForQuery("What is my dog's favorite food?")
        assertEquals("", context)
    }

    // =========================================================================
    // 3. Internal Reasoning Leak Prevention Tests
    // =========================================================================

    @Test
    fun testInternalReasoningFilter_StripsThoughtsAndLeakedText() {
        val leakedText = """
            **Retrieving Personal History**
            I'm currently focused on retrieving the user's historical data related to period tracking. I intend to use the `get_period_history` and `get_period_prediction` tools to access any relevant information stored in memory. My goal is to compile any stored data pertaining to this domain and prepare an appropriate response.
            Sir, mee peru Yash ani memory lo undi.
        """.trimIndent()

        val sanitized = InternalReasoningFilter.sanitize(leakedText)
        assertEquals("Sir, mee peru Yash ani memory lo undi.", sanitized)
    }

    @Test
    fun testInternalReasoningFilter_StripsRagBoundaryTags() {
        val leakedWithTags = """
            <MAX_RETRIEVED_MEMORY>
            [Memory 1]
            Title: User Name
            Content: User name is Yash
            </MAX_RETRIEVED_MEMORY>
            Sir, mee peru Yash.
        """.trimIndent()

        val sanitized = InternalReasoningFilter.sanitize(leakedWithTags)
        assertEquals("Sir, mee peru Yash.", sanitized)
    }

    @Test
    fun testInternalReasoningFilter_StripsToolComplaints() {
        val badResponse = """
            I don't have a dedicated tool for retrieving general facts.
            Sir, mee peru Yash.
        """.trimIndent()

        val sanitized = InternalReasoningFilter.sanitize(badResponse)
        assertEquals("Sir, mee peru Yash.", sanitized)
    }

    // =========================================================================
    // 4. Device Command RAG Bypass Test
    // =========================================================================

    @Test
    fun testDeviceCommand_BypassesRag() {
        assertFalse(ragEngine.shouldRetrieveForQuery("Turn flashlight on"))
        assertFalse(ragEngine.shouldRetrieveForQuery("turn off the flashlight"))
        assertFalse(ragEngine.shouldRetrieveForQuery("open camera"))
        assertFalse(ragEngine.shouldRetrieveForQuery("go home"))
        assertFalse(ragEngine.shouldRetrieveForQuery("turn wifi on"))
    }
}
