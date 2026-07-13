package com.example.automation.verification

import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.ToolResult
import com.example.knowledge.apps.InstalledAppsRepository

class OpenAppVerifier : Verifier {
    override val supportedTools: Set<String> = setOf("open_app")

    override fun verify(
        context: Context,
        request: ExecutionRequest,
        result: ToolResult,
        snapshot: DeviceContext
    ): VerificationResult {
        val arguments = request.arguments
        val appName = arguments.get("app_name")?.asString
            ?: arguments.get("app")?.asString
            ?: arguments.get("appName")?.asString
            ?: ""

        if (appName.isEmpty()) {
            return VerificationResult(
                success = false,
                reason = "App name is missing in request",
                retryRecommended = false,
                snapshot = snapshot
            )
        }

        val expectedPackage = InstalledAppsRepository.findApp(context, appName)
            ?: getHardcodedFallback(appName)

        if (expectedPackage == null) {
            return VerificationResult(
                success = false,
                reason = "Could not resolve app package name for '$appName'",
                retryRecommended = false,
                snapshot = snapshot
            )
        }

        val currentPackage = snapshot.ui.packageName
        val isMatch = currentPackage.equals(expectedPackage, ignoreCase = true)
        
        Log.d("OpenAppVerifier", "Verification match for '$appName': expected '$expectedPackage', got '$currentPackage', match = $isMatch")

        return VerificationResult(
            success = isMatch,
            reason = if (isMatch) null else "Foreground package '$currentPackage' does not match expected '$expectedPackage'",
            retryRecommended = !isMatch,
            snapshot = snapshot
        )
    }

    private fun getHardcodedFallback(appName: String): String? {
        return when (appName.lowercase()) {
            "whatsapp" -> "com.whatsapp"
            "whatsapp business" -> "com.whatsapp.w4b"
            "instagram" -> "com.instagram.android"
            "threads" -> "com.instagram.barcelona"
            "telegram" -> "org.telegram.messenger"
            "youtube" -> "com.google.android.youtube"
            "settings" -> "com.android.settings"
            else -> null
        }
    }
}
