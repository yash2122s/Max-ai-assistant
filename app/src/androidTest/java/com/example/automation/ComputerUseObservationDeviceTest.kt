package com.example.automation

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.automation.computeruse.ComputerUseEngine
import com.example.automation.computeruse.ScreenObservationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Safe navigation only: no calls, messages, setting changes, or synthetic observations. */
@RunWith(AndroidJUnit4::class)
class ComputerUseObservationDeviceTest {
    @Test fun openSettingsAndVerifyRealSemanticForeground() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val started = System.nanoTime()
            val result = ComputerUseEngine.execute(context, "Open Settings", "Settings", maxSteps = 4)
            assertTrue("Settings task failed: ${result.error}", result.success)
            val observeStarted = System.nanoTime()
            val observation = ScreenObservationProvider.captureObservation(context, captureScreenshot = false)
            assertTrue("Observation must be fresh", observation.isFresh())
            assertEquals("com.android.settings", observation.foregroundPackage)
            assertTrue("A real semantic tree is required", observation.interactiveElements.isNotEmpty() || observation.visibleTexts.isNotEmpty())
            assertNull(observation.screenshotPath)
            assertFalse(observation.hasScreenshot)
            Log.i("MaxLiveDeviceTest", "settings_task_ms=${(observeStarted - started) / 1_000_000} observation_ms=${(System.nanoTime() - observeStarted) / 1_000_000} screen_id=${observation.screenId}")
        }
    }
}
