package com.example.rag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.rag.engine.RagEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RagEngineTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val engine = RagEngine.getInstance(context)

    @Test
    fun testRelevanceGatingBypassesDirectHardwareCommands() {
        // Direct local commands should return false (bypass RAG)
        assertFalse(engine.shouldRetrieveForQuery("turn flashlight on"))
        assertFalse(engine.shouldRetrieveForQuery("turn off wifi"))
        assertFalse(engine.shouldRetrieveForQuery("open camera"))
        assertFalse(engine.shouldRetrieveForQuery("go home"))
        assertFalse(engine.shouldRetrieveForQuery("volume up"))
        assertFalse(engine.shouldRetrieveForQuery("take a screenshot"))
    }

    @Test
    fun testRelevanceGatingActivatesForKnowledgeQueries() {
        // Knowledge/question queries should return true (activate RAG)
        assertTrue(engine.shouldRetrieveForQuery("What did I tell you about my laptop?"))
        assertTrue(engine.shouldRetrieveForQuery("What is my favourite programming language?"))
        assertTrue(engine.shouldRetrieveForQuery("Tell me about the MAX project architecture."))
        assertTrue(engine.shouldRetrieveForQuery("Remember my friend's birthday?"))
    }
}
