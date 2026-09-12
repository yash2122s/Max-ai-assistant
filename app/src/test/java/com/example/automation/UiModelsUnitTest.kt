package com.example.automation

import android.graphics.Rect
import com.example.automation.models.TargetResolutionMethod
import com.example.automation.models.UiStep
import com.example.automation.models.UiTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UiModelsUnitTest {

    @Test
    fun testUiTargetCreation() {
        val rect = Rect(10, 20, 100, 200)
        val target = UiTarget(
            resourceId = "com.google.android.apps.maps:id/search_box",
            text = "Search",
            contentDescription = "Search Google Maps",
            role = "android.widget.EditText",
            bounds = rect,
            confidence = 0.98f,
            method = TargetResolutionMethod.RESOURCE_ID
        )

        assertEquals("com.google.android.apps.maps:id/search_box", target.resourceId)
        assertEquals("Search", target.text)
        assertEquals("Search Google Maps", target.contentDescription)
        assertEquals("android.widget.EditText", target.role)
        assertEquals(rect, target.bounds)
        assertEquals(0.98f, target.confidence, 0.001f)
        assertEquals(TargetResolutionMethod.RESOURCE_ID, target.method)
    }

    @Test
    fun testUiStepVariants() {
        val openApp = UiStep.OpenApp("Google Maps", "com.google.android.apps.maps")
        assertEquals("Google Maps", openApp.appName)
        assertEquals("com.google.android.apps.maps", openApp.packageName)

        val tap = UiStep.Tap(target = "Search bar", resourceId = "search_omnibox")
        assertEquals("Search bar", tap.target)

        val type = UiStep.TypeText("Palakollu to Bhimavaram", target = "Search input")
        assertEquals("Palakollu to Bhimavaram", type.text)

        val enter = UiStep.PressEnter
        assertNotNull(enter)

        val back = UiStep.PressBack
        assertNotNull(back)

        val scroll = UiStep.Scroll(direction = "UP")
        assertEquals("UP", scroll.direction)

        val swipe = UiStep.Swipe(0, 500, 0, 100, 250)
        assertEquals(250L, swipe.durationMs)

        val longPress = UiStep.LongPress(target = "Item 1", durationMs = 1500)
        assertEquals(1500L, longPress.durationMs)

        val waitForUi = UiStep.WaitForUi(targetText = "Directions", timeoutMs = 2500)
        assertEquals("Directions", waitForUi.targetText)
        assertEquals(2500L, waitForUi.timeoutMs)

        val finish = UiStep.Finish(success = true, message = "Task completed")
        assertTrue(finish.success)
        assertEquals("Task completed", finish.message)
    }
}
