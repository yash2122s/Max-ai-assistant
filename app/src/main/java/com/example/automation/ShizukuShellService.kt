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

    override fun destroy() {
        exitProcess(0)
    }
}
