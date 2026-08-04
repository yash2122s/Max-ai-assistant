package com.example.automation.tools

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class UsageStatsTool : Tool {
    private val TAG = "UsageStatsTool"
    override val name: String = "usage_stats"
    override val supportedActions: Set<String> = setOf("GET_APP_USAGE")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    private fun isUsageAccessGranted(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult = withContext(Dispatchers.IO) {
        if (!isUsageAccessGranted(context)) {
            try {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch Usage Access settings", e)
            }
            return@withContext ToolResult(
                success = false,
                toolName = name,
                errorCode = "USAGE_ACCESS_DENIED",
                message = "App Usage history requires Usage Access permission. I have launched the settings screen, please enable it."
            )
        }

        try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val calendar = java.util.Calendar.getInstance()
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
            calendar.set(java.util.Calendar.MINUTE, 0)
            calendar.set(java.util.Calendar.SECOND, 0)
            calendar.set(java.util.Calendar.MILLISECOND, 0)
            val startTime = calendar.timeInMillis
            val endTime = System.currentTimeMillis()

            val statsList = usageStatsManager.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )

            if (statsList.isNullOrEmpty()) {
                return@withContext ToolResult(
                    success = true,
                    toolName = name,
                    message = "No application usage history recorded for today.",
                    metadata = JSONObject().apply { put("apps", JSONArray()) },
                    verificationRequired = false
                )
            }

            val pm = context.packageManager
            val usageMap = mutableMapOf<String, Long>()
            
            statsList.forEach { stats ->
                val time = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    stats.totalTimeVisible
                } else {
                    stats.totalTimeInForeground
                }
                if (time > 0) {
                    usageMap[stats.packageName] = (usageMap[stats.packageName] ?: 0L) + time
                }
            }

            val appUsageList = usageMap.map { (packageName, foregroundTime) ->
                val label = try {
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    packageName.substringAfterLast('.')
                }
                JSONObject().apply {
                    put("appName", label)
                    put("packageName", packageName)
                    put("foregroundTimeMs", foregroundTime)
                    put("foregroundMinutes", foregroundTime / (1000 * 60))
                }
            }.sortedByDescending { it.getLong("foregroundTimeMs") }
             .take(5)

            val array = JSONArray()
            appUsageList.forEach { array.put(it) }

            val message = "App Usage stats for today retrieved successfully."
            ToolResult(
                success = true,
                toolName = name,
                message = message,
                metadata = JSONObject().apply { put("apps", array) },
                verificationRequired = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error querying usage statistics", e)
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "USAGE_ERROR",
                message = e.message ?: "Failed to query app usage history"
            )
        }
    }
}
