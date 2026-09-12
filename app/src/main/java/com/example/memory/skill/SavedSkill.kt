package com.example.memory.skill

import com.example.automation.state.ActionStep
import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a serialised "skill" — a named sequence of [ActionStep]s that was
 * successfully executed at least once and can be replayed for similar future goals.
 */
data class SavedSkill(
    val id: String,
    val task: String,
    val taskKeywords: List<String>,
    val successCount: Int,
    val failCount: Int,
    val lastUsed: Long,           // epoch millis
    val steps: List<ActionStep>
) {
    /**
     * A skill is reliable if it has at least one successful execution AND
     * its failure rate is below 30 %.  Handles the division-by-zero edge case
     * (zero total executions → not reliable).
     */
    val isReliable: Boolean
        get() {
            val total = successCount + failCount
            return successCount >= 1 && (if (total == 0) false else (failCount.toDouble() / total) < 0.30)
        }

    // ── Serialisation ─────────────────────────────────────────────────────────

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("task", task)
        put("task_keywords", JSONArray(taskKeywords))
        put("success_count", successCount)
        put("fail_count", failCount)
        put("last_used", lastUsed)
        val stepsArray = JSONArray()
        steps.forEach { step ->
            stepsArray.put(JSONObject().apply {
                put("step_id", step.stepId)
                put("action_type", step.actionType)
                step.targetText?.let { put("target_text", it) }
                step.resourceId?.let { put("resource_id", it) }
                step.contentDescription?.let { put("content_description", it) }
                step.fallbackX?.let { put("fallback_x", it) }
                step.fallbackY?.let { put("fallback_y", it) }
            })
        }
        put("steps", stepsArray)
    }

    companion object {
        fun fromJson(json: JSONObject): SavedSkill {
            val keywords = mutableListOf<String>()
            val kwArray = json.optJSONArray("task_keywords")
            if (kwArray != null) {
                for (i in 0 until kwArray.length()) keywords.add(kwArray.getString(i))
            }

            val steps = mutableListOf<ActionStep>()
            val stepsArray = json.optJSONArray("steps")
            if (stepsArray != null) {
                for (i in 0 until stepsArray.length()) {
                    val s = stepsArray.getJSONObject(i)
                    steps.add(
                        ActionStep(
                            stepId = s.optString("step_id", "step_$i"),
                            actionType = s.getString("action_type"),
                            targetText = s.optString("target_text").takeIf { it.isNotBlank() },
                            resourceId = s.optString("resource_id").takeIf { it.isNotBlank() },
                            contentDescription = s.optString("content_description").takeIf { it.isNotBlank() },
                            fallbackX = if (s.has("fallback_x")) s.getDouble("fallback_x").toFloat() else null,
                            fallbackY = if (s.has("fallback_y")) s.getDouble("fallback_y").toFloat() else null,
                        )
                    )
                }
            }

            return SavedSkill(
                id = json.getString("id"),
                task = json.getString("task"),
                taskKeywords = keywords,
                successCount = json.optInt("success_count", 0),
                failCount = json.optInt("fail_count", 0),
                lastUsed = json.optLong("last_used", System.currentTimeMillis()),
                steps = steps
            )
        }
    }
}
