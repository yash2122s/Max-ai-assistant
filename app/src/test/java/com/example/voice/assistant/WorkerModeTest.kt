package com.example.voice.assistant

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WorkerModeTest {

    @Before
    fun setUp() {
        // Reset state before each test
        val currentSession = AssistantLifecycleManager.activeMasterSessionId
        if (currentSession != null) {
            AssistantLifecycleManager.exitWorkerMode(currentSession)
        }
    }

    @After
    fun tearDown() {
        val currentSession = AssistantLifecycleManager.activeMasterSessionId
        if (currentSession != null) {
            AssistantLifecycleManager.exitWorkerMode(currentSession)
        }
    }

    @Test
    fun test01_initialStateIsStandalone() = runTest {
        assertEquals(AssistantMode.STANDALONE, AssistantLifecycleManager.mode.value)
        assertNull(AssistantLifecycleManager.activeMasterSessionId)
    }

    @Test
    fun test02_authenticatedConnectionEntersWorkerMode() = runTest {
        val session = "master_sess_alpha_001"
        AssistantLifecycleManager.enterWorkerMode(session)

        assertEquals(AssistantMode.WORKER_MODE, AssistantLifecycleManager.mode.value)
        assertEquals(session, AssistantLifecycleManager.activeMasterSessionId)
        assertTrue(AssistantLifecycleManager.sessionGeneration > 0)
    }

    @Test
    fun test03_blankSessionIgnored() = runTest {
        AssistantLifecycleManager.enterWorkerMode("")
        assertEquals(AssistantMode.STANDALONE, AssistantLifecycleManager.mode.value)
        assertNull(AssistantLifecycleManager.activeMasterSessionId)
    }

    @Test
    fun test04_duplicateEnterIsIdempotent() = runTest {
        val session = "master_sess_alpha_002"
        AssistantLifecycleManager.enterWorkerMode(session)
        val initialGen = AssistantLifecycleManager.sessionGeneration

        // Re-entering with same session
        AssistantLifecycleManager.enterWorkerMode(session)
        AssistantLifecycleManager.enterWorkerMode(session)

        assertEquals(AssistantMode.WORKER_MODE, AssistantLifecycleManager.mode.value)
        assertEquals(session, AssistantLifecycleManager.activeMasterSessionId)
        assertEquals(initialGen, AssistantLifecycleManager.sessionGeneration) // Must not re-increment
    }

    @Test
    fun test05_disconnectRestoresStandalone() = runTest {
        val session = "master_sess_alpha_003"
        AssistantLifecycleManager.enterWorkerMode(session)
        assertEquals(AssistantMode.WORKER_MODE, AssistantLifecycleManager.mode.value)

        AssistantLifecycleManager.exitWorkerMode(session)

        assertEquals(AssistantMode.STANDALONE, AssistantLifecycleManager.mode.value)
        assertNull(AssistantLifecycleManager.activeMasterSessionId)
    }

    @Test
    fun test06_staleDisconnectIgnored() = runTest {
        val oldSession = "master_sess_old"
        val newSession = "master_sess_new"

        // Old session connects then new session takes over
        AssistantLifecycleManager.enterWorkerMode(oldSession)
        AssistantLifecycleManager.enterWorkerMode(newSession)

        assertEquals(AssistantMode.WORKER_MODE, AssistantLifecycleManager.mode.value)
        assertEquals(newSession, AssistantLifecycleManager.activeMasterSessionId)

        // Delayed/stale disconnect from old session arrives
        AssistantLifecycleManager.exitWorkerMode(oldSession)

        // Must still remain in WORKER_MODE with newSession!
        assertEquals(AssistantMode.WORKER_MODE, AssistantLifecycleManager.mode.value)
        assertEquals(newSession, AssistantLifecycleManager.activeMasterSessionId)
    }

    @Test
    fun test07_rapidConnectDisconnectDeterministic() = runTest {
        (1..10).forEach { i ->
            val session = "rapid_sess_$i"
            AssistantLifecycleManager.enterWorkerMode(session)
            assertEquals(AssistantMode.WORKER_MODE, AssistantLifecycleManager.mode.value)
            assertEquals(session, AssistantLifecycleManager.activeMasterSessionId)

            AssistantLifecycleManager.exitWorkerMode(session)
            assertEquals(AssistantMode.STANDALONE, AssistantLifecycleManager.mode.value)
            assertNull(AssistantLifecycleManager.activeMasterSessionId)
        }
    }

    @Test
    fun test08_monotonicallyIncreasingGeneration() = runTest {
        val session1 = "gen_sess_1"
        val session2 = "gen_sess_2"

        AssistantLifecycleManager.enterWorkerMode(session1)
        val gen1 = AssistantLifecycleManager.sessionGeneration

        AssistantLifecycleManager.enterWorkerMode(session2)
        val gen2 = AssistantLifecycleManager.sessionGeneration

        assertTrue(gen2 > gen1)
    }

    @Test
    fun test09_duplicateExitIsIdempotent() = runTest {
        val session = "dup_exit_sess"
        AssistantLifecycleManager.enterWorkerMode(session)
        AssistantLifecycleManager.exitWorkerMode(session)

        // Duplicate exit call
        AssistantLifecycleManager.exitWorkerMode(session)

        assertEquals(AssistantMode.STANDALONE, AssistantLifecycleManager.mode.value)
        assertNull(AssistantLifecycleManager.activeMasterSessionId)
    }

    @Test
    fun test10_stateFlowEmitsUpdates() = runTest {
        val session = "flow_test_sess"
        AssistantLifecycleManager.enterWorkerMode(session)
        val currentMode = AssistantLifecycleManager.mode.first()
        assertEquals(AssistantMode.WORKER_MODE, currentMode)
    }
}
