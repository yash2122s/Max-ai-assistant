package com.example.automation.skills.compiler

import com.example.automation.skills.model.SkillDefinition
import com.example.automation.skills.model.SkillInput

/**
 * Result of binding runtime inputs to declared skill parameters.
 */
data class BoundInputsResult(
    val success: Boolean,
    val boundValues: Map<String, Any?> = emptyMap(),
    val errorMessage: String? = null
)

/**
 * Deterministic Parameter Binder for MAX Automation Skills.
 * Replaces {{variable}} and ${inputs.variable} placeholders without executing code.
 * Enforces type contracts and required parameter constraints.
 */
object SkillInputBinder {

    private val PLACEHOLDER_REGEX = Regex("""\{\{([a-zA-Z0-9_-]+)\}\}|\$\{inputs\.([a-zA-Z0-9_-]+)\}""")

    /**
     * Binds user-provided runtime values against declared skill inputs.
     */
    fun bindInputs(
        skill: SkillDefinition,
        providedValues: Map<String, Any?>
    ): BoundInputsResult {
        val boundMap = mutableMapOf<String, Any?>()
        val declaredInputs = skill.inputs

        for (inputDef in declaredInputs) {
            val key = inputDef.name
            val rawValue = providedValues[key]

            if (rawValue == null || (rawValue is String && rawValue.isBlank())) {
                val defaultValStr = inputDef.defaultValue?.toString()
                if (inputDef.required) {
                    if (!defaultValStr.isNullOrBlank()) {
                        boundMap[key] = coerceType(defaultValStr, inputDef.type)
                    } else {
                        return BoundInputsResult(
                            success = false,
                            errorMessage = "Missing mandatory input parameter: '$key' (${inputDef.description})"
                        )
                    }
                } else {
                    boundMap[key] = defaultValStr?.let { coerceType(it, inputDef.type) }
                }
            } else {
                // Type validation and coercion
                try {
                    boundMap[key] = validateAndCoerce(rawValue, inputDef.type, key)
                } catch (e: Exception) {
                    return BoundInputsResult(
                        success = false,
                        errorMessage = "Invalid type for parameter '$key': ${e.message}"
                    )
                }
            }
        }

        // Also pass through any additional context values that match safe identifiers
        for ((k, v) in providedValues) {
            if (!boundMap.containsKey(k) && k.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
                boundMap[k] = v
            }
        }

        return BoundInputsResult(success = true, boundValues = boundMap)
    }

    /**
     * Replaces placeholders in a template string using bound values.
     */
    fun interpolateString(template: String, boundValues: Map<String, Any?>): String {
        return PLACEHOLDER_REGEX.replace(template) { matchResult ->
            val varName = matchResult.groupValues[1].ifEmpty { matchResult.groupValues[2] }
            val value = boundValues[varName]
            value?.toString() ?: matchResult.value
        }
    }

    /**
     * Recursively interpolates placeholders in maps/arguments.
     */
    @Suppress("UNCHECKED_CAST")
    fun interpolateArguments(
        arguments: Map<String, Any?>,
        boundValues: Map<String, Any?>
    ): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        for ((key, value) in arguments) {
            result[key] = when (value) {
                is String -> interpolateString(value, boundValues)
                is Map<*, *> -> interpolateArguments(value as Map<String, Any?>, boundValues)
                is List<*> -> value.map { item ->
                    if (item is String) interpolateString(item, boundValues) else item
                }
                else -> value
            }
        }
        return result
    }

    private fun coerceType(valueStr: String, type: String): Any {
        return when (type.lowercase()) {
            "integer", "int" -> valueStr.toLongOrNull() ?: 0L
            "boolean", "bool" -> valueStr.equals("true", ignoreCase = true)
            "number", "float", "double" -> valueStr.toDoubleOrNull() ?: 0.0
            else -> valueStr
        }
    }

    private fun validateAndCoerce(value: Any, expectedType: String, paramName: String): Any {
        return when (expectedType.lowercase()) {
            "integer", "int" -> {
                when (value) {
                    is Number -> value.toLong()
                    is String -> value.toLongOrNull() ?: throw IllegalArgumentException("Expected integer, got '$value'")
                    else -> throw IllegalArgumentException("Expected integer, got ${value::class.java.simpleName}")
                }
            }
            "boolean", "bool" -> {
                when (value) {
                    is Boolean -> value
                    is String -> value.toBooleanStrictOrNull() ?: throw IllegalArgumentException("Expected boolean ('true'/'false'), got '$value'")
                    else -> throw IllegalArgumentException("Expected boolean, got ${value::class.java.simpleName}")
                }
            }
            "number", "float", "double" -> {
                when (value) {
                    is Number -> value.toDouble()
                    is String -> value.toDoubleOrNull() ?: throw IllegalArgumentException("Expected number, got '$value'")
                    else -> throw IllegalArgumentException("Expected number, got ${value::class.java.simpleName}")
                }
            }
            else -> value.toString()
        }
    }
}
