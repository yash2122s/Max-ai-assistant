package com.example.automation.skills.validation

import com.example.automation.skills.catalog.CanonicalCapabilityCatalog
import com.example.automation.skills.canonical.CanonicalJsonSerializer
import com.example.automation.skills.model.SkillDefinition
import com.example.automation.skills.model.SkillStep
import java.util.*

data class SkillValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
) {
    fun errorSummary(): String = errors.joinToString("; ")
}

/**
 * Multi-Tier Validator for MAX Runtime Automation Skills.
 * Performs structural, capability, DAG dependency (Kahn's algorithm), and checksum validation.
 */
object SkillValidator {

    private val SKILL_ID_REGEX = Regex("^[a-z0-9]+([._-][a-z0-9]+)*$")
    private val STEP_ID_REGEX = Regex("^[a-zA-Z0-9_-]+$")

    /**
     * Validates a parsed SkillDefinition.
     * @param skill The SkillDefinition to audit.
     * @param targetPlatform The host platform being targeted ("android" or "windows").
     * @param verifyChecksum Whether to verify SHA-256 integrity if checksum is declared.
     */
    fun validate(
        skill: SkillDefinition,
        targetPlatform: String = "android",
        verifyChecksum: Boolean = true
    ): SkillValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val skillId = skill.skillId ?: ""
        val name = skill.name ?: ""
        val description = skill.description ?: ""
        val schemaVersion = skill.schemaVersion ?: ""
        val supportedPlatforms = skill.supportedPlatforms ?: emptyList()
        val allowedCapabilities = (skill.allowedCapabilities ?: emptyList()).map { it.trim().lowercase() }.toSet()
        val steps = skill.steps ?: emptyList()
        val checksum = skill.checksum ?: ""

        // 1. Structural Checks
        if (skillId.isBlank()) {
            errors.add("skill_id cannot be blank.")
        } else if (!SKILL_ID_REGEX.matches(skillId)) {
            errors.add("skill_id '$skillId' is invalid. Must contain only lowercase letters, digits, '.', '_', or '-'.")
        }

        if (name.isBlank()) {
            errors.add("name cannot be blank.")
        } else if (name.length > 128) {
            errors.add("name exceeds maximum length of 128 characters.")
        }

        if (description.isBlank()) {
            errors.add("description cannot be blank.")
        } else if (description.length > 1024) {
            errors.add("description exceeds maximum length of 1024 characters.")
        }

        if (schemaVersion != "1.0.0") {
            errors.add("Unsupported schema_version '$schemaVersion'. Expected '1.0.0'.")
        }

        if (supportedPlatforms.isEmpty()) {
            errors.add("supported_platforms must declare at least one platform ('android' or 'windows').")
        } else {
            val invalidPlatforms = supportedPlatforms.filter { it != "android" && it != "windows" }
            if (invalidPlatforms.isNotEmpty()) {
                errors.add("Invalid platforms declared: $invalidPlatforms. Supported values: 'android', 'windows'.")
            }
        }

        // 2. Platform Compatibility Check
        val normalizedTarget = targetPlatform.trim().lowercase()
        if (!supportedPlatforms.contains(normalizedTarget)) {
            errors.add("Skill does not support target platform '$normalizedTarget' (supported: $supportedPlatforms).")
        }

        // 3. Capability Allowlist Checks
        for (cap in allowedCapabilities) {
            if (!CanonicalCapabilityCatalog.isSupported(cap)) {
                errors.add("Unrecognized capability verb in allowed_capabilities: '$cap'.")
            }
        }

        if (steps.isEmpty()) {
            errors.add("Skill must define at least one execution step.")
        } else {
            for (step in steps) {
                val stepAction = (step.action ?: "").trim().lowercase()
                val stepId = step.stepId ?: ""
                val target = step.target ?: ""
                val args = step.arguments ?: emptyMap()

                if (!CanonicalCapabilityCatalog.isSupported(stepAction)) {
                    errors.add("Step '$stepId' references unrecognized action verb: '$stepAction'.")
                } else if (!allowedCapabilities.contains(stepAction)) {
                    errors.add("Step '$stepId' action '$stepAction' is not in declared allowed_capabilities.")
                }

                if (stepId.isBlank() || !STEP_ID_REGEX.matches(stepId)) {
                    errors.add("Invalid step_id '$stepId'. Must contain alphanumeric characters, '-', or '_'.")
                }

                if (target.isBlank() && !args.containsKey("target") && !args.containsKey("app_name")) {
                    warnings.add("Step '$stepId' has an empty target descriptor.")
                }
            }
        }

        // 4. DAG Dependency Validation (Kahn's Algorithm)
        val dagErrors = validateDagDependencies(steps)
        errors.addAll(dagErrors)

        // 5. Checksum Verification
        if (verifyChecksum && checksum.isNotBlank()) {
            if (!CanonicalJsonSerializer.verifyChecksum(skill)) {
                errors.add("Checksum mismatch: declared '$checksum' does not match computed SHA-256.")
            }
        }

        return SkillValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }

    /**
     * Validates step dependencies using Kahn's topological sort algorithm:
     * - Detects duplicate step IDs
     * - Detects self-dependencies
     * - Detects unknown/unresolved dependencies
     * - Detects circular dependency graphs (cycles)
     */
    fun validateDagDependencies(steps: List<SkillStep>): List<String> {
        val errors = mutableListOf<String>()
        if (steps.isEmpty()) return errors

        val stepIds = mutableSetOf<String>()
        val duplicates = mutableSetOf<String>()

        for (step in steps) {
            val sId = step.stepId ?: ""
            if (!stepIds.add(sId)) {
                duplicates.add(sId)
            }
        }

        if (duplicates.isNotEmpty()) {
            errors.add("Duplicate step_id(s) found in skill: $duplicates")
            return errors // Cannot perform topological sort with duplicate IDs
        }

        val inDegree = mutableMapOf<String, Int>()
        val adjacency = mutableMapOf<String, MutableList<String>>()

        for (step in steps) {
            val sId = step.stepId ?: ""
            inDegree[sId] = 0
            adjacency[sId] = mutableListOf()
        }

        for (step in steps) {
            val sId = step.stepId ?: ""
            val deps = step.dependsOn ?: emptyList()
            for (dep in deps) {
                if (dep == sId) {
                    errors.add("Step '$sId' declares a self-dependency.")
                } else if (!stepIds.contains(dep)) {
                    errors.add("Step '$sId' declares unknown dependency '$dep'.")
                } else {
                    adjacency[dep]?.add(sId)
                    inDegree[sId] = (inDegree[sId] ?: 0) + 1
                }
            }
        }

        if (errors.isNotEmpty()) {
            return errors
        }

        // Kahn's Algorithm
        val queue: Queue<String> = LinkedList()
        for ((sId, degree) in inDegree) {
            if (degree == 0) {
                queue.add(sId)
            }
        }

        var visitedCount = 0
        while (queue.isNotEmpty()) {
            val current: String = queue.poll() ?: break
            visitedCount++

            adjacency[current]?.forEach { neighbor ->
                val newDegree = (inDegree[neighbor] ?: 1) - 1
                inDegree[neighbor] = newDegree
                if (newDegree == 0) {
                    queue.add(neighbor)
                }
            }
        }

        if (visitedCount < steps.size) {
            val unvisited = inDegree.filter { it.value > 0 }.keys
            errors.add("Circular dependency detected in skill steps involving: $unvisited")
        }

        return errors
    }
}
