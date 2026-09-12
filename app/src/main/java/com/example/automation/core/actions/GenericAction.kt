package com.example.automation.core.actions

import com.example.automation.agent.UiAction
import com.example.automation.state.ActionStep
import org.json.JSONObject

/**
 * Universal automation primitives for the generic AI UI agent.
 * App-specific actions (e.g. SEARCH_YOUTUBE, OPEN_WHATSAPP_CHAT) are prohibited.
 */
enum class GenericActionType {
    OPEN_APP,
    CLICK_ELEMENT,
    TYPE_TEXT,
    PRESS_ENTER,
    SCROLL,
    BACK,
    WAIT,
    LONG_CLICK,
    SWIPE
}

/**
 * Universal action representation emitted by the Brain (Planner) or normalized
 * from higher-level user goals before being dispatched to the MAX ExecutionEngine.
 */
data class GenericAction(
    val type: GenericActionType,
    val target: String? = null,
    val text: String? = null,
    val resourceId: String? = null,
    val contentDescription: String? = null,
    val index: Int? = null,
    val direction: String? = null,
    val packageName: String? = null,
    val confidence: Float = 0f
) {
    /**
     * Converts this GenericAction into an existing MAX pipeline [ActionStep].
     * Preserves compatibility with GoalPlanner, ActionAuthorizer, and ExecutionEngine.
     */
    fun toActionStep(stepId: String = "step_${System.currentTimeMillis()}"): ActionStep {
        val actionTypeStr = when (type) {
            GenericActionType.OPEN_APP -> "OPEN_APP"
            GenericActionType.CLICK_ELEMENT -> if (index != null) "CLICK_INDEX" else "CLICK_ELEMENT"
            GenericActionType.TYPE_TEXT -> "TYPE_TEXT"
            GenericActionType.PRESS_ENTER -> "PRESS_ENTER"
            GenericActionType.SCROLL -> "SCROLL_SCREEN"
            GenericActionType.BACK -> "SYSTEM_ACTION"
            GenericActionType.WAIT -> "WAIT"
            GenericActionType.LONG_CLICK -> "LONG_CLICK"
            GenericActionType.SWIPE -> "SWIPE"
        }

        val params = mutableMapOf<String, String>()
        target?.let { params["target"] = it }
        text?.let { params["text"] = it }
        direction?.let { params["direction"] = it }
        packageName?.let { params["package"] = it; params["app_name"] = it }
        index?.let { params["index"] = it.toString() }

        val resolvedTargetText = when (type) {
            GenericActionType.BACK -> "back"
            GenericActionType.OPEN_APP -> packageName ?: target
            GenericActionType.TYPE_TEXT -> text ?: target
            GenericActionType.PRESS_ENTER -> "enter"
            GenericActionType.SCROLL -> direction ?: "DOWN"
            else -> target ?: text
        }

        return ActionStep(
            stepId = stepId,
            actionType = actionTypeStr,
            targetText = resolvedTargetText,
            resourceId = resourceId,
            contentDescription = contentDescription,
            params = params
        )
    }

    /**
     * Adapter to convert to lower-level [UiAction] if direct gesture / Shizuku dispatch is needed.
     */
    fun toUiAction(x: Int? = null, y: Int? = null, endX: Int? = null, endY: Int? = null, durationMs: Long = 300L): UiAction {
        val uiType = when (type) {
            GenericActionType.OPEN_APP -> UiAction.Type.LAUNCH_APP
            GenericActionType.CLICK_ELEMENT -> UiAction.Type.TAP
            GenericActionType.LONG_CLICK -> UiAction.Type.LONG_PRESS
            GenericActionType.TYPE_TEXT -> UiAction.Type.TYPE
            GenericActionType.PRESS_ENTER -> UiAction.Type.KEY_ENTER
            GenericActionType.SCROLL -> UiAction.Type.SCROLL
            GenericActionType.SWIPE -> UiAction.Type.SWIPE
            GenericActionType.BACK -> UiAction.Type.KEY_BACK
            GenericActionType.WAIT -> UiAction.Type.FINISH
        }

        return UiAction(
            type = uiType,
            x = x,
            y = y,
            endX = endX,
            endY = endY,
            durationMs = durationMs,
            text = text ?: target,
            packageName = packageName ?: target,
            direction = direction,
            reason = "GenericAction: $type on ${target ?: text ?: packageName}"
        )
    }

    fun toJsonObject(): JSONObject {
        val json = JSONObject()
        json.put("type", type.name)
        target?.let { json.put("target", it) }
        text?.let { json.put("text", it) }
        resourceId?.let { json.put("resourceId", it) }
        contentDescription?.let { json.put("contentDescription", it) }
        index?.let { json.put("index", it) }
        direction?.let { json.put("direction", it) }
        packageName?.let { json.put("packageName", it) }
        if (confidence > 0f) json.put("confidence", confidence.toDouble())
        return json
    }

    companion object {
        fun fromJsonObject(json: JSONObject): GenericAction {
            val typeStr = json.optString("type").uppercase().trim()
            val type = try {
                GenericActionType.valueOf(typeStr)
            } catch (e: Exception) {
                when {
                    typeStr.contains("OPEN") || typeStr.contains("LAUNCH") -> GenericActionType.OPEN_APP
                    typeStr.contains("ENTER") && !typeStr.contains("TEXT") -> GenericActionType.PRESS_ENTER
                    typeStr.contains("CLICK") || typeStr.contains("TAP") || typeStr.contains("PRESS") -> GenericActionType.CLICK_ELEMENT
                    typeStr.contains("TYPE") || typeStr.contains("INPUT") || typeStr.contains("ENTER_TEXT") -> GenericActionType.TYPE_TEXT
                    typeStr.contains("SCROLL") -> GenericActionType.SCROLL
                    typeStr.contains("BACK") -> GenericActionType.BACK
                    typeStr.contains("WAIT") || typeStr.contains("SLEEP") || typeStr.contains("DELAY") -> GenericActionType.WAIT
                    typeStr.contains("LONG") -> GenericActionType.LONG_CLICK
                    typeStr.contains("SWIPE") -> GenericActionType.SWIPE
                    else -> GenericActionType.CLICK_ELEMENT
                }
            }

            return GenericAction(
                type = type,
                target = json.optString("target").takeIf { it.isNotEmpty() },
                text = json.optString("text").takeIf { it.isNotEmpty() },
                resourceId = json.optString("resourceId").takeIf { it.isNotEmpty() }
                    ?: json.optString("resource_id").takeIf { it.isNotEmpty() }
                    ?: json.optString("id").takeIf { it.isNotEmpty() },
                contentDescription = json.optString("contentDescription").takeIf { it.isNotEmpty() }
                    ?: json.optString("content_description").takeIf { it.isNotEmpty() }
                    ?: json.optString("description").takeIf { it.isNotEmpty() },
                index = if (json.has("index")) json.optInt("index") else null,
                direction = json.optString("direction").takeIf { it.isNotEmpty() },
                packageName = json.optString("packageName").takeIf { it.isNotEmpty() }
                    ?: json.optString("package_name").takeIf { it.isNotEmpty() }
                    ?: json.optString("pkg").takeIf { it.isNotEmpty() },
                confidence = json.optDouble("confidence", 0.0).toFloat()
            )
        }

        /**
         * Converts an existing [ActionStep] into a [GenericAction].
         */
        fun fromActionStep(step: ActionStep): GenericAction {
            val type = when (step.actionType.uppercase().trim()) {
                "OPEN_APP" -> GenericActionType.OPEN_APP
                "CLICK_ELEMENT", "CLICK_TEXT", "CLICK_INDEX", "CLICK_UI", "FIND_ELEMENT" -> GenericActionType.CLICK_ELEMENT
                "TYPE_TEXT" -> GenericActionType.TYPE_TEXT
                "PRESS_ENTER", "IME_ENTER", "ENTER_KEY" -> GenericActionType.PRESS_ENTER
                "SCROLL_SCREEN", "SCROLL_DOWN", "SCROLL_UP" -> GenericActionType.SCROLL
                "SYSTEM_ACTION" -> if (step.targetText?.equals("back", ignoreCase = true) == true) GenericActionType.BACK else GenericActionType.CLICK_ELEMENT
                "WAIT" -> GenericActionType.WAIT
                "LONG_CLICK" -> GenericActionType.LONG_CLICK
                "SWIPE" -> GenericActionType.SWIPE
                else -> GenericActionType.CLICK_ELEMENT
            }

            return GenericAction(
                type = type,
                target = step.targetText ?: step.params["target"] ?: step.params["app_name"],
                text = step.params["text"] ?: step.targetText,
                resourceId = step.resourceId ?: step.params["resourceId"],
                contentDescription = step.contentDescription ?: step.params["description"],
                index = step.params["index"]?.toIntOrNull(),
                direction = step.params["direction"] ?: if (step.actionType.contains("DOWN")) "DOWN" else if (step.actionType.contains("UP")) "UP" else null,
                packageName = step.params["package"] ?: step.params["app_name"] ?: step.targetText
            )
        }
    }
}
