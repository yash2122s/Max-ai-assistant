package com.example.automation.verification

import android.content.Context
import android.util.Log
import com.example.automation.actions.OpenAppAction
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.ToolResult
import com.example.knowledge.apps.InstalledAppsRepository

class OpenAppVerifier : Verifier {
    companion object {
        private const val TAG = "OpenAppVerifier"
        /** Total time budget for the polling verification window */
        const val MAX_VERIFICATION_WINDOW_MS = 3500L
        /** Interval between successive foreground-package polls */
        const val POLL_INTERVAL_MS = 150L
        /**
         * Initial grace period after startActivity() before the first
         * foreground-package check. The OS needs time to bring the target
         * activity to the top of the stack; checking earlier yields a
         * stale/empty package and causes a false failure.
         */
        private const val INITIAL_SETTLE_MS = 300L
    }

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
            ?: OpenAppAction.getHardcodedFallback(appName)

        if (expectedPackage == null) {
            return VerificationResult(
                success = false,
                reason = "Could not resolve app package name for '$appName'",
                retryRecommended = false,
                snapshot = snapshot
            )
        }

        val startTime = System.currentTimeMillis()
        var currentSnapshot = snapshot
        var currentPackage = resolveCurrentPackage(context, currentSnapshot)

        // 1. Immediate fresh check (0ms delay) — if target already matches, return SUCCESS immediately
        if (isPackageMatch(currentPackage, expectedPackage)) {
            Log.d(TAG, "✓ Immediate verification success for '$appName' ($expectedPackage) in 0ms (currentPackage='$currentPackage')")
            return VerificationResult(
                success = true,
                reason = "Live foreground package=$currentPackage expected=$expectedPackage",
                retryRecommended = false,
                snapshot = currentSnapshot
            )
        }

        Log.d(TAG, "Initial check did not match ('$currentPackage' != '$expectedPackage'). Starting adaptive polling up to ${MAX_VERIFICATION_WINDOW_MS}ms")

        // 2. Adaptive launch-aware polling loop (100ms interval, terminates immediately on match)
        while (true) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed + POLL_INTERVAL_MS > MAX_VERIFICATION_WINDOW_MS) {
                break
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }

            currentSnapshot = DeviceContext.capture(context)
            currentPackage = resolveCurrentPackage(context, currentSnapshot)

            if (isPackageMatch(currentPackage, expectedPackage)) {
                val elapsedMs = System.currentTimeMillis() - startTime
                Log.d(TAG, "✓ Polled verification success for '$appName' ($expectedPackage) in ${elapsedMs}ms (currentPackage='$currentPackage')")
                return VerificationResult(
                    success = true,
                    reason = "Live foreground package=$currentPackage expected=$expectedPackage",
                    retryRecommended = false,
                    snapshot = currentSnapshot
                )
            }
        }

        val finalElapsed = System.currentTimeMillis() - startTime
        val isFinalMatch = isPackageMatch(currentPackage, expectedPackage)

        Log.d(TAG, "Verification finished for '$appName': expected '$expectedPackage', got '$currentPackage', match=$isFinalMatch in ${finalElapsed}ms")

        val failureReason = if (currentPackage.isEmpty()) {
            "App '$appName' ($expectedPackage) did not enter foreground within ${MAX_VERIFICATION_WINDOW_MS}ms"
        } else {
            "Foreground package '$currentPackage' does not match expected '$expectedPackage'"
        }

        return VerificationResult(
            success = isFinalMatch,
            reason = if (isFinalMatch) "Live foreground package=$currentPackage expected=$expectedPackage" else failureReason,
            retryRecommended = false,
            snapshot = currentSnapshot
        )
    }

    private fun resolveCurrentPackage(context: Context, snapshot: DeviceContext): String {
        // Fallback 1: Live accessibility root node
        val activeRootPkg = com.example.service.JarvisAccessibilityService.getActiveRoot()?.packageName?.toString()?.trim().orEmpty()
        if (activeRootPkg.isNotEmpty()) return activeRootPkg

        val service = com.example.service.JarvisAccessibilityService.instance
        val livePkg = service?.rootInActiveWindow?.packageName?.toString()?.trim().orEmpty()
        if (livePkg.isNotEmpty()) return livePkg

        // Fallback 2: Active or focused accessibility window root
        val windowPkg = com.example.service.JarvisAccessibilityService.getActiveWindows()?.firstOrNull { it.isFocused || it.isActive }?.root?.packageName?.toString()?.trim().orEmpty()
        if (windowPkg.isNotEmpty()) return windowPkg

        return ""
    }

    private fun isPackageMatch(currentPackage: String, expectedPackage: String): Boolean {
        if (currentPackage.isEmpty() || expectedPackage.isEmpty()) return false
        return currentPackage == expectedPackage
    }
}
