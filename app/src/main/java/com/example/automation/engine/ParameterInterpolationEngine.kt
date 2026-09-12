package com.example.automation.engine

sealed class InterpolationResult {
    data class Success(val interpolatedParams: Map<String, String>) : InterpolationResult()
    data class Failure(val missingReference: String, val message: String) : InterpolationResult()
}

object ParameterInterpolationEngine {

    // Matches ${step_1.field} or $step_1.field or $step_1
    private val INTERPOLATION_REGEX = Regex("""(?:\$\{([a-zA-Z0-9_\.\-]+)\})|(?:\$([a-zA-Z0-9_\-]+(?:\.[a-zA-Z0-9_\-]+)*))""")

    fun interpolate(
        params: Map<String, String>,
        contextOutputs: Map<String, Map<String, Any?>>
    ): InterpolationResult {
        if (params.isEmpty()) {
            return InterpolationResult.Success(emptyMap())
        }

        val result = mutableMapOf<String, String>()

        for ((key, value) in params) {
            val interpolatedValueResult = interpolateString(value, contextOutputs)
            when (interpolatedValueResult) {
                is StringInterpolationResult.Success -> {
                    result[key] = interpolatedValueResult.value
                }
                is StringInterpolationResult.Missing -> {
                    return InterpolationResult.Failure(
                        missingReference = interpolatedValueResult.reference,
                        message = "Unresolved variable '${interpolatedValueResult.reference}' in parameter '$key'."
                    )
                }
            }
        }

        return InterpolationResult.Success(result)
    }

    private sealed class StringInterpolationResult {
        data class Success(val value: String) : StringInterpolationResult()
        data class Missing(val reference: String) : StringInterpolationResult()
    }

    private fun interpolateString(
        template: String,
        contextOutputs: Map<String, Map<String, Any?>>
    ): StringInterpolationResult {
        if (!template.contains("$")) {
            return StringInterpolationResult.Success(template)
        }

        var missingRef: String? = null

        val replaced = INTERPOLATION_REGEX.replace(template) { matchResult ->
            val ref = matchResult.groupValues[1].ifEmpty { matchResult.groupValues[2] }
            val resolvedVal = resolveReference(ref, contextOutputs)
            if (resolvedVal == null) {
                missingRef = ref
                matchResult.value
            } else {
                resolvedVal.toString()
            }
        }

        missingRef?.let {
            return StringInterpolationResult.Missing(it)
        }


        return StringInterpolationResult.Success(replaced)
    }

    fun resolveReference(
        ref: String,
        contextOutputs: Map<String, Map<String, Any?>>
    ): Any? {
        val parts = ref.split(".")
        if (parts.isEmpty()) return null

        val stepId = parts[0]
        val stepOutput = contextOutputs[stepId] ?: return null

        if (parts.size == 1) {
            // Direct reference to whole step output (return 'result', 'value', or first entry if available)
            return stepOutput["result"] ?: stepOutput["value"] ?: stepOutput["text"] ?: stepOutput
        }

        var current: Any? = stepOutput
        for (i in 1 until parts.size) {
            val key = parts[i]
            current = when (current) {
                is Map<*, *> -> current[key]
                is org.json.JSONObject -> if (current.has(key)) current.opt(key) else null
                else -> null
            }
            if (current == null) return null
        }

        return current
    }
}
