---
name: Automated Code Generation
description: Templates and strategies for automated code generation in Android projects
compliance_level: RECOMMENDED
tags: [code-generation, automation, templates, productivity, ai]
version: 2.2.0
last_updated: 2026-01-24
---

# Automated Code Generation

## Context
Automated code generation accelerates development, ensures consistency, and reduces boilerplate. This guide provides templates and strategies for AI agents to generate production-ready code.

## Code Generation Strategies

### Primary: AI-Driven Generation (Recommended 95% of cases)
**Why:** Always up-to-date, context-aware, zero maintenance

See [AI_PROMPTS.md](../AI_PROMPTS.md) for comprehensive prompts.

### Secondary: IntelliJ Live Templates
**When:** Quick scaffolding, offline development
**Note:** Requires manual updates when conventions change

### Advanced: KSP (Kotlin Symbol Processing)
**When:** 100+ similar components, large teams only
**Warning:** High maintenance burden, complex setup

---

## 1. ViewModel Generation

### Input Requirements
```kotlin
data class ViewModelSpec(
    val name: String,                    // e.g., "User"
    val features: List<String>,          // e.g., ["load", "update", "delete"]
    val stateProperties: List<Property>, // UI state fields
    val events: List<String>,            // One-time events
    val useCases: List<String>,          // Dependencies
    val diFramework: DIFramework,        // Hilt or Koin
)

data class Property(
    val name: String,
    val type: String,
    val defaultValue: String?,
    val isNullable: Boolean = false,
)

enum class DIFramework { HILT, KOIN }
```

### Template: MVI ViewModel
```kotlin
// Template: ${NAME}ViewModel.kt
package ${PACKAGE}.presentation.${FEATURE}

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
${DI_IMPORTS}

/**
 * ViewModel for ${NAME} feature.
 * 
 * Features:
${FEATURE_LIST}
 * 
 * @see ${PACKAGE}.domain.usecases
 */
${DI_ANNOTATION}
class ${NAME}ViewModel ${DI_CONSTRUCTOR}(
${USECASE_PARAMETERS}
) : ViewModel() {
    
    private val _state = MutableStateFlow(${NAME}UiState())
    val state: StateFlow<${NAME}UiState> = _state.asStateFlow()
    
    private val _events = Channel<${NAME}Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    
${FEATURE_IMPLEMENTATIONS}
}

/**
 * UI State for ${NAME} screen.
 */
@Immutable
data class ${NAME}UiState(
${STATE_PROPERTIES}
) {
${DERIVED_PROPERTIES}
}

/**
 * One-time events for ${NAME} screen.
 */
sealed interface ${NAME}Event {
${EVENT_CLASSES}
}
```

### Generation Example
```kotlin
// Input
val spec = ViewModelSpec(
    name = "Profile",
    features = listOf("load", "update"),
    stateProperties = listOf(
        Property("name", "String", "\"\""),
        Property("email", "String", "\"\""),
        Property("isLoading", "Boolean", "false"),
        Property("error", "String?", "null", isNullable = true),
    ),
    events = listOf("ShowSnackbar", "NavigateBack"),
    useCases = listOf("GetProfileUseCase", "UpdateProfileUseCase"),
    diFramework = DIFramework.HILT,
)

// Output: ProfileViewModel.kt
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val getProfile: GetProfileUseCase,
    private val updateProfile: UpdateProfileUseCase,
) : ViewModel() {
    
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()
    
    private val _events = Channel<ProfileEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    
    fun loadProfile(userId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            
            getProfile(userId)
                .onSuccess { profile ->
                    _state.update {
                        it.copy(
                            name = profile.name,
                            email = profile.email,
                            isLoading = false,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = error.message,
                        )
                    }
                    _events.send(ProfileEvent.ShowSnackbar(error.message ?: "Error loading profile"))
                }
        }
    }
    
    fun updateProfile(name: String, email: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            
            updateProfile(UpdateProfileRequest(name, email))
                .onSuccess {
                    _state.update { it.copy(name = name, email = email, isLoading = false) }
                    _events.send(ProfileEvent.ShowSnackbar("Profile updated"))
                    _events.send(ProfileEvent.NavigateBack)
                }
                .onFailure { error ->
                    _state.update { it.copy(isLoading = false, error = error.message) }
                }
        }
    }
}

@Immutable
data class ProfileUiState(
    val name: String = "",
    val email: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val canSave: Boolean get() = name.isNotBlank() && email.isNotBlank() && !isLoading
}

sealed interface ProfileEvent {
    data class ShowSnackbar(val message: String) : ProfileEvent
    data object NavigateBack : ProfileEvent
}
```

---

## 2. Repository Generation

### Template: Repository Interface + Implementation
```kotlin
// Template: ${NAME}Repository.kt
package ${PACKAGE}.data.repository

import ${PACKAGE}.domain.model.${NAME}
import kotlinx.coroutines.flow.Flow

/**
 * Repository for ${NAME} data.
 */
interface ${NAME}Repository {
${REPOSITORY_METHODS}
}

// Template: ${NAME}RepositoryImpl.kt
package ${PACKAGE}.data.repository

import ${PACKAGE}.data.local.dao.${NAME}Dao
import ${PACKAGE}.data.remote.api.${NAME}Api
import ${PACKAGE}.domain.model.${NAME}
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Implementation of ${NAME}Repository.
 */
class ${NAME}RepositoryImpl @Inject constructor(
    private val api: ${NAME}Api,
    private val dao: ${NAME}Dao,
) : ${NAME}Repository {
    
${METHOD_IMPLEMENTATIONS}
}
```

### Generation Example
```kotlin
// Input
val spec = RepositorySpec(
    name = "User",
    methods = listOf(
        RepositoryMethod("getUser", "Result<User>", listOf(Parameter("id", "String")), isSuspend = true),
        RepositoryMethod("observeUser", "Flow<User?>", listOf(Parameter("id", "String")), isSuspend = false),
        RepositoryMethod("updateUser", "Result<Unit>", listOf(Parameter("user", "User")), isSuspend = true),
    ),
    hasLocal = true,
    hasRemote = true,
)

// Output: UserRepository.kt + UserRepositoryImpl.kt
interface UserRepository {
    suspend fun getUser(id: String): Result<User>
    fun observeUser(id: String): Flow<User?>
    suspend fun updateUser(user: User): Result<Unit>
}

class UserRepositoryImpl @Inject constructor(
    private val api: UserApi,
    private val dao: UserDao,
) : UserRepository {
    
    override suspend fun getUser(id: String): Result<User> {
        return try {
            // Try remote first
            val remoteUser = api.getUser(id)
            dao.insertUser(remoteUser.toEntity())
            Result.success(remoteUser.toDomain())
        } catch (e: Exception) {
            // Fallback to cache
            dao.getUser(id)?.let {
                Result.success(it.toDomain())
            } ?: Result.failure(e)
        }
    }
    
    override fun observeUser(id: String): Flow<User?> {
        return dao.observeUser(id).map { it?.toDomain() }
    }
    
    override suspend fun updateUser(user: User): Result<Unit> {
        return try {
            api.updateUser(user.toRequest())
            dao.updateUser(user.toEntity())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

---

## 3. UseCase Generation

### Template: UseCase
```kotlin
// Template: ${NAME}UseCase.kt
package ${PACKAGE}.domain.usecases

import ${PACKAGE}.domain.repository.${REPOSITORY}
import ${PACKAGE}.domain.model.${MODEL}
import javax.inject.Inject

/**
 * UseCase: ${DESCRIPTION}
 * 
 * @see ${REPOSITORY}
 */
class ${NAME}UseCase @Inject constructor(
    private val repository: ${REPOSITORY},
${ADDITIONAL_DEPENDENCIES}
) {
    suspend operator fun invoke(${PARAMETERS}): Result<${RETURN_TYPE}> {
${VALIDATION}
        return repository.${REPOSITORY_METHOD}(${ARGUMENTS})
    }
}
```

### Generation Example
```kotlin
// Input
val spec = UseCaseSpec(
    name = "GetUserProfile",
    description = "Fetches user profile with validation",
    repository = "UserRepository",
    repositoryMethod = "getUser",
    parameters = listOf(Parameter("userId", "String")),
    returnType = "User",
    validation = listOf("requireNotBlank(userId, \"userId\")"),
)

// Output: GetUserProfileUseCase.kt
class GetUserProfileUseCase @Inject constructor(
    private val repository: UserRepository,
) {
    suspend operator fun invoke(userId: String): Result<User> {
        if (userId.isBlank()) {
            return Result.failure(IllegalArgumentException("userId cannot be blank"))
        }
        return repository.getUser(userId)
    }
}
```

---

## 4. Composable Screen Generation

### Template: Route + Screen
```kotlin
// Template: ${NAME}Screen.kt
package ${PACKAGE}.presentation.${FEATURE}

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Route for ${NAME} screen (Stateful).
 */
@Composable
fun ${NAME}Route(
    onNavigateBack: () -> Unit,
    viewModel: ${NAME}ViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
${EVENT_HANDLERS}
            }
        }
    }
    
    ${NAME}Screen(
        state = state,
${ACTION_PARAMETERS}
    )
}

/**
 * ${NAME} screen content (Stateless).
 */
@Composable
fun ${NAME}Screen(
    state: ${NAME}UiState,
${ACTION_PARAMETERS_DEFINITION}
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${TITLE}") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
${SCREEN_CONTENT}
        }
    }
}

@Preview
@Composable
private fun ${NAME}ScreenPreview() {
    MaterialTheme {
        ${NAME}Screen(
            state = ${NAME}UiState(),
${PREVIEW_ACTIONS}
        )
    }
}
```

---

## 5. Test Generation

### Template: ViewModel Test
```kotlin
// Template: ${NAME}ViewModelTest.kt
package ${PACKAGE}.presentation.${FEATURE}

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ${NAME}ViewModelTest {
    
    private lateinit var viewModel: ${NAME}ViewModel
${MOCK_DEPENDENCIES}
    
    @BeforeEach
    fun setup() {
${MOCK_SETUP}
        viewModel = ${NAME}ViewModel(${MOCK_ARGUMENTS})
    }
    
${TEST_METHODS}
}
```

### Generation Example
```kotlin
// Output: ProfileViewModelTest.kt
class ProfileViewModelTest {
    
    private lateinit var viewModel: ProfileViewModel
    private val getProfile: GetProfileUseCase = mockk()
    private val updateProfile: UpdateProfileUseCase = mockk()
    
    @BeforeEach
    fun setup() {
        viewModel = ProfileViewModel(getProfile, updateProfile)
    }
    
    @Test
    fun `loadProfile updates state with loading and success`() = runTest {
        // Given
        val userId = "123"
        val expectedProfile = User("123", "John Doe", "john@example.com")
        coEvery { getProfile(userId) } returns Result.success(expectedProfile)
        
        // When
        viewModel.state.test {
            val initial = awaitItem()
            assertEquals(false, initial.isLoading)
            
            viewModel.loadProfile(userId)
            
            val loading = awaitItem()
            assertEquals(true, loading.isLoading)
            
            val success = awaitItem()
            assertEquals(false, success.isLoading)
            assertEquals("John Doe", success.name)
            assertEquals("john@example.com", success.email)
        }
    }
    
    @Test
    fun `loadProfile handles error`() = runTest {
        // Given
        val userId = "123"
        val error = Exception("Network error")
        coEvery { getProfile(userId) } returns Result.failure(error)
        
        // When/Then
        viewModel.state.test {
            awaitItem() // initial
            viewModel.loadProfile(userId)
            awaitItem() // loading
            
            val errorState = awaitItem()
            assertEquals(false, errorState.isLoading)
            assertEquals("Network error", errorState.error)
        }
    }
}
```

---

## 6. AI-Driven Generation Prompts

### Prompt Template: Generate ViewModel
```
Generate a ViewModel for {FEATURE_NAME} with the following:

Requirements:
- Feature: {FEATURE_DESCRIPTION}
- State properties: {STATE_PROPERTIES}
- Actions: {ACTIONS}
- Dependencies: {USECASES}
- DI Framework: {HILT/KOIN}
- Pattern: MVI with StateStoreHolder

Follow these guidelines:
1. Extend AndroidX ViewModel
2. Use StateFlow for state (private mutable, public read-only)
3. Use Channel for one-time events
4. Use viewModelScope for coroutines
5. Add @Immutable to UiState
6. Include derived properties in UiState
7. Add KDoc comments
8. Follow naming conventions from 02-coding-conventions.md
9. Use Result<T> pattern for error handling

Output structure:
1. UiState data class
2. Event sealed interface
3. ViewModel class with DI
4. Feature implementations
5. Private helper methods

Reference:
- skills/guides/07-state-management.md
- skills/templates/compose/AgentDemoViewModel.kt
```

### Prompt Template: Generate Repository
```
Generate Repository interface and implementation for {ENTITY_NAME}:

Requirements:
- Entity: {ENTITY_NAME}
- Operations: {CRUD_OPERATIONS}
- Data sources: {LOCAL/REMOTE/BOTH}
- Offline-first: {YES/NO}
- Caching strategy: {CACHE_FIRST/NETWORK_FIRST/CACHE_THEN_NETWORK}

Implementation details:
1. Interface in domain layer
2. Implementation in data layer
3. Use Result<T> for operations
4. Use Flow for observations
5. Handle errors gracefully
6. Add cache fallback if offline-first
7. Use mappers for entity conversions

Reference:
- skills/guides/01-architecture.md
- skills/guides/14-offline-first.md
```

---

## 7. AI-Driven Generation (Primary Strategy)

**Why AI over Scripts?**
- ✅ **Always up-to-date**: Reads latest guides dynamically
- ✅ **Zero maintenance**: No code to update when patterns change
- ✅ **Context-aware**: Understands nuance and specific requirements
- ✅ **Intelligent**: Adapts to your needs, not rigid templates

### Using AI Prompts

See [AI_PROMPTS.md](../AI_PROMPTS.md) for comprehensive structured prompts.

**Quick Example:**
```
@gen-viewmodel

Generate MVI ViewModel for ProductDetail.

State: product (Product?), isLoading (Boolean), error (String?), quantity (Int = 1)
Actions: loadProduct(id), increaseQuantity(), decreaseQuantity(), addToCart(), onDismissError()
UseCases: GetProductUseCase, AddToCartUseCase
DI: hilt
Special: Cache product data, track view analytics
```

**Output:** Complete, production-ready ViewModel with:
- Proper StateFlow/Channel setup
- All validations
- Error handling with Result<T>
- Analytics integration
- Comprehensive KDoc

### Comparison: AI vs Scripts

| Aspect | AI Prompts | Bash Scripts |
|--------|------------|--------------|
| **Setup Time** | 0 seconds | Hours (write + test + debug) |
| **Maintenance** | None | Every pattern change |
| **Flexibility** | High (understands context) | Low (rigid templates) |
| **Updates** | Automatic (reads guides) | Manual (rewrite code) |
| **Context-Aware** | Yes | No |
| **Adaptability** | Instant | Requires code changes |

**Real Example: StateStoreHolder Pattern**

When StateStoreHolder was added to guide 07:
- **AI Prompts:** Worked immediately (reads guide dynamically)
- **Scripts:** Required rewriting 400+ lines, testing, debugging

---

## 8. IntelliJ IDEA Live Templates (Secondary Strategy)

**Use for:** Quick scaffolding, offline development

**Warning:** Requires manual updates when conventions change. Prefer AI prompts.

### ViewModel Template
```xml
<!-- File: .idea/templates/AndroidViewModel.xml -->
<template name="avm" value="@HiltViewModel&#10;class $NAME$ViewModel @Inject constructor(&#10;    private val $USECASE$: $USECASE_TYPE$,&#10;) : ViewModel() {&#10;    &#10;    private val _state = MutableStateFlow($NAME$UiState())&#10;    val state: StateFlow&lt;$NAME$UiState&gt; = _state.asStateFlow()&#10;    &#10;    private val _events = Channel&lt;$NAME$Event&gt;(Channel.BUFFERED)&#10;    val events = _events.receiveAsFlow()&#10;    &#10;    $END$&#10;}&#10;&#10;@Immutable&#10;data class $NAME$UiState(&#10;    val isLoading: Boolean = false,&#10;)&#10;&#10;sealed interface $NAME$Event {&#10;    &#10;}" description="Android ViewModel with MVI pattern" toReformat="true" toShortenFQNames="true">
  <variable name="NAME" expression="" defaultValue="" alwaysStopAt="true" />
  <variable name="USECASE" expression="" defaultValue="" alwaysStopAt="true" />
  <variable name="USECASE_TYPE" expression="" defaultValue="" alwaysStopAt="true" />
</template>
```

---

## 9. Best Practices

### ✅ DO:
1. **Use AI prompts** as primary generation method (see [AI_PROMPTS.md](../AI_PROMPTS.md))
2. **Be specific** in requirements (types, defaults, validations)
3. **Reference guides** in prompts: "Follow 07-state-management.md"
4. **Validate generated code**:
   ```bash
   ./gradlew detekt
   ./gradlew lint
   ./gradlew test
   ```
5. **Review for business logic** and edge cases
6. **Request complete code** (no TODOs or placeholders)
7. **Add tests** if not generated with @gen-test

### ❌ DON'T:
1. **Use vague prompts** ("create basic ViewModel")
2. **Skip validation** of generated code
3. **Rely on static scripts** (become outdated quickly)
4. **Copy-paste blindly** without understanding
5. **Generate without tests** (use @gen-test or @gen-feature)
6. **Ignore linter errors** from detekt/lint
7. **Mix DI frameworks** without specifying (always state: hilt OR koin)

---

## 11. Advanced: KSP-Based Generation

**When to Use:** Only for teams with 100+ similar components

**Warning:** High maintenance burden, complex setup. Most teams should use AI prompts.

### Before Generation
- [ ] Feature requirements clear
- [ ] Architecture pattern decided (MVI/MVVM)
- [ ] Dependencies identified
- [ ] DI framework chosen (Hilt/Koin)
- [ ] Package structure planned

### After Generation
- [ ] Code compiles without errors
- [ ] Follows naming conventions
- [ ] Has proper KDoc comments
- [ ] Uses correct patterns (StateFlow, Result<T>)
- [ ] Has @Immutable annotations
- [ ] Includes error handling
- [ ] Has TODO markers for manual completion
- [ ] Tests generated (optional)

### Validation
- [ ] Run `./gradlew build`
- [ ] Run `./scripts/validate-templates.sh`
- [ ] Check with `ktlint`
- [ ] Review generated code manually

---

## 10. Advanced: KSP-Based Generation

### Example: @GenerateViewModel Annotation
```kotlin
// Annotation
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class GenerateViewModel(
    val name: String,
    val features: Array<String> = [],
)

// Processor
class ViewModelProcessor : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> {
        resolver.getSymbolsWithAnnotation("GenerateViewModel")
            .forEach { symbol ->
                // Generate ViewModel code
                generateViewModel(symbol)
            }
        return emptyList()
    }
}

// Usage
@GenerateViewModel(
    name = "Profile",
    features = ["load", "update"]
)
interface ProfileFeature
```

---

## Related Guides

### Primary Resources
- **[AI Prompts (START HERE)](../AI_PROMPTS.md)** - Structured prompts for all components
- **[Template Examples](../templates/examples/)** - Reference implementations

### Architecture & Patterns
- [01-architecture.md](./01-architecture.md) - Clean Architecture layers
- [02-coding-conventions.md](./02-coding-conventions.md) - Naming and documentation standards
- [07-state-management.md](./07-state-management.md) - MVI/MVVM patterns
- [08-dependency-injection.md](./08-dependency-injection.md) - Hilt and Koin setup
- [24-validation-rules.md](./24-validation-rules.md) - Input validation patterns

### Quality & Testing
- [11-testing.md](./11-testing.md) - Test generation patterns
- [10-error-handling.md](./10-error-handling.md) - Result<T> and error strategies
- [20-anti-patterns.md](./20-anti-patterns.md) - Common mistakes to avoid
- [21-code-review.md](./21-code-review.md) - Review checklist

### AI Intelligence
- [26-context-aware-suggestions.md](./26-context-aware-suggestions.md) - AI-driven code improvements
- [27-refactoring-patterns.md](./27-refactoring-patterns.md) - Migration strategies

---

## Generation Strategy Decision Matrix

| Your Situation | Best Approach | Tool |
|----------------|---------------|------|
| Learning Android | AI Prompts (with "explain why") | GitHub Copilot / Claude |
| Solo Developer | AI Prompts | GitHub Copilot / Claude |
| Small Team (2-5) | AI Prompts | GitHub Copilot / Claude |
| Medium Team (6-10) | AI Prompts + Live Templates | Copilot + IntelliJ |
| Large Team (10+) | AI Prompts + KSP (rare) | Copilot + Custom KSP |

**95% of teams should use AI Prompts only.**

---

## Tools & Resources
- **[AI_PROMPTS.md](../AI_PROMPTS.md)** - Production-ready generation prompts
- [KSP Documentation](https://kotlinlang.org/docs/ksp-overview.html) - For custom processors
- [IntelliJ Live Templates](https://www.jetbrains.com/help/idea/using-live-templates.html) - Quick scaffolding
- GitHub Copilot - AI pair programmer
- Claude / ChatGPT - For detailed generation with long prompts

---

**Last Updated**: 2026-01-24  
**Maintained By**: AI Agent / TrongLB  
**Primary Strategy**: AI-Driven Generation (zero maintenance, always current)
