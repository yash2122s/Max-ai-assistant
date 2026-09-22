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
 * Input for Koin module generation.
 */
@Immutable
data class KoinModuleGeneratorInput(
    val moduleName: String,
    val packageName: String,
    val dependencies: List<KoinDependencySpec>,
    val includeImports: Boolean = true,
    val generateTestModule: Boolean = false,
)

/**
 * Specification for a Koin dependency to generate.
 */
@Immutable
data class KoinDependencySpec(
    val interfaceType: String,
    val implementationType: String,
    val scope: KoinScope = KoinScope.SINGLE,
    val qualifier: String? = null,
    val constructorParams: List<ConstructorParam> = emptyList(),
    val isAndroidContext: Boolean = false,
)

/**
 * Constructor parameter specification.
 */
@Immutable
data class ConstructorParam(
    val name: String,
    val type: String,
    val qualifier: String? = null,
    val isLazy: Boolean = false,
)

/**
 * Koin scope types.
 */
enum class KoinScope {
    SINGLE,
    SINGLE_OF,
    FACTORY,
    FACTORY_OF,
    SCOPED,
    VIEWMODEL,
    VIEWMODEL_OF,
}

/**
 * Output containing generated Koin module code.
 */
@Immutable
data class KoinModuleGeneratorOutput(
    val moduleCode: String,
    val testModuleCode: String?,
    val imports: List<String>,
)

/**
 * Skill to generate Koin module code.
 *
 * This skill generates clean, well-formatted Koin module definitions
 * following best practices.
 *
 * Example usage:
 * ```kotlin
 * val input = KoinModuleGeneratorInput(
 *     moduleName = "repositoryModule",
 *     packageName = "com.example.di",
 *     dependencies = listOf(
 *         KoinDependencySpec(
 *             interfaceType = "UserRepository",
 *             implementationType = "UserRepositoryImpl",
 *             scope = KoinScope.SINGLE_OF,
 *             constructorParams = listOf(
 *                 ConstructorParam("api", "UserApiService"),
 *                 ConstructorParam("dao", "UserDao"),
 *             ),
 *         ),
 *     ),
 * )
 *
 * val result = skill.execute(input, context)
 * result.onSuccess { output ->
 *     println(output.moduleCode)
 * }
 * ```
 */
class KoinModuleGeneratorSkill : BaseSkill<KoinModuleGeneratorInput, KoinModuleGeneratorOutput>() {

    override val metadata = skillMetadata {
        id = "core.di.koin_generator"
        name = "Koin Module Generator Skill"
        description = "Generates Koin module code from specifications"
        category = SkillCategory.UTILITY
        version = "1.0.0"
        tags = listOf("di", "koin", "code-generation", "dependency-injection")
    }

    override suspend fun validate(input: KoinModuleGeneratorInput): SkillResult<Unit> {
        return validate {
            requireNotBlank(input.moduleName, "moduleName")
            requireNotBlank(input.packageName, "packageName")
            require(input.dependencies.isNotEmpty()) {
                "dependencies must not be empty"
            }
            input.dependencies.forEach { dep ->
                requireNotBlank(dep.interfaceType, "interfaceType")
                requireNotBlank(dep.implementationType, "implementationType")
            }
        }
    }

    override suspend fun doExecute(
        input: KoinModuleGeneratorInput,
        context: SkillContext,
    ): SkillResult<KoinModuleGeneratorOutput> {
        checkCancellation()

        val imports = generateImports(input)
        val moduleCode = generateModuleCode(input)
        val testModuleCode = if (input.generateTestModule) {
            generateTestModuleCode(input)
        } else {
            null
        }

        return SkillResult.success(
            KoinModuleGeneratorOutput(
                moduleCode = moduleCode,
                testModuleCode = testModuleCode,
                imports = imports,
            )
        )
    }

    private fun generateImports(input: KoinModuleGeneratorInput): List<String> {
        if (!input.includeImports) return emptyList()

        val imports = mutableSetOf(
            "org.koin.dsl.module",
        )

        input.dependencies.forEach { dep ->
            when (dep.scope) {
                KoinScope.SINGLE_OF -> imports.add("org.koin.core.module.dsl.singleOf")
                KoinScope.FACTORY_OF -> imports.add("org.koin.core.module.dsl.factoryOf")
                KoinScope.VIEWMODEL -> imports.add("org.koin.androidx.viewmodel.dsl.viewModel")
                KoinScope.VIEWMODEL_OF -> imports.add("org.koin.androidx.viewmodel.dsl.viewModelOf")
                KoinScope.SCOPED -> imports.add("org.koin.core.module.dsl.scoped")
                else -> {}
            }

            if (dep.interfaceType != dep.implementationType) {
                imports.add("org.koin.core.module.dsl.bind")
            }

            if (dep.qualifier != null) {
                imports.add("org.koin.core.qualifier.named")
            }

            if (dep.constructorParams.any { it.isLazy }) {
                imports.add("org.koin.core.component.inject")
            }

            if (dep.isAndroidContext) {
                imports.add("org.koin.android.ext.koin.androidContext")
            }
        }

        return imports.sorted()
    }

    private fun generateModuleCode(input: KoinModuleGeneratorInput): String {
        val sb = StringBuilder()

        // Package declaration
        sb.appendLine("package ${input.packageName}")
        sb.appendLine()

        // Imports
        if (input.includeImports) {
            generateImports(input).forEach { import ->
                sb.appendLine("import $import")
            }
            sb.appendLine()
        }

        // Module documentation
        sb.appendLine("/**")
        sb.appendLine(" * Koin module for ${input.moduleName.removeSuffix("Module")} dependencies.")
        sb.appendLine(" */")

        // Module definition
        sb.appendLine("val ${input.moduleName} = module {")
        sb.appendLine()

        input.dependencies.forEachIndexed { index, dep ->
            sb.append(generateDependencyCode(dep, indent = "    "))
            if (index < input.dependencies.size - 1) {
                sb.appendLine()
            }
        }

        sb.appendLine("}")

        return sb.toString()
    }

    private fun generateDependencyCode(
        dep: KoinDependencySpec,
        indent: String,
    ): String {
        val sb = StringBuilder()

        // Add comment
        sb.appendLine("$indent// ${dep.interfaceType}")

        when (dep.scope) {
            KoinScope.SINGLE_OF, KoinScope.FACTORY_OF, KoinScope.VIEWMODEL_OF -> {
                sb.append(indent)
                sb.append(
                    when (dep.scope) {
                        KoinScope.SINGLE_OF -> "singleOf"
                        KoinScope.FACTORY_OF -> "factoryOf"
                        KoinScope.VIEWMODEL_OF -> "viewModelOf"
                        else -> "singleOf"
                    }
                )
                sb.append("(::${dep.implementationType})")

                if (dep.interfaceType != dep.implementationType || dep.qualifier != null) {
                    sb.append(" {")
                    if (dep.interfaceType != dep.implementationType) {
                        sb.append(" bind<${dep.interfaceType}>()")
                    }
                    if (dep.qualifier != null) {
                        sb.append(" named(\"${dep.qualifier}\")")
                    }
                    sb.append(" }")
                }
                sb.appendLine()
            }

            KoinScope.SINGLE, KoinScope.FACTORY, KoinScope.SCOPED -> {
                val scopeKeyword = when (dep.scope) {
                    KoinScope.SINGLE -> "single"
                    KoinScope.FACTORY -> "factory"
                    KoinScope.SCOPED -> "scoped"
                    else -> "single"
                }

                sb.append(indent)
                sb.append(scopeKeyword)

                if (dep.qualifier != null) {
                    sb.append("(named(\"${dep.qualifier}\"))")
                }

                sb.appendLine("<${dep.interfaceType}> {")
                sb.append("$indent    ${dep.implementationType}(")

                if (dep.constructorParams.isNotEmpty()) {
                    sb.appendLine()
                    dep.constructorParams.forEachIndexed { index, param ->
                        sb.append("$indent        ")
                        sb.append("${param.name} = ")

                        if (param.isLazy) {
                            sb.append("lazy { get")
                        } else {
                            sb.append("get")
                        }

                        if (param.qualifier != null) {
                            sb.append("(named(\"${param.qualifier}\"))")
                        } else {
                            sb.append("()")
                        }

                        if (param.isLazy) {
                            sb.append(" }")
                        }

                        if (index < dep.constructorParams.size - 1) {
                            sb.appendLine(",")
                        } else {
                            sb.appendLine(",")
                        }
                    }
                    sb.append("$indent    ")
                }
                sb.appendLine(")")
                sb.appendLine("$indent}")
            }

            KoinScope.VIEWMODEL -> {
                sb.append(indent)
                sb.append("viewModel")

                if (dep.qualifier != null) {
                    sb.append("(named(\"${dep.qualifier}\"))")
                }

                sb.appendLine(" {")
                sb.append("$indent    ${dep.implementationType}(")

                if (dep.constructorParams.isNotEmpty()) {
                    sb.appendLine()
                    dep.constructorParams.forEachIndexed { index, param ->
                        sb.append("$indent        ")
                        sb.append("${param.name} = ")
                        sb.append("get")

                        if (param.qualifier != null) {
                            sb.append("(named(\"${param.qualifier}\"))")
                        } else {
                            sb.append("()")
                        }

                        if (index < dep.constructorParams.size - 1) {
                            sb.appendLine(",")
                        } else {
                            sb.appendLine(",")
                        }
                    }
                    sb.append("$indent    ")
                }
                sb.appendLine(")")
                sb.appendLine("$indent}")
            }
        }

        return sb.toString()
    }

    private fun generateTestModuleCode(input: KoinModuleGeneratorInput): String {
        val sb = StringBuilder()

        sb.appendLine("package ${input.packageName}")
        sb.appendLine()
        sb.appendLine("import io.mockk.mockk")
        sb.appendLine("import org.koin.dsl.module")
        sb.appendLine()
        sb.appendLine("/**")
        sb.appendLine(" * Test module for ${input.moduleName}.")
        sb.appendLine(" * Provides mock implementations for testing.")
        sb.appendLine(" */")
        sb.appendLine("val test${input.moduleName.replaceFirstChar { it.uppercase() }} = module {")
        sb.appendLine()

        input.dependencies.forEach { dep ->
            sb.appendLine("    // Mock ${dep.interfaceType}")
            sb.appendLine("    single<${dep.interfaceType}> { mockk(relaxed = true) }")
            sb.appendLine()
        }

        sb.appendLine("}")

        return sb.toString()
    }
}
