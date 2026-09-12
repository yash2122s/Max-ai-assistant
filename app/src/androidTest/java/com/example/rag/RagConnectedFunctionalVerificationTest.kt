package com.example.rag

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.local.AppDatabase
import com.example.data.preferences.SettingsManager
import com.example.domain.usecase.AssistantUseCase
import com.example.memory.data.MemoryType
import com.example.memory.data.PermanentMemory
import com.example.memory.universal.ImportCandidate
import com.example.memory.universal.ImportProviderType
import com.example.memory.universal.UniversalMemoryImporter
import com.example.rag.chunking.RagChunker
import com.example.rag.context.RagContextBuilder
import com.example.rag.embedding.LocalEmbeddingProvider
import com.example.rag.engine.RagEngine
import com.example.rag.engine.RagIndexer
import com.example.rag.model.RagConfig
import com.example.rag.repository.RagRepository
import com.example.rag.retrieval.RagRetriever
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RagConnectedFunctionalVerificationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var ragEngine: RagEngine
    private lateinit var settingsManager: SettingsManager
    private val embeddingProvider = LocalEmbeddingProvider(128)

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        db = AppDatabase.getDatabase(context)
        ragEngine = RagEngine.getInstance(context)
        settingsManager = SettingsManager(context)

        com.example.core.lifecycle.MaxRuntimeManager.setGlobalEnabled(context, true)
        settingsManager.isMaxEnabled = true
        settingsManager.isRagEnabled = true
        settingsManager.isSemanticSearchEnabled = true
        settingsManager.isHybridSearchEnabled = true
        settingsManager.ragTopK = 5
        settingsManager.ragMinRelevance = 0.25f
    }

    @Test
    fun testRealDevice_FullRagPipeline() = runBlocking {
        ragEngine.clearIndex()

        // 1. Create and index memory
        val memory = PermanentMemory(
            memoryId = "device-mem-01",
            title = "MAX Architecture",
            content = "MAX uses 100% native Kotlin and Jetpack Compose.",
            category = "Technical",
            type = MemoryType.FACT
        )
        val indexed = ragEngine.indexMemory(memory)
        assertTrue(indexed)

        // 2. Query via RagEngine
        val query = "What technologies does MAX use?"
        val results = ragEngine.retrieve(query)

        assertFalse(results.isEmpty())
        assertEquals("device-mem-01", results[0].memoryId)
        assertTrue(results[0].score >= 0.25f)

        // 3. AssistantUseCase integration
        val assistantUseCase = AssistantUseCase(context)
        val ragContext = assistantUseCase.getRagContext("What technologies does my MAX project use?")
        assertTrue(ragContext.isNotBlank())
        assertTrue(ragContext.contains("<MAX_RETRIEVED_MEMORY>"))
        assertTrue(ragContext.contains("Kotlin and Jetpack Compose"))

        // 4. Offline command bypass
        assertFalse(ragEngine.shouldRetrieveForQuery("Turn the flashlight on."))
    }
}
