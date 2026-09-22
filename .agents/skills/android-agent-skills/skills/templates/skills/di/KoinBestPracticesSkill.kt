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
 * Input for Koin best practices analysis.
 */
@Immutable
data class KoinBestPracticesInput(
    val codeSnippets: List<CodeSnippet>,
    val projectType: ProjectType = ProjectType.ANDROID,
    val includeKmpRecommendations: Boolean = false,
)

/**
 * Represents a code snippet to analyze.
 */
@Immutable
data class CodeSnippet(
    val fileName: String,
    val code: String,
    val type: SnippetType,
)

/**
 * Type of code snippet.
 */
enum class SnippetType {
    KOIN_MODULE,
    VIEWMODEL,
    REPOSITORY,
    USECASE,
    APPLICATION,
    COMPOSABLE,
    TEST,
}

/**
 * Project type for context-specific recommendations.
 */
enum class ProjectType {
    ANDROID,
    KMP,
    PURE_KOTLIN,
}

/**
 * Output containing best practices analysis.
 */
@Immutable
data class KoinBestPracticesOutput(
    val overallScore: Int, // 0-100
    val issues: List<BestPracticeIssue>,
    val recommendations: List<Recommendation>,
    val codeImprovements: List<CodeImprovement>,
)

/**
 * Represents a best practice issue found.
 */
@Immutable
data class BestPracticeIssue(
    val fileName: String,
    val severity: IssueSeverity,
    val rule: String,
    val description: String,
    val lineHint: String? = null,
)

/**
 * Severity of an issue.
 */
enum class IssueSeverity {
    ERROR,
    WARNING,
    INFO,
}

/**
 * A recommendation for improving the code.
 */
@Immutable
data class Recommendation(
    val category: RecommendationCategory,
    val title: String,
    val description: String,
    val example: String? = null,
)

/**
 * Category of recommendation.
 */
enum class RecommendationCategory {
    MODULE_ORGANIZATION,
    SCOPE_USAGE,
    TESTING,
    PERFORMANCE,
    MAINTAINABILITY,
    KMP_READINESS,
}

/**
 * Suggested code improvement.
 */
@Immutable
data class CodeImprovement(
    val fileName: String,
    val originalCode: String,
    val improvedCode: String,
    val explanation: String,
)

/**
 * Skill to analyze Koin code for best practices.
 *
 * This skill analyzes Koin DI code and provides:
 * - Best practice violations detection
 * - Recommendations for improvement
 * - Code suggestions
 * - KMP readiness assessment
 *
 * Example usage:
 * ```kotlin
 * val input = KoinBestPracticesInput(
 *     codeSnippets = listOf(
 *         CodeSnippet(
 *             fileName = "AppModule.kt",
 *             code = """
 *                 val appModule = module {
 *                     single { UserRepository() }
 *                     viewModel { MainViewModel(get()) }
 *                 }
 *             """.trimIndent(),
 *             type = SnippetType.KOIN_MODULE,
 *         ),
 *     ),
 * )
 *
 * val result = skill.execute(input, context)
 * result.onSuccess { output ->
 *     println("Score: ${output.overallScore}/100")
 *     output.issues.forEach { println("Issue: ${it.description}") }
 * }
 * ```
 */
class KoinBestPracticesSkill : BaseSkill<KoinBestPracticesInput, KoinBestPracticesOutput>() {

    override val metadata = skillMetadata {
        id = "core.di.koin_best_practices"
        name = "Koin Best Practices Skill"
        description = "Analyzes Koin code for best practices and provides recommendations"
        category = SkillCategory.UTILITY
        version = "1.0.0"
        tags = listOf("di", "koin", "best-practices", "code-analysis")
    }

    override suspend fun validate(input: KoinBestPracticesInput): SkillResult<Unit> {
        return validate {
            require(input.codeSnippets.isNotEmpty()) {
                "codeSnippets must not be empty"
            }
            input.codeSnippets.forEach { snippet ->
                requireNotBlank(snippet.fileName, "fileName")
                requireNotBlank(snippet.code, "code")
            }
        }
    }

    override suspend fun doExecute(
        input: KoinBestPracticesInput,
        context: SkillContext,
    ): SkillResult<KoinBestPracticesOutput> {
        checkCancellation()

        val issues = mutableListOf<BestPracticeIssue>()
        val recommendations = mutableListOf<Recommendation>()
        val improvements = mutableListOf<CodeImprovement>()

        input.codeSnippets.forEach { snippet ->
            checkCancellation()
            analyzeSnippet(snippet, issues, improvements)
        }

        checkCancellation()

        // Generate recommendations based on issues and project type
        generateRecommendations(input, issues, recommendations)

        // Add KMP recommendations if requested
        if (input.includeKmpRecommendations) {
            addKmpRecommendations(input.codeSnippets, recommendations)
        }

        // Calculate score
        val score = calculateScore(issues)

        return SkillResult.success(
            KoinBestPracticesOutput(
                overallScore = score,
                issues = issues,
                recommendations = recommendations,
                codeImprovements = improvements,
            )
        )
    }

    private fun analyzeSnippet(
        snippet: CodeSnippet,
        issues: MutableList<BestPracticeIssue>,
        improvements: MutableList<CodeImprovement>,
    ) {
        when (snippet.type) {
            SnippetType.KOIN_MODULE -> analyzeKoinModule(snippet, issues, improvements)
            SnippetType.VIEWMODEL -> analyzeViewModel(snippet, issues, improvements)
            SnippetType.REPOSITORY -> analyzeRepository(snippet, issues)
            SnippetType.COMPOSABLE -> analyzeComposable(snippet, issues, improvements)
            SnippetType.TEST -> analyzeTest(snippet, issues)
            else -> analyzeGeneric(snippet, issues)
        }
    }

    private fun analyzeKoinModule(
        snippet: CodeSnippet,
        issues: MutableList<BestPracticeIssue>,
        improvements: MutableList<CodeImprovement>,
    ) {
        val code = snippet.code

        // Rule: Avoid using get() in module definition
        if (code.contains("get<") && code.contains("single {")) {
            val hasProperInjection = code.contains("singleOf") || code.contains("factoryOf")
            if (!hasProperInjection) {
                issues.add(
                    BestPracticeIssue(
                        fileName = snippet.fileName,
                        severity = IssueSeverity.INFO,
                        rule = "PREFER_DSL_INJECTION",
                        description = "Consider using singleOf/factoryOf DSL for cleaner code",
                        lineHint = "single { SomeClass(get()) }",
                    )
                )
            }
        }

        // Rule: ViewModel should use viewModelOf
        if (code.contains("ViewModel") && code.contains("single {")) {
            issues.add(
                BestPracticeIssue(
                    fileName = snippet.fileName,
                    severity = IssueSeverity.ERROR,
                    rule = "VIEWMODEL_WRONG_SCOPE",
                    description = "ViewModel should use viewModel {} or viewModelOf(), not single {}",
                )
            )
        }

        // Rule: Large module should be split
        val moduleCount = code.split("single|factory|viewModel".toRegex()).size - 1
        if (moduleCount > 10) {
            issues.add(
                BestPracticeIssue(
                    fileName = snippet.fileName,
                    severity = IssueSeverity.WARNING,
                    rule = "LARGE_MODULE",
                    description = "Module has $moduleCount definitions. Consider splitting into smaller feature modules",
                )
            )
        }

        // Rule: Missing interface binding
        if (code.contains("Impl(") && !code.contains("bind<")) {
            issues.add(
                BestPracticeIssue(
                    fileName = snippet.fileName,
                    severity = IssueSeverity.WARNING,
                    rule = "MISSING_INTERFACE_BINDING",
                    description = "Implementation classes should be bound to their interfaces for testability",
                )
            )

            // Suggest improvement
            if (code.contains("single { ") && code.contains("Impl(")) {
                val implRegex = """single\s*\{\s*(\w+)Impl\(""".toRegex()
                val match = implRegex.find(code)
                if (match != null) {
                    val className = match.groupValues[1]
                    improvements.add(
                        CodeImprovement(
                            fileName = snippet.fileName,
                            originalCode = "single { ${className}Impl(get()) }",
                            improvedCode = "singleOf(::${className}Impl) { bind<${className}Repository>() }",
                            explanation = "Use singleOf with bind for interface binding",
                        )
                    )
                }
            }
        }

        // Rule: Named qualifiers for dispatchers
        if (code.contains("Dispatchers.") && !code.contains("named(")) {
            issues.add(
                BestPracticeIssue(
                    fileName = snippet.fileName,
                    severity = IssueSeverity.WARNING,
                    rule = "UNQUALIFIED_DISPATCHERS",
                    description = "Use named qualifiers for CoroutineDispatcher to avoid ambiguity",
                )
            )
        }

        // Rule: androidContext usage
        if (code.contains("get<Context>()") || code.contains("get<Application>()")) {
            issues.add(
                BestPracticeIssue(
                    fileName = snippet.fileName,
                    severity = IssueSeverity.WARNING,
                    rule = "CONTEXT_INJECTION",
                    description = "Use androidContext() instead of get<Context>() for proper context injection",
                )
            )
        }
    }

    private fun analyzeViewModel(
        snippet: CodeSnippet,
        issues: MutableList<BestPracticeIssue>,
        improvements: MutableList<CodeImprovement>,
    ) {
        val code = snippet.code

        // Rule: ViewModel should not call getKoin()
        if (code.contains("getKoin()") || code.contains("KoinComponent")) {
            issues.add(
                BestPracticeIssue(
                    fileName = snippet.fileName,
                    severity = IssueSeverity.ERROR,
                    rule = "VIEWMODEL_SERVICE_LOCATOR",
                    description = "ViewModel should use constructor injection, not service locator pattern",
                )
            )
        }

        // Rule: ViewModel should have constructor injection
        if (!code.contains("constructor") && code.contains("class") && code.contains("ViewModel")) {
            // Check if using primary constructor
            val classDefRegex = """class\s+\w+ViewModel\s*\(""".toRegex()
            if (!classDefRegex.containsMatchIn(code)) {
                issues.add(
                    BestPracticeIssue(
                        fileName = snippet.fileName,
                        severity = IssueSeverity.WARNING,
                        rule = "VIEWMODEL_CONSTRUCTOR",
                        description = "ViewModel should use constructor injection for dependencies",
                    )
                )
            }
        }
    }

    private fun analyzeRepository(
        snippet: CodeSnippet,
        issues: MutableList<BestPracticeIssue>,
    ) {
        val code = snippet.code

        // Rule: Repository should not depend on Context directly
        if (code.contains("Context") && code.contains("Repository")) {
            issues.add(
                BestPracticeIssue(
                    fileName = snippet.fileName,
                    severity = IssueSeverity.WARNING,
                    rule = "REPOSITORY_CONTEXT_DEPENDENCY",
                    description = "Repository should not depend on Android Context directly. Use abstraction instead.",
                )
            )
        }
    }

    private fun analyzeComposable(
        snippet: CodeSnippet,
        issues: MutableList<BestPracticeIssue>,
        improvements: MutableList<CodeImprovement>,
    ) {
        val code = snippet.code

        // Rule: Use koinViewModel() in Composable
        if (code.contains("getViewModel") || code.contains("get<") && code.contains("ViewModel")) {
            issues.add(
                BestPracticeIssue(
                    fileName = snippet.fileName,
                    severity = IssueSeverity.ERROR,
                    rule = "COMPOSABLE_VIEWMODEL_INJECTION",
                    description = "Use koinViewModel() for ViewModel injection in Composable functions",
                )
            )
        }

        // Rule: Use koinInject for non-ViewModel
        if (code.contains("getKoin().get<") && !code.contains("ViewModel")) {
            issues.add(
                BestPracticeIssue(
                    fileName = snippet.fileName,
                    severity = IssueSeverity.WARNING,
                    rule = "COMPOSABLE_INJECTION",
                    description = "Use koinInject() for dependency injection in Composable",
                )
            )
        }
    }

    private fun analyzeTest(
        snippet: CodeSnippet,
        issues: MutableList<BestPracticeIssue>,
    ) {
        val code = snippet.code

        // Rule: Test should use KoinTestRule
        if (code.contains("KoinTest") && !code.contains("KoinTestRule")) {
            issues.add(
                BestPracticeIssue(
                    fileName = snippet.fileName,
                    severity = IssueSeverity.WARNING,
                    rule = "TEST_KOIN_RULE",
                    description = "Use KoinTestRule for proper Koin lifecycle in tests",
                )
            )
        }

        // Rule: Test should use declareMock or MockProviderRule
        if (code.contains("mockk") && !code.contains("declareMock") && !code.contains("MockProviderRule")) {
            issues.add(
                BestPracticeIssue(
                    fileName = snippet.fileName,
                    severity = IssueSeverity.INFO,
                    rule = "TEST_MOCK_PROVIDER",
                    description = "Consider using MockProviderRule for consistent mock creation",
                )
            )
        }
    }

    private fun analyzeGeneric(
        snippet: CodeSnippet,
        issues: MutableList<BestPracticeIssue>,
    ) {
        val code = snippet.code

        // Rule: Avoid GlobalContext usage outside Application
        if (code.contains("GlobalContext.get()") || code.contains("KoinJavaComponent")) {
            issues.add(
                BestPracticeIssue(
                    fileName = snippet.fileName,
                    severity = IssueSeverity.WARNING,
                    rule = "GLOBAL_CONTEXT_USAGE",
                    description = "Avoid using GlobalContext directly. Use constructor injection instead.",
                )
            )
        }
    }

    private fun generateRecommendations(
        input: KoinBestPracticesInput,
        issues: List<BestPracticeIssue>,
        recommendations: MutableList<Recommendation>,
    ) {
        // Module organization recommendations
        val hasLargeModule = issues.any { it.rule == "LARGE_MODULE" }
        if (hasLargeModule) {
            recommendations.add(
                Recommendation(
                    category = RecommendationCategory.MODULE_ORGANIZATION,
                    title = "Split large modules",
                    description = "Organize your Koin modules by feature or layer",
                    example = """
                        // ✅ Organized by layer
                        val networkModule = module { ... }
                        val databaseModule = module { ... }
                        val repositoryModule = module { ... }
                        val viewModelModule = module { ... }
                        
                        // ✅ Or by feature
                        val userFeatureModule = module { ... }
                        val orderFeatureModule = module { ... }
                    """.trimIndent(),
                )
            )
        }

        // Scope usage recommendations
        val hasWrongScope = issues.any { it.rule == "VIEWMODEL_WRONG_SCOPE" }
        if (hasWrongScope) {
            recommendations.add(
                Recommendation(
                    category = RecommendationCategory.SCOPE_USAGE,
                    title = "Use correct scopes",
                    description = "Use viewModel {} for ViewModels, single {} for singletons, factory {} for new instances",
                    example = """
                        val viewModelModule = module {
                            // ✅ Correct: Use viewModelOf for ViewModels
                            viewModelOf(::UserViewModel)
                            
                            // ✅ Correct: Use singleOf for repositories (singletons)
                            singleOf(::UserRepositoryImpl) { bind<UserRepository>() }
                            
                            // ✅ Correct: Use factoryOf for use cases (new instance each time)
                            factoryOf(::GetUserUseCase)
                        }
                    """.trimIndent(),
                )
            )
        }

        // Testing recommendations
        val hasTestIssues = issues.any { it.rule.startsWith("TEST_") }
        if (hasTestIssues) {
            recommendations.add(
                Recommendation(
                    category = RecommendationCategory.TESTING,
                    title = "Improve test setup",
                    description = "Use Koin testing utilities for cleaner tests",
                    example = """
                        class UserViewModelTest : KoinTest {
                            @get:Rule
                            val koinTestRule = KoinTestRule.create {
                                modules(testModule)
                            }
                            
                            @get:Rule
                            val mockProvider = MockProviderRule.create { clazz ->
                                mockkClass(clazz)
                            }
                            
                            private val viewModel: UserViewModel by inject()
                        }
                    """.trimIndent(),
                )
            )
        }

        // Performance recommendations
        val moduleCount = input.codeSnippets.count { it.type == SnippetType.KOIN_MODULE }
        if (moduleCount > 5) {
            recommendations.add(
                Recommendation(
                    category = RecommendationCategory.PERFORMANCE,
                    title = "Use lazy modules for faster startup",
                    description = "Consider using lazyModule() for feature modules that aren't needed immediately",
                    example = """
                        // Define lazy module
                        val featureModule = lazyModule {
                            viewModelOf(::FeatureViewModel)
                        }
                        
                        // Load on demand
                        loadKoinModules(featureModule)
                        
                        // Unload when done
                        unloadKoinModules(featureModule)
                    """.trimIndent(),
                )
            )
        }
    }

    private fun addKmpRecommendations(
        snippets: List<CodeSnippet>,
        recommendations: MutableList<Recommendation>,
    ) {
        // Check for Android-specific code
        val hasAndroidSpecific = snippets.any { snippet ->
            snippet.code.contains("androidContext") ||
                    snippet.code.contains("android.") ||
                    snippet.code.contains("Context")
        }

        if (hasAndroidSpecific) {
            recommendations.add(
                Recommendation(
                    category = RecommendationCategory.KMP_READINESS,
                    title = "Prepare for KMP",
                    description = "Isolate platform-specific code for KMP readiness",
                    example = """
                        // commonMain
                        expect class PlatformContext
                        
                        val commonModule = module {
                            single<UserRepository> { UserRepositoryImpl(get()) }
                        }
                        
                        // androidMain  
                        actual typealias PlatformContext = Context
                        
                        val androidModule = module {
                            single { androidContext() as PlatformContext }
                        }
                        
                        // iosMain
                        actual class PlatformContext
                        
                        val iosModule = module {
                            single { PlatformContext() }
                        }
                    """.trimIndent(),
                )
            )
        }

        recommendations.add(
            Recommendation(
                category = RecommendationCategory.KMP_READINESS,
                title = "Use Koin multiplatform",
                description = "Use koin-core for shared code, koin-android only in Android module",
                example = """
                    // build.gradle.kts (shared)
                    commonMain {
                        dependencies {
                            implementation("io.insert-koin:koin-core:3.5.6")
                        }
                    }
                    androidMain {
                        dependencies {
                            implementation("io.insert-koin:koin-android:3.5.6")
                        }
                    }
                """.trimIndent(),
            )
        )
    }

    private fun calculateScore(issues: List<BestPracticeIssue>): Int {
        var score = 100

        issues.forEach { issue ->
            score -= when (issue.severity) {
                IssueSeverity.ERROR -> 15
                IssueSeverity.WARNING -> 5
                IssueSeverity.INFO -> 1
            }
        }

        return score.coerceIn(0, 100)
    }
}
