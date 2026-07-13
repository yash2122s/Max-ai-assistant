package com.example.automation.verification

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect

data class DeviceContext(
    val schemaVersion: Int = 1,
    val ui: UiSnapshot,
    val volumePercent: Int = 0,
    val brightnessPercent: Int = 0,
    val dndEnabled: Boolean = false,
    val isScreenOn: Boolean = true,
    val isMediaPlaying: Boolean = false,
    val isWifiEnabled: Boolean = false,
    val isBluetoothEnabled: Boolean = false,
    val batteryPercent: Int = 100,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun capture(context: Context, rootNode: AccessibilityNodeInfo? = null): DeviceContext {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

            val volume = audioManager?.let {
                val current = it.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (max > 0) (current * 100) / max else 0
            } ?: 0

            val brightness = try {
                val currentVal = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                (currentVal * 100) / 255
            } catch (e: Exception) {
                50
            }

            val dnd = notificationManager?.let {
                try {
                    if (it.isNotificationPolicyAccessGranted) {
                        val filter = it.currentInterruptionFilter
                        filter == NotificationManager.INTERRUPTION_FILTER_NONE ||
                                filter == NotificationManager.INTERRUPTION_FILTER_ALARMS ||
                                filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    } else false
                } catch (e: Exception) {
                    false
                }
            } ?: false

            val wifi = try {
                wifiManager?.isWifiEnabled ?: false
            } catch (e: SecurityException) {
                false
            }
            val battery = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100

            val nodes = mutableListOf<UiNode>()
            var pkgName = ""
            if (rootNode != null) {
                pkgName = rootNode.packageName?.toString().orEmpty()
                extractNodes(rootNode, nodes)
            }

            return DeviceContext(
                ui = UiSnapshot(
                    packageName = pkgName,
                    nodes = nodes
                ),
                volumePercent = volume,
                brightnessPercent = brightness,
                dndEnabled = dnd,
                isWifiEnabled = wifi,
                batteryPercent = battery
            )
        }

        private fun extractNodes(node: AccessibilityNodeInfo, list: MutableList<UiNode>) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            list.add(
                UiNode(
                    id = node.viewIdResourceName,
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    className = node.className?.toString().orEmpty(),
                    isClickable = node.isClickable,
                    bounds = bounds
                )
            )
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    extractNodes(child, list)
                }
            }
        }
    }
}
