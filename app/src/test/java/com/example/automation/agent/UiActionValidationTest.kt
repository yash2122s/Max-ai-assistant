package com.example.automation.agent

import android.graphics.Rect
import com.example.service.PrunedNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UiActionValidationTest {

    @Test
    fun missingCoordinatesAreRejectedWithoutModelDefaults() {
        assertFalse(UiAction(type = UiAction.Type.TAP).validate(1080, 2400))
        assertFalse(UiAction(type = UiAction.Type.SWIPE, x = 10, y = 10, endX = 20).validate(1080, 2400))
    }

    @Test
    fun negativeCoordinatesAreRejected() {
        assertFalse(UiAction(type = UiAction.Type.TAP, x = -1, y = 10).validate(1080, 2400))
        assertFalse(UiAction(type = UiAction.Type.SWIPE, x = 10, y = 10, endX = 20, endY = -1).validate(1080, 2400))
    }

    @Test
    fun coordinatesMustStayInsideDisplay() {
        assertFalse(UiAction(type = UiAction.Type.TAP, x = 1080, y = 10).validate(1080, 2400))
        assertFalse(UiAction(type = UiAction.Type.TAP, x = 10, y = 2400).validate(1080, 2400))
        assertTrue(UiAction(type = UiAction.Type.TAP, x = 1079, y = 2399).validate(1080, 2400))
    }

    @Test
    fun coordinatesOutsideAvailableObservationBoundsAreRejected() {
        val observation = UiObservation(
            screenshotJpeg = null,
            prunedElements = listOf(
                PrunedNode(
                    nodeId = "button",
                    className = "android.widget.Button",
                    boundsInScreen = Rect(100, 200, 300, 400)
                )
            ),
            foregroundPackage = "com.example.target",
            visibleText = "",
            clickableElements = emptyList()
        )

        assertFalse(UiAction(type = UiAction.Type.TAP, x = 50, y = 250).validate(1080, 2400, observation))
        assertTrue(UiAction(type = UiAction.Type.TAP, x = 200, y = 300).validate(1080, 2400, observation))
    }
}
