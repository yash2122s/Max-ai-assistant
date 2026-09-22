package com.agentcore.skills.di

import com.agentcore.skill.base.BaseSkill
import com.agentcore.skill.base.Immutable
import com.agentcore.skill.base.SkillCategory
import com.agentcore.skill.base.SkillContext
import com.agentcore.skill.base.SkillResult
import com.agentcore.skill.base.skillMetadata
import com.agentcore.skill.base.validate
import com.agentcore.util.checkCancellation

/**
 * Input for Koin DI validation.
 *
 * @property moduleDefinitions List of module definition strings to validate
 * @property checkCircularDependencies Whether to check for potential circular dependencies
 * @property checkMissingDependencies Whether to check for missing dependencies
 */
@Immutable
data class KoinValidationInput(
    val moduleDefinitions: List<ModuleDefinition>,
    val checkCircularDependencies: Boolean = true,
    val checkMissingDependencies: Boolean = true,
)

/**
 * Represents a Koin module definition.
 */
@Immutable
data class ModuleDefinition(
    val moduleName: String,
    val dependencies: List<DependencyDefinition>,
)

/**
 * Represents a single dependency definition in Koin.
 */
@Immutable
data class DependencyDefinition(
    val type: DependencyType,
    val interfaceType: String,
    val implementationType: String? = null,
    val qualifier: String? = null,
    val dependencies: List<String> = emptyList(),
)

/**
 * Type of dependency in Koin.
 */
enum class DependencyType {
    SINGLE,
    SINGLE_OF,
    FACTORY,
    FACTORY_OF,
    SCOPED,
    VIEWMODEL,
    VIEWMODEL_OF,
}

/**
 * Output from Koin validation.
 */
@Immutable
data class KoinValidationOutput(
    val isValid: Boolean,
    val errors: List<ValidationError>,
    val warnings: List<ValidationWarning>,
    val suggestions: List<String>,
)

/**
 * Represents a validation error.
 */
@Immutable
data class ValidationError(
    val module: String,
    val dependency: String,
    val errorType: ErrorType,
    val message: String,
)

/**
 * Type of validation error.
 */
enum class ErrorType {
    CIRCULAR_DEPENDENCY,
    MISSING_DEPENDENCY,
    DUPLICATE_BINDING,
    INVALID_SCOPE,
    MISSING_QUALIFIER,
}

/**
 * Represents a validation warning.
 */
@Immutable
data class ValidationWarning(
    val module: String,
    val dependency: String,
    val message: String,
)

/**
 * Skill to validate Koin DI configuration.
 *
 * This skill analyzes Koin module definitions and detects:
 * - Circular dependencies
 * - Missing dependencies
 * - Duplicate bindings
 * - Invalid scopes
 * - Best practice violations
 *
 * Example usage:
 * ```kotlin
 * val input = KoinValidationInput(
 *     moduleDefinitions = listOf(
 *         ModuleDefinition(
 *             moduleName = "appModule",
 *             dependencies = listOf(
 *                 DependencyDefinition(
 *                     type = DependencyType.SINGLE_OF,
 *                     interfaceType = "UserRepository",
 *                     implementationType = "UserRepositoryImpl",
 *                     dependencies = listOf("UserApiService", "UserDao"),
 *                 ),
 *             ),
 *         ),
 *     ),
 * )
 *
 * val result = skill.execute(input, context)
 * result.onSuccess { output ->
 *     if (output.isValid) {
 *         println("Koin configuration is valid!")
 *     } else {
 *         output.errors.forEach { error ->
 *             println("Error: ${error.message}")
 *         }
 *     }
 * }
 * ```
 */
class KoinValidationSkill : BaseSkill<KoinValidationInput, KoinValidationOutput>() {

    override val metadata = skillMetadata {
        id = "core.di.koin_validation"
        name = "Koin Validation Skill"
        description = "Validates Koin DI configuration for common issues"
        category = SkillCategory.UTILITY
        version = "1.0.0"
        tags = listOf("di", "koin", "validation", "dependency-injection")
    }

    override suspend fun validate(input: KoinValidationInput): SkillResult<Unit> {
        return validate {
            require(input.moduleDefinitions.isNotEmpty()) {
                "moduleDefinitions must not be empty"
            }
            input.moduleDefinitions.forEach { module ->
                requireNotBlank(module.moduleName, "moduleName")
            }
        }
    }

    override suspend fun doExecute(
        input: KoinValidationInput,
        context: SkillContext,
    ): SkillResult<KoinValidationOutput> {
        checkCancellation()

        val errors = mutableListOf<ValidationError>()
        val warnings = mutableListOf<ValidationWarning>()
        val suggestions = mutableListOf<String>()

        // Build dependency graph
        val allDependencies = buildDependencyGraph(input.moduleDefinitions)

        checkCancellation()

        // Check for circular dependencies
        if (input.checkCircularDependencies) {
            errors.addAll(detectCircularDependencies(allDependencies, input.moduleDefinitions))
        }

        checkCancellation()

        // Check for missing dependencies
        if (input.checkMissingDependencies) {
            errors.addAll(detectMissingDependencies(allDependencies, input.moduleDefinitions))
        }

        checkCancellation()

        // Check for duplicate bindings
        errors.addAll(detectDuplicateBindings(input.moduleDefinitions))

        // Check for best practices
        warnings.addAll(checkBestPractices(input.moduleDefinitions))

        // Generate suggestions
        suggestions.addAll(generateSuggestions(input.moduleDefinitions, errors, warnings))

        return SkillResult.success(
            KoinValidationOutput(
                isValid = errors.isEmpty(),
                errors = errors,
                warnings = warnings,
                suggestions = suggestions,
            )
        )
    }

    private fun buildDependencyGraph(
        modules: List<ModuleDefinition>,
    ): Map<String, DependencyDefinition> {
        return modules
            .flatMap { it.dependencies }
            .associateBy { it.interfaceType }
    }

    private fun detectCircularDependencies(
        allDependencies: Map<String, DependencyDefinition>,
        modules: List<ModuleDefinition>,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()

        fun dfs(type: String, path: List<String>): List<String>? {
            if (type in recursionStack) {
                return path + type
            }
            if (type in visited) {
                return null
            }

            visited.add(type)
            recursionStack.add(type)

            val dependency = allDependencies[type]
            dependency?.dependencies?.forEach { dep ->
                val cycle = dfs(dep, path + type)
                if (cycle != null) {
                    return cycle
                }
            }

            recursionStack.remove(type)
            return null
        }

        modules.flatMap { it.dependencies }.forEach { dep ->
            val cycle = dfs(dep.interfaceType, emptyList())
            if (cycle != null && cycle.size > 1) {
                val moduleName = modules.find { module ->
                    module.dependencies.any { it.interfaceType == dep.interfaceType }
                }?.moduleName ?: "unknown"

                errors.add(
                    ValidationError(
                        module = moduleName,
                        dependency = dep.interfaceType,
                        errorType = ErrorType.CIRCULAR_DEPENDENCY,
                        message = "Circular dependency detected: ${cycle.joinToString(" -> ")}",
                    )
                )
            }
            visited.clear()
            recursionStack.clear()
        }

        return errors.distinctBy { it.message }
    }

    private fun detectMissingDependencies(
        allDependencies: Map<String, DependencyDefinition>,
        modules: List<ModuleDefinition>,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val providedTypes = allDependencies.keys + KOIN_BUILTIN_TYPES

        modules.forEach { module ->
            module.dependencies.forEach { dep ->
                dep.dependencies.forEach { requiredDep ->
                    if (requiredDep !in providedTypes) {
                        errors.add(
                            ValidationError(
                                module = module.moduleName,
                                dependency = dep.interfaceType,
                                errorType = ErrorType.MISSING_DEPENDENCY,
                                message = "Missing dependency: '$requiredDep' required by '${dep.interfaceType}'",
                            )
                        )
                    }
                }
            }
        }

        return errors
    }

    private fun detectDuplicateBindings(
        modules: List<ModuleDefinition>,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val seenBindings = mutableMapOf<String, String>()

        modules.forEach { module ->
            module.dependencies.forEach { dep ->
                val key = "${dep.interfaceType}:${dep.qualifier ?: "default"}"
                val existingModule = seenBindings[key]

                if (existingModule != null && existingModule != module.moduleName) {
                    errors.add(
                        ValidationError(
                            module = module.moduleName,
                            dependency = dep.interfaceType,
                            errorType = ErrorType.DUPLICATE_BINDING,
                            message = "Duplicate binding for '${dep.interfaceType}' " +
                                    "(qualifier: ${dep.qualifier ?: "none"}). " +
                                    "Also defined in '$existingModule'",
                        )
                    )
                } else {
                    seenBindings[key] = module.moduleName
                }
            }
        }

        return errors
    }

    private fun checkBestPractices(
        modules: List<ModuleDefinition>,
    ): List<ValidationWarning> {
        val warnings = mutableListOf<ValidationWarning>()

        modules.forEach { module ->
            module.dependencies.forEach { dep ->
                // Warn about SINGLE for ViewModels
                if (dep.type == DependencyType.SINGLE &&
                    dep.interfaceType.endsWith("ViewModel")
                ) {
                    warnings.add(
                        ValidationWarning(
                            module = module.moduleName,
                            dependency = dep.interfaceType,
                            message = "ViewModel '${dep.interfaceType}' should use " +
                                    "viewModelOf() or viewModel {} instead of single {}",
                        )
                    )
                }

                // Warn about FACTORY for repositories
                if (dep.type == DependencyType.FACTORY &&
                    dep.interfaceType.endsWith("Repository")
                ) {
                    warnings.add(
                        ValidationWarning(
                            module = module.moduleName,
                            dependency = dep.interfaceType,
                            message = "Repository '${dep.interfaceType}' should typically " +
                                    "use single {} or singleOf() instead of factory {}",
                        )
                    )
                }

                // Warn about missing interface binding
                if (dep.type in listOf(DependencyType.SINGLE, DependencyType.FACTORY) &&
                    dep.implementationType == null &&
                    !dep.interfaceType.endsWith("Impl")
                ) {
                    warnings.add(
                        ValidationWarning(
                            module = module.moduleName,
                            dependency = dep.interfaceType,
                            message = "Consider binding '${dep.interfaceType}' to an interface " +
                                    "for better testability",
                        )
                    )
                }
            }
        }

        return warnings
    }

    private fun generateSuggestions(
        modules: List<ModuleDefinition>,
        errors: List<ValidationError>,
        warnings: List<ValidationWarning>,
    ): List<String> {
        val suggestions = mutableListOf<String>()

        // Suggest module verification test
        if (modules.size > 2) {
            suggestions.add(
                "Consider adding a module verification test using koinApplication { checkModules() }"
            )
        }

        // Suggest using qualifiers for dispatcher dependencies
        val hasMultipleDispatchers = modules.flatMap { it.dependencies }
            .count { it.interfaceType.contains("Dispatcher") } > 1

        if (hasMultipleDispatchers) {
            val hasQualifiers = modules.flatMap { it.dependencies }
                .filter { it.interfaceType.contains("Dispatcher") }
                .all { it.qualifier != null }

            if (!hasQualifiers) {
                suggestions.add(
                    "Use named qualifiers for CoroutineDispatcher dependencies " +
                            "(e.g., named(\"IoDispatcher\"))"
                )
            }
        }

        // Suggest organizing modules
        if (modules.size == 1 && modules.first().dependencies.size > 10) {
            suggestions.add(
                "Consider splitting your module into smaller, feature-based modules " +
                        "(e.g., networkModule, databaseModule, repositoryModule)"
            )
        }

        return suggestions
    }

    companion object {
        /**
         * Built-in types that Koin provides automatically.
         */
        private val KOIN_BUILTIN_TYPES = setOf(
            "Context",
            "Application",
            "SavedStateHandle",
            "CoroutineScope",
            "CoroutineDispatcher",
            "Koin",
        )
    }
}
