package com.example.network.agent

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RemoteCommandExecutionTest {

    private lateinit var cache: CommandIdempotencyCache

    @Before
    fun setUp() {
        cache = CommandIdempotencyCache(maxEntries = 5, ttlMs = 200L)
    }

    @Test
    fun test01_firstCommandExecutesOnceAndStoresResult() = runTest {
        val executionCounter = AtomicInteger(0)
        val cmdId = "cmd_test_01"

        val result = cache.executeOrAwait(cmdId) {
            executionCounter.incrementAndGet()
            WindowsAgentClient.CommandResultPayload(
                commandId = cmdId,
                status = "success",
                action = "FLASHLIGHT_ON",
                data = mapOf("enabled" to true),
                executionTimeMs = 25
            )
        }

        assertEquals(1, executionCounter.get())
        assertEquals("success", result.status)
        assertEquals("FLASHLIGHT_ON", result.action)
        assertEquals(1, cache.size())
    }

    @Test
    fun test02_duplicateCommandWithinTTLReturnsCachedWithoutReExecuting() = runTest {
        val executionCounter = AtomicInteger(0)
        val cmdId = "cmd_duplicate_test"

        // First execution
        val result1 = cache.executeOrAwait(cmdId) {
            executionCounter.incrementAndGet()
            WindowsAgentClient.CommandResultPayload(
                commandId = cmdId,
                status = "success",
                action = "SET_VOLUME",
                data = mapOf("volume" to 75),
                executionTimeMs = 30
            )
        }

        // Duplicate call with SAME commandId within TTL
        val result2 = cache.executeOrAwait(cmdId) {
            executionCounter.incrementAndGet()
            WindowsAgentClient.CommandResultPayload(
                commandId = cmdId,
                status = "success",
                action = "SET_VOLUME",
                data = mapOf("volume" to 75),
                executionTimeMs = 30
            )
        }

        assertEquals(1, executionCounter.get()) // MUST NOT re-execute
        assertEquals(result1.commandId, result2.commandId)
        assertEquals(result1.data["volume"], result2.data["volume"])
    }

    @Test
    fun test03_duplicateCommandAfterTTLExecutesAgain() = runTest {
        val executionCounter = AtomicInteger(0)
        val cmdId = "cmd_ttl_expired_test"

        // First execution
        cache.executeOrAwait(cmdId) {
            executionCounter.incrementAndGet()
            WindowsAgentClient.CommandResultPayload(
                commandId = cmdId,
                status = "success",
                action = "OPEN_APP",
                data = mapOf("app" to "YouTube")
            )
        }

        assertEquals(1, executionCounter.get())

        // Wait for TTL (200ms) to expire on real wall-clock time
        Thread.sleep(250)


        // Second execution after expiration
        cache.executeOrAwait(cmdId) {
            executionCounter.incrementAndGet()
            WindowsAgentClient.CommandResultPayload(
                commandId = cmdId,
                status = "success",
                action = "OPEN_APP",
                data = mapOf("app" to "YouTube")
            )
        }

        assertEquals(2, executionCounter.get())
    }

    @Test
    fun test04_concurrentDuplicatesShareSingleInFlightExecution() = runTest {
        val executionCounter = AtomicInteger(0)
        val cmdId = "cmd_concurrent_race_test"

        // Launch 10 concurrent coroutines attempting to execute the same command_id simultaneously
        val deferredResults = (1..10).map {
            async(Dispatchers.Default) {
                cache.executeOrAwait(cmdId) {
                    delay(100) // Simulate in-flight I/O latency
                    executionCounter.incrementAndGet()
                    WindowsAgentClient.CommandResultPayload(
                        commandId = cmdId,
                        status = "success",
                        action = "TAKE_SCREENSHOT",
                        data = mapOf("path" to "/sdcard/screenshot.png")
                    )
                }
            }
        }

        val results = deferredResults.awaitAll()

        // Verify only 1 physical execution took place
        assertEquals(1, executionCounter.get())
        assertEquals(10, results.size)
        results.forEach { res ->
            assertEquals("success", res.status)
            assertEquals("TAKE_SCREENSHOT", res.action)
            assertEquals(cmdId, res.commandId)
        }
    }

    @Test
    fun test05_lruCacheMaintainsMaximumSizeBoundary() = runTest {
        val smallCache = CommandIdempotencyCache(maxEntries = 3, ttlMs = 60000L)

        // Insert 5 different command IDs
        (1..5).forEach { i ->
            smallCache.storeResult(
                "cmd_$i",
                WindowsAgentClient.CommandResultPayload(
                    commandId = "cmd_$i",
                    status = "success",
                    action = "ACTION_$i"
                )
            )
        }

        // Cache size must not exceed maxEntries (3)
        assertEquals(3, smallCache.size())

        // The oldest entries (cmd_1 and cmd_2) should have been evicted
        assertNull(smallCache.getCachedResult("cmd_1"))
        assertNull(smallCache.getCachedResult("cmd_2"))
        assertNotNull(smallCache.getCachedResult("cmd_3"))
        assertNotNull(smallCache.getCachedResult("cmd_4"))
        assertNotNull(smallCache.getCachedResult("cmd_5"))
    }

    @Test
    fun test06_commandResultPreservesCorrelationId() {
        val commandId = "cmd_corr_" + UUID.randomUUID().toString().take(8)
        val payload = WindowsAgentClient.CommandResultPayload(
            commandId = commandId,
            status = "success",
            action = "GET_BATTERY_STATUS",
            data = mapOf("battery" to 85, "is_charging" to false),
            error = null,
            executionTimeMs = 12
        )

        assertEquals(commandId, payload.commandId)
        assertEquals("success", payload.status)
        assertEquals(85, payload.data["battery"])
    }

    @Test
    fun test07_structuredErrorPayloadFormatting() {
        val payload = WindowsAgentClient.CommandResultPayload(
            commandId = "cmd_err_01",
            status = "failed",
            action = "UNLOCK_DEVICE",
            data = emptyMap(),
            error = WindowsAgentClient.CommandError(
                code = "DEVICE_BUSY",
                message = "Device screen is already active and transitioning",
                details = mapOf("reason" to "transition_in_progress")
            ),
            executionTimeMs = 5
        )

        assertEquals("failed", payload.status)
        assertNotNull(payload.error)
        assertEquals("DEVICE_BUSY", payload.error?.code)
        assertEquals("Device screen is already active and transitioning", payload.error?.message)
        assertEquals("transition_in_progress", payload.error?.details?.get("reason"))
    }

    @Test
    fun test08_cacheThreadSafety() = runTest {
        val threadCache = CommandIdempotencyCache(maxEntries = 100, ttlMs = 60000L)
        val jobs = (1..50).map { i ->
            launch(Dispatchers.IO) {
                threadCache.storeResult(
                    "cmd_thread_$i",
                    WindowsAgentClient.CommandResultPayload(
                        commandId = "cmd_thread_$i",
                        status = "success",
                        action = "TEST"
                    )
                )
                val retrieved = threadCache.getCachedResult("cmd_thread_$i")
                assertNotNull(retrieved)
            }
        }
        jobs.joinAll()
        assertEquals(50, threadCache.size())
    }

    @Test
    fun test09_protocolTimeoutReturnsUnverifiedFailure() = runTest {
        val client = WindowsAgentClient(ApplicationProvider.getApplicationContext())
        val payload = WindowsAgentClient.CommandRequestPayload(
            commandId = "cmd_timeout",
            tool = "test",
            action = "TEST",
            timeoutMs = 20
        )

        val result = client.executeWithProtocolTimeout(payload) {
            delay(200)
            WindowsAgentClient.CommandResultPayload(
                commandId = payload.commandId,
                status = "success",
                action = payload.action
            )
        }

        assertEquals("failed", result.status)
        assertEquals("EXECUTION_TIMEOUT", result.error?.code)
        assertTrue(result.error?.message?.contains("outcome may be unknown") == true)
    }
}
