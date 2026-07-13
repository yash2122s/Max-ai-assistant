package com.example.automation

import android.util.Log
import rikka.shizuku.Shizuku

object ShizukuExecutor {

    private const val TAG = "ShizukuExecutor"

    private val blockedCommands = listOf(
        "rm -rf",
        "reboot",
        "format",
        "wipe",
        "mkfs",
        "dd"
    )

    fun isSafeCommand(cmd: String): Boolean {
        val lowerCmd = cmd.lowercase()
        return blockedCommands.none { lowerCmd.contains(it) }
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
