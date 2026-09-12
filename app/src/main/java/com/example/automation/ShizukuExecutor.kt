package com.example.automation

import android.util.Log
import rikka.shizuku.Shizuku

object ShizukuExecutor {

    private const val TAG = "ShizukuExecutor"

    private val allowedPrefixes = listOf(
        "pm list packages",
        "wm size",
        "getprop",
        "dumpsys",
        "input keyevent",
        "input text",
        "input tap",
        "input swipe",
        "input "
    )

    fun isSafeCommand(cmd: String): Boolean {
        val lowerCmd = cmd.trim().lowercase()
        // Check for shell injection characters in input commands
        if (lowerCmd.startsWith("input ")) {
            if (cmd.contains(";") || cmd.contains("&") || cmd.contains("|") || cmd.contains("`") || cmd.contains("$")) {
                return false
            }
        }
        return allowedPrefixes.any { lowerCmd.startsWith(it) }
    }

    fun sendKeyEvent(keyCode: Int): Boolean {
        if (keyCode <= 0) return false
        val res = runCommand("input keyevent $keyCode")
        return !res.startsWith("Security Block") && !res.startsWith("Failed") && !res.startsWith("Remote error") && !res.startsWith("Shizuku is not available")
    }

    fun sendText(text: String): Boolean {
        if (text.isEmpty()) return false
        // Replace spaces with %s for adb input text
        val safeText = text.replace(" ", "%s").replace("'", "").replace("\"", "")
        val res = runCommand("input text $safeText")
        return !res.startsWith("Security Block") && !res.startsWith("Failed") && !res.startsWith("Remote error") && !res.startsWith("Shizuku is not available")
    }

    fun sendTap(x: Float, y: Float): Boolean {
        val res = runCommand("input tap ${x.toInt()} ${y.toInt()}")
        return !res.startsWith("Security Block") && !res.startsWith("Failed") && !res.startsWith("Remote error") && !res.startsWith("Shizuku is not available")
    }

    fun runCommand(command: String): String {
        Log.d(TAG, "Attempting to run command: $command")
        if (!isSafeCommand(command)) {
            val errorMsg = "Security Block: Command contains blocked keywords!"
            Log.e(TAG, errorMsg)
            return errorMsg
        }

        if (!ShizukuManager.isShizukuAvailable()) {
            val errorMsg = "Shizuku is not available or running."
            Log.w(TAG, errorMsg)
            return errorMsg
        }

        if (!ShizukuManager.isPermissionGranted()) {
            val errorMsg = "Shizuku permission has not been granted."
            Log.w(TAG, errorMsg)
            return errorMsg
        }

        return ShizukuShellPlugin.runCommand(command)
    }
}
