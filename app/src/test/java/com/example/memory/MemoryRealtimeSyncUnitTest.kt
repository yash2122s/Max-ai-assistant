package com.example.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.SaveMemoryTool
import com.example.memory.data.MemoryCategory
import com.example.memory.data.MemoryRepository
import com.example.memory.data.MemorySource
import com.example.memory.data.MemoryType
import com.example.memory.data.PermanentMemory
import com.example.memory.retrieval.MemoryFactResolver
import com.example.memory.universal.UniversalMemoryImporter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryRealtimeSyncUnitTest {

    private lateinit var context: Context
    private lateinit var repository: MemoryRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = MemoryRepository.getInstance(context)
        runBlocking {
            repository.deleteAll()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            repository.deleteAll()
        }
    }

    @Test
    fun testManualCreate_ImmediateVoiceRetrieval() {
        runBlocking {
            val initialRev = repository.memoryRevision.value

            // 1. User manually creates "Favourite Food = Dilkush Biryani" in Memory Wallet
            val foodMemory = PermanentMemory(
                memoryId = UUID.randomUUID().toString(),
                title = "Favourite Food",
                content = "Dilkush Biryani",
                category = MemoryCategory.PREFERENCES.displayName,
                type = MemoryType.PREFERENCE,
                source = MemorySource.MANUAL
            )
            val saveResult = repository.addMemory(foodMemory)
            assertTrue(saveResult is com.example.memory.data.MemoryValidationResult.Success)
            assertTrue("Memory revision should increment on create", repository.memoryRevision.value > initialRev)

            // 2. User immediately asks MAX voice "What is my favourite food?"
            val resolved = MemoryFactResolver.resolve(context, "What is my favourite food?")
            assertNotNull("Should resolve memory inquiry", resolved)
            assertTrue("Fact should be found in memory", resolved!!.found)
            assertTrue("Response should contain Dilkush Biryani", resolved.responseText.contains("Dilkush Biryani"))
        }
    }

    @Test
    fun testManualEdit_ImmediateUpdatedValueRetrieved() {
        runBlocking {
            // 1. Initial memory
            val foodMemory = PermanentMemory(
                memoryId = "food-mem-1",
                title = "Favourite Food",
                content = "Dilkush Biryani",
                category = MemoryCategory.PREFERENCES.displayName,
                type = MemoryType.PREFERENCE,
                source = MemorySource.MANUAL
            )
            repository.addMemory(foodMemory)

            // 2. User edits memory to "Hyderabadi Chicken Biryani"
            val updatedMemory = foodMemory.copy(content = "Hyderabadi Chicken Biryani")
            val updateResult = repository.updateMemory(updatedMemory)
            assertTrue(updateResult is com.example.memory.data.MemoryValidationResult.Success)

            // 3. User immediately asks MAX voice "Naa favourite food enti?"
            val resolved = MemoryFactResolver.resolve(context, "Naa favourite food enti?")
            assertNotNull(resolved)
            assertTrue(resolved!!.found)
            assertTrue("Should return updated food value", resolved.responseText.contains("Hyderabadi Chicken Biryani"))
            assertFalse("Should NOT return old stale value", resolved.responseText.contains("Dilkush Biryani"))
        }
    }

    @Test
    fun testManualDelete_ImmediateForget() {
        runBlocking {
            val memId = "food-mem-2"
            val foodMemory = PermanentMemory(
                memoryId = memId,
                title = "Favourite Food",
                content = "Dilkush Biryani",
                category = MemoryCategory.PREFERENCES.displayName,
                type = MemoryType.PREFERENCE
            )
            repository.addMemory(foodMemory)

            // Verify it was present
            val beforeDelete = MemoryFactResolver.resolve(context, "What is my favourite food?")
            assertTrue(beforeDelete!!.found)

            // User deletes memory in Memory Wallet
            repository.deleteById(memId)

            // Voice query immediately returns not found
            val afterDelete = MemoryFactResolver.resolve(context, "What is my favourite food?")
            assertNotNull(afterDelete)
            assertFalse("Deleted memory must not be found", afterDelete!!.found)
        }
    }

    @Test
    fun testEnableDisableToggle_ImmediateVisibilityControl() {
        runBlocking {
            val memId = "laptop-mem-1"
            val laptopMemory = PermanentMemory(
                memoryId = memId,
                title = "Laptop",
                content = "HP Victus Gaming",
                category = MemoryCategory.WORK.displayName,
                type = MemoryType.FACT,
                enabled = true
            )
            repository.addMemory(laptopMemory)

            // 1. Enabled -> Accessible
            val enabledResult = MemoryFactResolver.resolve(context, "What is my laptop?")
            assertTrue(enabledResult!!.found)
            assertTrue(enabledResult.responseText.contains("HP Victus Gaming"))

            // 2. Disabled -> Not Accessible
            repository.toggleEnabled(memId, false)
            val disabledResult = MemoryFactResolver.resolve(context, "What is my laptop?")
            assertNotNull(disabledResult)
            assertFalse("Disabled memory must not be returned", disabledResult!!.found)

            // 3. Re-enabled -> Accessible again
            repository.toggleEnabled(memId, true)
            val reEnabledResult = MemoryFactResolver.resolve(context, "What is my laptop?")
            assertTrue(reEnabledResult!!.found)
            assertTrue(reEnabledResult.responseText.contains("HP Victus Gaming"))
        }
    }

    @Test
    fun testUniversalMemoryImport_ImmediateVoiceRetrieval() {
        runBlocking {
            val jsonImportPayload = """
                {
                  "max_backup_version": 1,
                  "export_source": "MAX",
                  "memories": [
                    {
                      "title": "Main Laptop",
                      "content": "MacBook Pro M3 Max",
                      "category": "Work",
                      "type": "FACT"
                    }
                  ]
                }
            """.trimIndent()

            val importer = UniversalMemoryImporter(context)
            val preview = importer.preview(jsonImportPayload, "max_memories.json")
            assertEquals(1, preview.totalParsed)

            val commitResult = importer.commit(preview.items.map { it.candidate })
            assertTrue(commitResult.success)
            assertEquals(1, commitResult.insertedCount)

            // Immediate voice query
            val resolved = MemoryFactResolver.resolve(context, "Which laptop do I use?")
            assertNotNull(resolved)
            assertTrue(resolved!!.found)
            assertTrue(resolved.responseText.contains("MacBook Pro M3 Max"))
        }
    }

    @Test
    fun testVoiceSaveTool_ImmediateMemoryWalletAndVoiceRetrieval() {
        runBlocking {
            val tool = SaveMemoryTool()
            val args = com.google.gson.JsonObject().apply {
                addProperty("title", "User Name")
                addProperty("content", "User name is Yashwanth")
                addProperty("category", "Personal")
                addProperty("type", "FACT")
            }
            val request = ExecutionRequest(
                action = "SAVE_MEMORY",
                arguments = args
            )

            val toolResult = tool.execute(context, request)
            assertTrue(toolResult.success)

            // 1. Check Room repository
            val allMemories = repository.getAll()
            assertTrue(allMemories.any { it.title == "User Name" && it.content == "User name is Yashwanth" })

            // 2. Check Voice retrieval immediately
            val voiceResolved = MemoryFactResolver.resolve(context, "What is my name?")
            assertNotNull(voiceResolved)
            assertTrue(voiceResolved!!.found)
            assertTrue(voiceResolved.responseText.contains("Yashwanth"))
        }
    }

    @Test
    fun testMultilingualInquiryResolution() {
        runBlocking {
            val foodMemory = PermanentMemory(
                memoryId = "food-multi-1",
                title = "Favourite Food",
                content = "Dilkush Biryani",
                category = MemoryCategory.PREFERENCES.displayName,
                type = MemoryType.PREFERENCE
            )
            repository.addMemory(foodMemory)

            // English
            val en = MemoryFactResolver.resolve(context, "What is my favourite food?")
            assertTrue("English inquiry should resolve", en!!.found && en.responseText.contains("Dilkush Biryani"))

            // Tinglish
            val tinglish = MemoryFactResolver.resolve(context, "Naa favourite food cheppu")
            assertTrue("Tinglish inquiry should resolve", tinglish!!.found && tinglish.responseText.contains("Dilkush Biryani"))

            // Hindi
            val hi = MemoryFactResolver.resolve(context, "Mera favourite food kya hai?")
            assertTrue("Hindi inquiry should resolve", hi!!.found && hi.responseText.contains("Dilkush Biryani"))
        }
    }
}
