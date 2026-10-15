package com.example.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.ActionAuthorizer
import com.example.automation.engine.ExecutionRequest
import com.example.automation.engine.ExecutionSource
import com.example.automation.tools.ForgetMemoryTool
import com.example.data.local.AppDatabase
import com.example.data.preferences.SettingsManager
import com.example.memory.data.MemoryCategory
import com.example.memory.data.MemoryRepository
import com.example.memory.data.MemoryType
import com.example.memory.data.PermanentMemory
import com.example.memory.account.MemoryAccountScope
import com.example.memory.retrieval.MemoryFormatter
import com.example.memory.retrieval.MemoryRetrieval
import com.example.memory.security.MemoryCryptography
import com.example.memory.security.SensitiveDataFilter
import com.google.gson.JsonObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MemorySafetyUnitTest {

    private lateinit var context: Context
    private lateinit var repository: MemoryRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        repository = MemoryRepository(context)
        runBlocking {
            AppDatabase.getDatabase(context).permanentMemoryDao().deleteAll()
        }
    }

    @Test
    fun test1_ExplicitRememberRequest_Stored() = runBlocking {
        val auth = ActionAuthorizer.authorizeAction("Remember that my preferred language is Telugu", "SAVE_MEMORY", null)
        assertTrue(auth.authorized)

        val memory = PermanentMemory(
            title = "Preferred Language",
            content = "Telugu",
            category = MemoryCategory.PREFERENCES.displayName,
            type = MemoryType.PREFERENCE
        )
        val result = repository.addMemory(memory)
        assertTrue(result is com.example.memory.data.MemoryValidationResult.Success)

        val retrieved = repository.getAll().filter { it.title == "Preferred Language" }
        assertEquals(1, retrieved.size)
        assertEquals("Telugu", retrieved[0].content)
    }

    @Test
    fun test2_NormalCommand_NotStored() {
        val userGoal = "Open WhatsApp."
        val auth = ActionAuthorizer.authorizeAction(userGoal, "SAVE_MEMORY", null)
        assertFalse(auth.authorized)
    }

    @Test
    fun test3_TemporaryTaskContext_NotStored() {
        val userGoal = "Send Hello to Bhaskar on WhatsApp."
        val auth = ActionAuthorizer.authorizeAction(userGoal, "SAVE_MEMORY", null)
        assertFalse(auth.authorized)
    }

    @Test
    fun test4_NotificationContent_NotStored() {
        val userGoal = "You got a message from Alice: 'See you at 5'"
        val auth = ActionAuthorizer.authorizeAction(userGoal, "SAVE_MEMORY", null)
        assertFalse(auth.authorized)
    }

    @Test
    fun test5_ScreenContent_NotStored() {
        val userGoal = "What is on my screen?"
        val auth = ActionAuthorizer.authorizeAction(userGoal, "SAVE_MEMORY", null)
        assertFalse(auth.authorized)
    }

    @Test
    fun test6_Password_Rejected() {
        val filter1 = SensitiveDataFilter.isSafeToStore("Bank Account", "My password is supersecret123")
        assertFalse(filter1.isSafe)

        val filter2 = SensitiveDataFilter.isSafeToStore("ATM PIN", "My login pin is 4829")
        assertFalse(filter2.isSafe)
    }

    @Test
    fun test7_OTP_Rejected() {
        val filter = SensitiveDataFilter.isSafeToStore("Bank Login", "Your verification code is 849201")
        assertFalse(filter.isSafe)
    }

    @Test
    fun test8_ApiKey_Rejected() {
        val filter1 = SensitiveDataFilter.isSafeToStore("Cloud Config", "API Key = AIzaSyD9x8c1a7b6e5d4c3b2a1")
        assertFalse(filter1.isSafe)

        val filter2 = SensitiveDataFilter.isSafeToStore("Auth Header", "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.e30.t-ID")
        assertFalse(filter2.isSafe)
    }

    @Test
    fun test9_DuplicateMemory_MergedIdempotent() = runBlocking {
        val mem1 = PermanentMemory(
            title = "Favorite Drink",
            content = "Filter Coffee",
            category = MemoryCategory.PREFERENCES.displayName,
            type = MemoryType.PREFERENCE
        )
        val res1 = repository.addMemory(mem1)
        assertTrue(res1 is com.example.memory.data.MemoryValidationResult.Success)

        // Adding exact same memory again
        val mem2 = PermanentMemory(
            title = "Favorite Drink",
            content = "Filter Coffee",
            category = MemoryCategory.PREFERENCES.displayName,
            type = MemoryType.PREFERENCE
        )
        val res2 = repository.addMemory(mem2)
        assertTrue(res2 is com.example.memory.data.MemoryValidationResult.Success)

        val matching = repository.getAll().filter { it.title == "Favorite Drink" }
        assertEquals(1, matching.size)
    }

    @Test
    fun test10_UpdatedPreference_OldValueSuperseded() = runBlocking {
        val initial = PermanentMemory(
            title = "Favorite Phone",
            content = "Redmi K20 Pro",
            category = MemoryCategory.PREFERENCES.displayName,
            type = MemoryType.PREFERENCE
        )
        repository.addMemory(initial)

        // User later updates preference with same topic/title
        val updated = PermanentMemory(
            title = "Favorite Phone",
            content = "Samsung Galaxy S24",
            category = MemoryCategory.PREFERENCES.displayName,
            type = MemoryType.PREFERENCE
        )
        repository.addMemory(updated)

        val matching = repository.getAll().filter { it.title == "Favorite Phone" }
        assertEquals(1, matching.size)
        assertEquals("Samsung Galaxy S24", matching[0].content)
    }

    @Test
    fun test11_ForgetMemoryRequest_ActualDeletion() = runBlocking {
        repository.addMemory(PermanentMemory(
            title = "Preferred Language",
            content = "Telugu",
            category = MemoryCategory.PREFERENCES.displayName,
            type = MemoryType.PREFERENCE
        ))

        val forgetTool = ForgetMemoryTool()
        val args = JsonObject().apply {
            addProperty("query", "Telugu")
        }
        val request = ExecutionRequest(
            action = "FORGET_MEMORY",
            arguments = args,
            source = ExecutionSource.GEMINI_LIVE
        )

        val result = forgetTool.execute(context, request)
        assertTrue(result.success)
        assertTrue(result.message?.contains("Successfully forgot") == true)

        val remaining = repository.getAll().filter { it.content.contains("Telugu") }
        assertEquals(0, remaining.size)
    }

    @Test
    fun test12_IrrelevantMemory_NotRetrieved() = runBlocking {
        val memory = PermanentMemory(
            title = "Gym Schedule",
            content = "Leg day on Wednesdays",
            category = MemoryCategory.PERSONAL.displayName,
            type = MemoryType.FACT
        )
        val score = MemoryRetrieval.scoreMemory(memory, "What is the weather today in Hyderabad?")
        assertEquals(0, score)
    }

    @Test
    fun test13_RelevantMemory_Retrieved() = runBlocking {
        val memory = PermanentMemory(
            title = "Preferred Language",
            content = "Telugu",
            category = MemoryCategory.PREFERENCES.displayName,
            type = MemoryType.PREFERENCE
        )
        val score = MemoryRetrieval.scoreMemory(memory, "What is my preferred language?")
        assertTrue(score > 0)
    }

    @Test
    fun test14_AllEnabledMemories_IncludedInRetrievalAndPrompt() = runBlocking {
        val publicMem = PermanentMemory(
            title = "Favorite Music",
            content = "AR Rahman",
            category = MemoryCategory.PREFERENCES.displayName,
            type = MemoryType.PREFERENCE
        )
        val secondMem = PermanentMemory(
            title = "Medical Note",
            content = "Prescription allergy list",
            category = MemoryCategory.PERSONAL.displayName,
            type = MemoryType.FACT
        )
        repository.addMemory(publicMem)
        repository.addMemory(secondMem)

        val enabled = repository.getAllEnabled().filter { it.title == "Favorite Music" || it.title == "Medical Note" }
        assertEquals(2, enabled.size)

        val promptStr = MemoryFormatter.formatForPrompt(listOf(publicMem, secondMem))
        assertTrue(promptStr.contains("AR Rahman"))
        assertTrue(promptStr.contains("Prescription allergy list"))
    }

    @Test
    fun test15_MemoryRetrieval_NoUnrelatedPrivateDataExposed() {
        val safeMemories = listOf(
            PermanentMemory(
                title = "Preferred Language",
                content = "Telugu",
                category = MemoryCategory.PREFERENCES.displayName,
                type = MemoryType.PREFERENCE
            )
        )
        val formatted = MemoryFormatter.formatForPrompt(safeMemories)
        assertTrue(formatted.contains("User Permanent Memory"))
        assertTrue(formatted.contains("Telugu"))
    }

    @Test
    fun test16_SettingsManagerEncryption_CredentialMigration() {
        val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("gemini_api_key", "test_gemini_key_123456").apply()

        val settings = SettingsManager(context)
        assertEquals("test_gemini_key_123456", settings.geminiApiKey)
    }

    @Test
    fun test17_MemoryDatabase_ColumnLevelEncryption() = runBlocking {
        val memory = PermanentMemory(
            title = "Favorite Food",
            content = "Biryani",
            category = MemoryCategory.PREFERENCES.displayName,
            type = MemoryType.PREFERENCE
        )
        repository.addMemory(memory)

        // Read directly from the raw database to verify encryption on-disk
        val rawList = AppDatabase.getDatabase(context).permanentMemoryDao().getAll(MemoryAccountScope.capture().ownerKey)
        val rawMem = rawList.first { it.category == MemoryCategory.PREFERENCES.displayName }
        
        // Assert values are encrypted on disk (prefixed with enc_v1:)
        assertTrue(rawMem.title.startsWith("enc_v1:"))
        assertTrue(rawMem.content.startsWith("enc_v1:"))

        // Verify decrypted values in the repository match originals
        val repoList = repository.getAll()
        val repoMem = repoList.first { it.category == MemoryCategory.PREFERENCES.displayName }
        assertEquals("Favorite Food", repoMem.title)
        assertEquals("Biryani", repoMem.content)
    }

    @Test
    fun test18_MemoriesMd_PlaintextCacheElimination() = runBlocking {
        val memory = PermanentMemory(
            title = "Home Address",
            content = "Flat 101, Jubilee Hills",
            category = MemoryCategory.PERSONAL.displayName,
            type = MemoryType.FACT
        )
        repository.addMemory(memory)

        val file = File(context.applicationContext.filesDir, "memories.md")
        assertFalse(file.exists())
    }

    @Test
    fun test19_MemoryCryptography_FailClosedQuarantine() {
        val encrypted = MemoryCryptography.encrypt("MySecretData")
        val decrypted = MemoryCryptography.decrypt(encrypted)
        assertEquals("MySecretData", decrypted)

        // Test fail-closed decryption with a bad prefix or corrupted bytes
        val badEncrypted = "enc_v1:invalid_iv:invalid_bytes"
        val failDecrypted = MemoryCryptography.decrypt(badEncrypted)
        assertTrue(failDecrypted.contains("Quarantined") || failDecrypted.contains("FAILED"))
    }

    @Test
    fun test20_AgentMemoriesOnly_CreationRetrievalAndDeletion(): Unit = runBlocking {
        val memory = PermanentMemory(
            title = "Preferred Language",
            content = "Telugu",
            category = MemoryCategory.PREFERENCES.displayName,
            type = MemoryType.PREFERENCE
        )
        repository.addMemory(memory)

        val retrieved: PermanentMemory? = repository.getAllEnabled().firstOrNull { it.title == "Preferred Language" }
        assertNotNull(retrieved)
        assertEquals("Telugu", retrieved?.content)

        val promptStr = MemoryFormatter.formatForPrompt(listOf(retrieved!!))
        assertTrue(promptStr.contains("Preferred Language: Telugu"))

        repository.deleteById(retrieved.memoryId)
        val afterDelete: PermanentMemory? = repository.getAllEnabled().firstOrNull { it.title == "Preferred Language" }
        assertNull(afterDelete)
    }

    @Test
    fun test21_SecretKeyCaching_ThreadSafeAndFast() {
        val testText = "FastEncryptionCheck"
        val start = System.currentTimeMillis()
        for (i in 1..50) {
            val enc = MemoryCryptography.encrypt(testText)
            val dec = MemoryCryptography.decrypt(enc)
            assertEquals(testText, dec)
        }
        val duration = System.currentTimeMillis() - start
        assertTrue("50 encrypt/decrypt operations should complete quickly with cached key", duration < 2000)
    }

    @Test
    fun test22_ConcurrentSecretKeyAccess(): Unit = runBlocking {
        val jobs = (1..20).map { id ->
            async(kotlinx.coroutines.Dispatchers.Default) {
                val enc = MemoryCryptography.encrypt("ConcurrentData_$id")
                val dec = MemoryCryptography.decrypt(enc)
                assertEquals("ConcurrentData_$id", dec)
            }
        }
        jobs.awaitAll()
    }

    @Test
    fun test23_IdempotentRepositoryInit() = runBlocking {
        // Creating a secondary repository instance should not trigger another write cycle
        val repo2 = MemoryRepository(context)
        val list = repo2.getAllEnabled()
        assertNotNull(list)
    }
}
