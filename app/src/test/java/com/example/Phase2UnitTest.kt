package com.example

import org.junit.Assert.assertEquals
import org.junit.Test

class Phase2UnitTest {

    @Test
    fun testBatteryTemperatureConversion() {
        val tempFromSensor = 354 // represents 35.4 degrees Celsius in tenths of a degree
        val converted = tempFromSensor / 10.0
        assertEquals(35.4, converted, 0.01)

        val negativeTemp = -10
        val negativeConverted = negativeTemp / 10.0
        assertEquals(-1.0, negativeConverted, 0.01)
    }

    @Test
    fun testBrightnessPercentageConversion() {
        val maxBrightness = 255
        val halfBrightness = 127
        val zeroBrightness = 0

        val maxPercent = (maxBrightness * 100 / 255)
        val halfPercent = (halfBrightness * 100 / 255)
        val zeroPercent = (zeroBrightness * 100 / 255)

        assertEquals(100, maxPercent)
        assertEquals(49, halfPercent)
        assertEquals(0, zeroPercent)
    }

    @Test
    fun testSettingsTargetMapping() {
        val targetIntentMap = mapOf(
            "wifi" to "android.settings.WIFI_SETTINGS",
            "bluetooth" to "android.settings.BLUETOOTH_SETTINGS",
            "battery" to "android.settings.BATTERY_SAVER_SETTINGS",
            "display" to "android.settings.DISPLAY_SETTINGS",
            "accessibility" to "android.settings.ACCESSIBILITY_SETTINGS",
            "location" to "android.settings.LOCATION_SOURCE_SETTINGS",
            "apps" to "android.settings.APPLICATION_SETTINGS",
            "airplane" to "android.settings.AIRPLANE_MODE_SETTINGS",
            "sound" to "android.settings.SOUND_SETTINGS",
            "date_time" to "android.settings.DATE_SETTINGS",
            "main" to "android.settings.SETTINGS"
        )

        assertEquals("android.settings.WIFI_SETTINGS", targetIntentMap["wifi"])
        assertEquals("android.settings.BLUETOOTH_SETTINGS", targetIntentMap["bluetooth"])
        assertEquals("android.settings.BATTERY_SAVER_SETTINGS", targetIntentMap["battery"])
        assertEquals("android.settings.SETTINGS", targetIntentMap["main"])
        assertEquals(null, targetIntentMap["invalid_target"])
    }
}
