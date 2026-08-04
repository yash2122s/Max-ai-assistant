package com.example.automation

import kotlin.system.exitProcess
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class ShizukuShellService : IShizukuShell.Stub() {
    override fun runCommand(command: String): String {
        android.util.Log.d("ShizukuShellService", "Executing command: $command")
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val outputReader = process.inputStream.bufferedReader()
            val errorReader = process.errorStream.bufferedReader()
            
            val output = outputReader.readText()
            val error = errorReader.readText()
            
            val exitCode = process.waitFor()
            android.util.Log.d("ShizukuShellService", "Exit Code: $exitCode")
            if (output.isNotBlank()) android.util.Log.d("ShizukuShellService", "stdout: $output")
            if (error.isNotBlank()) android.util.Log.e("ShizukuShellService", "stderr: $error")

            if (exitCode == 0) {
                if (output.isNotBlank()) output.trim() else "Success"
            } else {
                "Error (Exit Code $exitCode): ${error.trim()}"
            }
        } catch (e: Exception) {
            "Failed: ${e.localizedMessage}"
        }
    }

    override fun runCommandBytes(command: String): ByteArray {
        android.util.Log.d("ShizukuShellService", "Executing byte command: $command")
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val bytes = process.inputStream.readBytes()
            process.waitFor()
            bytes
        } catch (e: Exception) {
            android.util.Log.e("ShizukuShellService", "Error reading bytes from command: $command", e)
            ByteArray(0)
        }
    }

    override fun destroy() {
        android.util.Log.d("ShizukuShellService", "ShizukuShellService stub destroyed.")
    }

}
