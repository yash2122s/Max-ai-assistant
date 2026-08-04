package com.example.automation.engine

import android.util.Log

enum class IntentRouteType {
    DIRECT_FAST_PATH,
    COMPLEX_VISION_PATH
}

data class IntentClassificationResult(
    val routeType: IntentRouteType,
    val targetToolName: String? = null,
    val extractedArgs: Map<String, Any> = emptyMap(),
    val rawPrompt: String,
    val classificationLatencyMs: Long
)

object IntentClassifier {
    private const val TAG = "IntentClassifier"

    // Fast-path keywords and intent matchers
    private val OPEN_APP_PATTERNS = listOf(
        Regex("^(?:open|launch|start|run)\\s+([a-zA-Z0-9\\s]+)$", RegexOption.IGNORE_CASE)
    )

    private val SYSTEM_TOGGLE_PATTERNS = mapOf(
        Regex(".*(?:turn on|enable|switch on)\\s+(?:flashlight|torch).*", RegexOption.IGNORE_CASE) to ("flashlight" to mapOf("enabled" to true)),
        Regex(".*(?:turn off|disable|switch off)\\s+(?:flashlight|torch).*", RegexOption.IGNORE_CASE) to ("flashlight" to mapOf("enabled" to false)),
        Regex(".*(?:turn on|enable)\\s+dnd.*", RegexOption.IGNORE_CASE) to ("set_dnd" to mapOf("enabled" to true)),
        Regex(".*(?:turn off|disable)\\s+dnd.*", RegexOption.IGNORE_CASE) to ("set_dnd" to mapOf("enabled" to false)),
        Regex(".*(?:get|check)\\s+battery.*", RegexOption.IGNORE_CASE) to ("get_battery_status" to emptyMap<String, Any>())
    )

    fun classify(prompt: String): IntentClassificationResult {
        val startTime = System.currentTimeMillis()
        val trimmed = prompt.trim()

        // 1. Check system toggles & deterministic tools
        for ((pattern, toolMapping) in SYSTEM_TOGGLE_PATTERNS) {
            if (pattern.matches(trimmed)) {
                val latency = System.currentTimeMillis() - startTime
                Log.d(TAG, "Fast-path match for '${toolMapping.first}' in ${latency}ms")
                return IntentClassificationResult(
                    routeType = IntentRouteType.DIRECT_FAST_PATH,
                    targetToolName = toolMapping.first,
                    extractedArgs = toolMapping.second,
                    rawPrompt = prompt,
                    classificationLatencyMs = latency
                )
            }
        }

        // 2. Check Open App intent
        for (pattern in OPEN_APP_PATTERNS) {
            val match = pattern.find(trimmed)
            if (match != null) {
                val appName = match.groupValues[1].trim()
                // Prevent routing complex vision prompts like "open settings and click wifi" to direct app open
                if (!appName.contains("and", ignoreCase = true) && !appName.contains("click", ignoreCase = true)) {
                    val latency = System.currentTimeMillis() - startTime
                    Log.d(TAG, "Fast-path app open match for '$appName' in ${latency}ms")
                    return IntentClassificationResult(
                        routeType = IntentRouteType.DIRECT_FAST_PATH,
                        targetToolName = "open_app",
                        extractedArgs = mapOf("app_name" to appName),
                        rawPrompt = prompt,
                        classificationLatencyMs = latency
                    )
                }
            }
        }

        // 3. Fallback to Complex Vision Path for visual/spatial/navigation prompts
        val latency = System.currentTimeMillis() - startTime
        Log.d(TAG, "Complex vision path designated for prompt in ${latency}ms")
        return IntentClassificationResult(
            routeType = IntentRouteType.COMPLEX_VISION_PATH,
            rawPrompt = prompt,
            classificationLatencyMs = latency
        )
    }
}
