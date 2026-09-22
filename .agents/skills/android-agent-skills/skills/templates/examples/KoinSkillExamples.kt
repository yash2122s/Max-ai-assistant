package com.agentcore.examples

import com.agentcore.skills.di.CodeSnippet
import com.agentcore.skills.di.ConstructorParam
import com.agentcore.skills.di.DependencyDefinition
import com.agentcore.skills.di.DependencyType
import com.agentcore.skills.di.KoinBestPracticesInput
import com.agentcore.skills.di.KoinBestPracticesSkill
import com.agentcore.skills.di.KoinDependencySpec
import com.agentcore.skills.di.KoinModuleGeneratorInput
import com.agentcore.skills.di.KoinModuleGeneratorSkill
import com.agentcore.skills.di.KoinScope
import com.agentcore.skills.di.KoinValidationInput
import com.agentcore.skills.di.KoinValidationSkill
import com.agentcore.skills.di.ModuleDefinition
import com.agentcore.skills.di.ProjectType
import com.agentcore.skills.di.SnippetType

/*
 * ============================================================================
 * 📚 TEMPLATE FILE - Reference for AI Code Generation
 * ============================================================================
 * 
 * This file demonstrates Koin DI skills usage with examples.
 * 
 * FILE NAMING CONVENTIONS:
 *   Module File:    [Feature]Module.kt       (e.g., NetworkModule.kt)
 *   Qualifiers:     Qualifiers.kt            (centralized qualifiers)
 *   DI Setup:       AppKoinModule.kt         (main module)
 * 
 * @see 08-dependency-injection.md for DI patterns
 * @see HiltKoinSetup.kt for Hilt alternatives
 * ============================================================================
 */

/**
 * Examples demonstrating Koin DI skills usage.
 *
 * This file shows how to use the three Koin-related skills:
 * 1. KoinValidationSkill - Validate Koin configuration
 * 2. KoinModuleGeneratorSkill - Generate Koin module code
 * 3. KoinBestPracticesSkill - Analyze code for best practices
 */
object KoinSkillExamples {

    // ============================================================
    // 1. KOIN VALIDATION SKILL
    // ============================================================

    /**
     * Example: Validating Koin module configuration.
     *
     * This skill checks for:
     * - Circular dependencies
     * - Missing dependencies
     * - Duplicate bindings
     * - Best practice violations
     */
    suspend fun validateKoinConfiguration(
        skill: KoinValidationSkill,
        context: com.agentcore.skill.base.SkillContext,
    ) {
        // Define your module structure
        val input = KoinValidationInput(
            moduleDefinitions = listOf(
                ModuleDefinition(
                    moduleName = "networkModule",
                    dependencies = listOf(
                        DependencyDefinition(
                            type = DependencyType.SINGLE,
                            interfaceType = "OkHttpClient",
                        ),
                        DependencyDefinition(
                            type = DependencyType.SINGLE,
                            interfaceType = "Retrofit",
                            dependencies = listOf("OkHttpClient"),
                        ),
                        DependencyDefinition(
                            type = DependencyType.SINGLE,
                            interfaceType = "UserApiService",
                            dependencies = listOf("Retrofit"),
                        ),
                    ),
                ),
                ModuleDefinition(
                    moduleName = "repositoryModule",
                    dependencies = listOf(
                        DependencyDefinition(
                            type = DependencyType.SINGLE_OF,
                            interfaceType = "UserRepository",
                            implementationType = "UserRepositoryImpl",
                            dependencies = listOf("UserApiService", "UserDao"),
                        ),
                    ),
                ),
                ModuleDefinition(
                    moduleName = "viewModelModule",
                    dependencies = listOf(
                        DependencyDefinition(
                            type = DependencyType.VIEWMODEL_OF,
                            interfaceType = "UserViewModel",
                            implementationType = "UserViewModel",
                            dependencies = listOf("GetUserUseCase"),
                        ),
                    ),
                ),
            ),
            checkCircularDependencies = true,
            checkMissingDependencies = true,
        )

        val result = skill.execute(input, context)

        result.onSuccess { output ->
            println("Validation result: ${if (output.isValid) "VALID" else "INVALID"}")

            if (output.errors.isNotEmpty()) {
                println("\nErrors:")
                output.errors.forEach { error ->
                    println("  - [${error.errorType}] ${error.message}")
                }
            }

            if (output.warnings.isNotEmpty()) {
                println("\nWarnings:")
                output.warnings.forEach { warning ->
                    println("  - ${warning.message}")
                }
            }

            if (output.suggestions.isNotEmpty()) {
                println("\nSuggestions:")
                output.suggestions.forEach { suggestion ->
                    println("  - $suggestion")
                }
            }
        }

        result.onFailure { error ->
            println("Validation failed: ${error.message}")
        }
    }

    // ============================================================
    // 2. KOIN MODULE GENERATOR SKILL
    // ============================================================

    /**
     * Example: Generating Koin module code.
     *
     * This skill generates clean, well-formatted Koin module code
     * from specifications.
     */
    suspend fun generateKoinModule(
        skill: KoinModuleGeneratorSkill,
        context: com.agentcore.skill.base.SkillContext,
    ) {
        val input = KoinModuleGeneratorInput(
            moduleName = "repositoryModule",
            packageName = "com.example.di",
            dependencies = listOf(
                KoinDependencySpec(
                    interfaceType = "UserRepository",
                    implementationType = "UserRepositoryImpl",
                    scope = KoinScope.SINGLE_OF,
                    constructorParams = listOf(
                        ConstructorParam(name = "api", type = "UserApiService"),
                        ConstructorParam(name = "dao", type = "UserDao"),
                        ConstructorParam(
                            name = "dispatcher",
                            type = "CoroutineDispatcher",
                            qualifier = "IoDispatcher",
                        ),
                    ),
                ),
                KoinDependencySpec(
                    interfaceType = "PostRepository",
                    implementationType = "PostRepositoryImpl",
                    scope = KoinScope.SINGLE_OF,
                    constructorParams = listOf(
                        ConstructorParam(name = "api", type = "PostApiService"),
                        ConstructorParam(name = "cache", type = "PostCache"),
                    ),
                ),
                KoinDependencySpec(
                    interfaceType = "GetUserUseCase",
                    implementationType = "GetUserUseCase",
                    scope = KoinScope.FACTORY_OF,
                ),
            ),
            includeImports = true,
            generateTestModule = true,
        )

        val result = skill.execute(input, context)

        result.onSuccess { output ->
            println("Generated Module Code:")
            println("=" .repeat(50))
            println(output.moduleCode)

            if (output.testModuleCode != null) {
                println("\nGenerated Test Module Code:")
                println("=" .repeat(50))
                println(output.testModuleCode)
            }

            println("\nRequired Imports:")
            output.imports.forEach { import ->
                println("  import $import")
            }
        }

        result.onFailure { error ->
            println("Generation failed: ${error.message}")
        }
    }

    // ============================================================
    // 3. KOIN BEST PRACTICES SKILL
    // ============================================================

    /**
     * Example: Analyzing code for Koin best practices.
     *
     * This skill analyzes Koin code and provides:
     * - Best practice violations
     * - Recommendations for improvement
     * - Code suggestions
     */
    suspend fun analyzeKoinBestPractices(
        skill: KoinBestPracticesSkill,
        context: com.agentcore.skill.base.SkillContext,
    ) {
        val input = KoinBestPracticesInput(
            codeSnippets = listOf(
                CodeSnippet(
                    fileName = "AppModule.kt",
                    code = """
                        val appModule = module {
                            single { UserRepositoryImpl(get(), get()) }
                            single { MainViewModel(get()) } // Wrong: should use viewModel
                            factory { UserRepository(get()) } // Warning: repository as factory
                        }
                    """.trimIndent(),
                    type = SnippetType.KOIN_MODULE,
                ),
                CodeSnippet(
                    fileName = "UserViewModel.kt",
                    code = """
                        class UserViewModel : ViewModel(), KoinComponent {
                            private val repository: UserRepository by inject()
                        }
                    """.trimIndent(),
                    type = SnippetType.VIEWMODEL,
                ),
                CodeSnippet(
                    fileName = "UserScreen.kt",
                    code = """
                        @Composable
                        fun UserScreen() {
                            val viewModel = getKoin().get<UserViewModel>()
                        }
                    """.trimIndent(),
                    type = SnippetType.COMPOSABLE,
                ),
            ),
            projectType = ProjectType.ANDROID,
            includeKmpRecommendations = false,
        )

        val result = skill.execute(input, context)

        result.onSuccess { output ->
            println("Best Practices Score: ${output.overallScore}/100")
            println()

            if (output.issues.isNotEmpty()) {
                println("Issues Found:")
                output.issues.forEach { issue ->
                    val icon = when (issue.severity) {
                        com.agentcore.skills.di.IssueSeverity.ERROR -> "❌"
                        com.agentcore.skills.di.IssueSeverity.WARNING -> "⚠️"
                        com.agentcore.skills.di.IssueSeverity.INFO -> "ℹ️"
                    }
                    println("  $icon [${issue.rule}] ${issue.description}")
                    println("     File: ${issue.fileName}")
                }
            }

            if (output.recommendations.isNotEmpty()) {
                println("\nRecommendations:")
                output.recommendations.forEach { rec ->
                    println("  📌 ${rec.title}")
                    println("     ${rec.description}")
                    if (rec.example != null) {
                        println("     Example:")
                        rec.example.lines().forEach { line ->
                            println("       $line")
                        }
                    }
                }
            }

            if (output.codeImprovements.isNotEmpty()) {
                println("\nCode Improvements:")
                output.codeImprovements.forEach { improvement ->
                    println("  File: ${improvement.fileName}")
                    println("  Before: ${improvement.originalCode}")
                    println("  After:  ${improvement.improvedCode}")
                    println("  Why:    ${improvement.explanation}")
                    println()
                }
            }
        }

        result.onFailure { error ->
            println("Analysis failed: ${error.message}")
        }
    }

    // ============================================================
    // 4. COMBINING SKILLS IN A PIPELINE
    // ============================================================

    /**
     * Example: Using multiple Koin skills together.
     *
     * This shows how to combine validation and best practices
     * analysis for comprehensive DI code review.
     */
    suspend fun comprehensiveKoinReview(
        validationSkill: KoinValidationSkill,
        bestPracticesSkill: KoinBestPracticesSkill,
        context: com.agentcore.skill.base.SkillContext,
    ) {
        println("🔍 Starting Comprehensive Koin Review")
        println("=" .repeat(50))

        // Step 1: Validate structure
        val moduleDefinitions = listOf(
            ModuleDefinition(
                moduleName = "appModule",
                dependencies = listOf(
                    DependencyDefinition(
                        type = DependencyType.SINGLE_OF,
                        interfaceType = "UserRepository",
                        implementationType = "UserRepositoryImpl",
                        dependencies = listOf("UserApiService"),
                    ),
                    DependencyDefinition(
                        type = DependencyType.VIEWMODEL_OF,
                        interfaceType = "UserViewModel",
                        dependencies = listOf("UserRepository"),
                    ),
                ),
            ),
        )

        val validationResult = validationSkill.execute(
            KoinValidationInput(moduleDefinitions),
            context,
        )

        validationResult.onSuccess { validation ->
            println("\n📋 Structure Validation: ${if (validation.isValid) "✅ PASSED" else "❌ FAILED"}")
            validation.errors.forEach { println("   Error: ${it.message}") }
            validation.warnings.forEach { println("   Warning: ${it.message}") }
        }

        // Step 2: Check best practices
        val codeSnippets = listOf(
            CodeSnippet(
                fileName = "AppModule.kt",
                code = """
                    val appModule = module {
                        singleOf(::UserRepositoryImpl) { bind<UserRepository>() }
                        viewModelOf(::UserViewModel)
                    }
                """.trimIndent(),
                type = SnippetType.KOIN_MODULE,
            ),
        )

        val bestPracticesResult = bestPracticesSkill.execute(
            KoinBestPracticesInput(codeSnippets),
            context,
        )

        bestPracticesResult.onSuccess { practices ->
            println("\n📊 Best Practices Score: ${practices.overallScore}/100")
            practices.issues.forEach { println("   Issue: ${it.description}") }
            practices.recommendations.forEach { println("   Tip: ${it.title}") }
        }

        println("\n" + "=" .repeat(50))
        println("✅ Review Complete")
    }
}
