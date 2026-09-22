---
name: Decision Tree for AI Agents
description: Quick decision guide for choosing the right pattern/template
compliance_level: MANDATORY
tags: [decision, guide, quick-reference]
version: 2.2.0
last_updated: 2026-01-24
---

# 🌳 AI Agent Decision Tree

> **Purpose**: Fast navigation for AI agents to choose correct patterns, templates, and guides
> 
> **Use When**: Agent needs to determine which approach to take for a given task
> 
> **Benefit**: Reduces confusion, ensures consistent pattern usage

---

## 🎯 What Are You Building?

```
START HERE
    │
    ├─► Creating a reusable skill?
    │   └─► Use @gen-skill prompt
    │       Template: DomainSkillGuide.kt
    │       Reference: 30-skill-analysis.md, 24-validation-rules.md
    │       Pattern: BaseSkill<Input, Output>
    │
    ├─► Building a pipeline (multi-step workflow)?
    │   └─► Use @gen-pipeline prompt
    │       Template: PipelineExamples.kt, CompletePipelineExample.kt
    │       Reference: 31-pipeline-patterns.md
    │       Pattern: pipeline<T> DSL
    │
    ├─► Writing a ViewModel?
    │   │
    │   ├─► For agent debug/demo UI (AgentController)?
    │   │   └─► Follow AgentDemoViewModel.kt
    │   │       Reference: 07-state-management.md (AgentController pattern)
    │   │       When: Testing/debugging agent skills with UI
    │   │
    │   └─► For normal app feature (99% use case)?
    │       └─► Use @gen-viewmodel prompt
    │           Template: UseCaseViewModelExample.kt
    │           Reference: 07-state-management.md (MVI pattern)
    │           Pattern: ViewModel + UseCase injection
    │
    ├─► Writing UI (Compose)?
    │   └─► Use @gen-screen prompt
    │       Reference: 05-jetpack-compose.md
    │       Pattern: Route (stateful) + Screen (stateless)
    │       Stability: Use @Immutable/@Stable annotations
    │
    ├─► Writing Repository?
    │   └─► Use @gen-repository prompt
    │       Reference: 01-architecture.md, 14-offline-first.md
    │       Pattern: Single Source of Truth, offline-first
    │
    ├─► Writing UseCase?
    │   └─► Use @gen-usecase prompt
    │       Reference: 01-architecture.md
    │       Pattern: Operator invoke, Result<T> return
    │
    ├─► Writing tests?
    │   │
    │   ├─► For Skill?
    │   │   └─► Use @gen-skill-test prompt
    │   │       Template: SkillTestTemplate.kt
    │   │       Coverage: Validation, Execution, Cancellation, Edge Cases
    │   │
    │   ├─► For ViewModel?
    │   │   └─► Use @gen-viewmodel-test prompt
    │   │       Reference: 11-testing.md, 29-testing-automation.md
    │   │       Tools: JUnit5, MockK, Turbine
    │   │
    │   ├─► For UseCase?
    │   │   └─► Use @gen-usecase-test prompt
    │   │       Coverage: All validation scenarios + business logic
    │   │
    │   ├─► For Repository?
    │   │   └─► Use @gen-repository-test prompt
    │   │       Coverage: Offline/online scenarios, sync logic
    │   │
    │   ├─► For Compose Screen?
    │   │   └─► Use @gen-screen-test prompt
    │   │       Tools: Compose Test, Paparazzi/Roborazzi
    │   │
    │   └─► For Integration?
    │       └─► Use @gen-integration-test prompt
    │           Coverage: Multi-layer interactions
    │
    ├─► Analyzing code quality?
    │   │
    │   ├─► Skill quality?
    │   │   └─► Use @analyze-skill prompt
    │   │       Checks: Validation coverage, error handling, cancellation, docs
    │   │       Score: 0-100 (aim for 90+)
    │   │
    │   ├─► Test coverage?
    │   │   └─► Use @analyze-test-coverage prompt
    │   │       Identifies: Untested paths, missing scenarios
    │   │
    │   ├─► Test quality?
    │   │   └─► Use @analyze-test-quality prompt
    │   │       Detects: Test smells, weak assertions, flaky tests
    │   │
    │   └─► Performance?
    │       └─► Use @optimize-skill prompt
    │           Analyzes: Algorithm efficiency, memory usage, concurrency
    │
    ├─► Improving existing code?
    │   │
    │   ├─► Adding validation?
    │   │   └─► Use @add-validation prompt
    │   │       Reference: 24-validation-rules.md
    │   │       Goal: 100% input field coverage
    │   │
    │   ├─► Refactoring legacy code?
    │   │   └─► Reference: 27-refactoring-patterns.md
    │   │       Patterns: LiveData→StateFlow, XML→Compose, Hilt↔Koin
    │   │
    │   └─► Performance optimization?
    │       └─► Reference: 25-performance-benchmarks.md
    │           Tools: Profiler, Baseline Profiles, Metrics
    │
    ├─► Setting up DI?
    │   │
    │   ├─► Using Hilt?
    │   │   └─► Reference: HiltKoinSetup.kt (Hilt section)
    │   │       Guide: 08-dependency-injection.md
    │   │       Pattern: @HiltViewModel, @Binds, modules
    │   │
    │   └─► Using Koin?
    │       └─► Reference: HiltKoinSetup.kt (Koin section)
    │           Guide: 08-dependency-injection.md
    │           Skills: KoinValidationSkill, KoinModuleGeneratorSkill
    │           Pattern: module {}, viewModelOf, singleOf
    │
    ├─► Validating inputs?
    │   └─► Reference: 24-validation-rules.md
    │       DSL: Validation.kt
    │       Functions: requireNotBlank, requireValidEmail, requireInRange, etc.
    │       Pattern: validate { } block
    │
    ├─► Handling errors?
    │   └─► Reference: 10-error-handling.md
    │       Pattern: Result<T>, SkillResult<T>
    │       Strategy: Never throw from domain layer
    │
    ├─► Managing state?
    │   └─► Reference: 07-state-management.md
    │       Pattern: StateFlow + Channel for events
    │       Annotation: @Immutable for state classes
    │
    ├─► Building navigation?
    │   └─► Reference: 13-navigation.md
    │       Pattern: Type-safe navigation with sealed class routes
    │
    ├─► Implementing offline-first?
    │   └─► Reference: 14-offline-first.md
    │       Pattern: Room as SSOT, sync with WorkManager
    │
    └─► Multiplatform (KMP)?
        └─► Reference: 15-kmp-readiness.md, 16-compose-multiplatform.md
            Pattern: expect/actual, shared domain layer
```

---

## 📋 Quick Pattern Selection Matrix

| Task | Pattern | Template/Example | Guide | AI Prompt |
|------|---------|------------------|-------|-----------|
| **Skills & Pipelines** |  |  |  |  |
| New Skill | BaseSkill<I,O> | DomainSkillGuide.kt | 30-skill-analysis.md | @gen-skill |
| New Pipeline | pipeline DSL | PipelineExamples.kt | ARCHITECTURE.md | @gen-pipeline |
| Skill Test | JUnit5 + MockK | SkillTestTemplate.kt | 29-testing-automation.md | @gen-skill-test |
| **ViewModels** |  |  |  |  |
| App ViewModel | UseCase injection | UseCaseViewModelExample.kt | 07-state-management.md | @gen-viewmodel |
| Agent ViewModel | AgentController | AgentDemoViewModel.kt | 07-state-management.md | Manual (rare) |
| ViewModel Test | Turbine + MockK | ViewModelTestTemplate.kt | 11-testing.md | @gen-viewmodel-test |
| **UI** |  |  |  |  |
| Compose Screen | Route/Screen | ComposeExample.kt | 05-jetpack-compose.md | @gen-screen |
| Screen Test | Compose Test | ComposeScreenTestTemplate.kt | 11-testing.md | @gen-screen-test |
| **Data Layer** |  |  |  |  |
| Repository | Offline-first | UserRepositoryImpl.kt | 01-architecture.md | @gen-repository |
| Repository Test | Mock API/DAO | RepositoryTestTemplate.kt | 11-testing.md | @gen-repository-test |
| UseCase | operator invoke | UseCaseViewModelExample.kt | 01-architecture.md | @gen-usecase |
| **Integration** |  |  |  |  |
| Multi-layer Test | ViewModel→UseCase→Repo | IntegrationTestTemplate.kt | 11-testing.md | @gen-integration-test |
| **Validation & Errors** |  |  |  |  |
| Validation | validate DSL | Validation.kt | 24-validation-rules.md | @add-validation |
| Error Handling | SkillResult<T> | SkillResult.kt | 10-error-handling.md | Manual |
| **Quality & Analysis** |  |  |  |  |
| Skill Quality | Analysis tool | - | 30-skill-analysis.md | @analyze-skill |
| Test Coverage | Coverage tool | - | 29-testing-automation.md | @analyze-test-coverage |
| Performance | Optimization | - | 25-performance-benchmarks.md | @optimize-skill |

---

## 🔍 Decision by File Type

### When Looking at a `.kt` File

```
File Type Detection:
    │
    ├─► Contains "ViewModel"?
    │   └─► Follow: 07-state-management.md
    │       Pattern: StateFlow + Channel
    │       Test with: @gen-viewmodel-test
    │
    ├─► Contains "Repository"?
    │   └─► Follow: 01-architecture.md, 14-offline-first.md
    │       Pattern: Single Source of Truth
    │       Test with: @gen-repository-test
    │
    ├─► Contains "UseCase"?
    │   └─► Follow: 01-architecture.md
    │       Pattern: operator invoke
    │       Test with: @gen-usecase-test
    │
    ├─► Contains "BaseSkill"?
    │   └─► Follow: 30-skill-analysis.md
    │       Analyze with: @analyze-skill
    │       Test with: @gen-skill-test
    │
    ├─► Contains "@Composable"?
    │   └─► Follow: 05-jetpack-compose.md, 06-compose-performance.md
    │       Pattern: Stateless + @Immutable
    │       Test with: @gen-screen-test
    │
    └─► Contains "pipeline<"?
        └─► Follow: ARCHITECTURE.md
            Reference: PipelineExamples.kt
            Generate with: @gen-pipeline
```

---

## ⚡ Common Scenarios

### Scenario 1: "I need to add a new feature"

```
Steps:
1. Generate UseCase: @gen-usecase (business logic)
2. Generate ViewModel: @gen-viewmodel (presentation state)
3. Generate Screen: @gen-screen (UI)
4. Generate Repository if needed: @gen-repository (data access)
5. Generate tests: @gen-viewmodel-test, @gen-usecase-test, @gen-screen-test
6. Analyze quality: @analyze-test-coverage
```

**Reference**: 28-code-generation.md (Feature Generation)

### Scenario 2: "I need to create an agent skill"

```
Steps:
1. Generate Skill: @gen-skill
2. Add comprehensive validation: @add-validation
3. Generate tests: @gen-skill-test
4. Analyze quality: @analyze-skill (aim for 90+ score)
5. Optimize if needed: @optimize-skill
```

**Reference**: 30-skill-analysis.md

### Scenario 3: "I need to improve test coverage"

```
Steps:
1. Analyze coverage: @analyze-test-coverage
2. Generate missing tests:
   - @gen-viewmodel-test
   - @gen-usecase-test
   - @gen-repository-test
3. Validate test quality: @analyze-test-quality
4. Fix test smells if detected
```

**Reference**: 29-testing-automation.md

### Scenario 4: "I need to refactor legacy code"

```
Steps:
1. Identify pattern: 27-refactoring-patterns.md
2. Choose migration path:
   - LiveData → StateFlow
   - XML → Compose
   - Hilt ↔ Koin
3. Follow step-by-step guide
4. Generate new tests
5. Verify quality with analysis prompts
```

**Reference**: 27-refactoring-patterns.md

### Scenario 5: "I need to optimize performance"

```
Steps:
1. Analyze current metrics: @optimize-skill or 25-performance-benchmarks.md
2. Profile with Android Profiler
3. Apply optimizations:
   - Algorithm improvements
   - Memory reduction
   - Concurrency optimization
4. Measure again
5. Document improvements
```

**Reference**: 25-performance-benchmarks.md

---

## 🎓 Learning Path

### For New AI Agents

**Phase 1: Core Patterns** (Essential)
1. Start: [01-architecture.md](./01-architecture.md) - Understand layers
2. Read: [07-state-management.md](./07-state-management.md) - StateFlow pattern
3. Study: [05-jetpack-compose.md](./05-jetpack-compose.md) - Compose basics
4. Practice: @gen-viewmodel, @gen-screen, @gen-usecase

**Phase 2: Quality** (Important)
5. Read: [11-testing.md](./11-testing.md) - Testing strategies
6. Study: [24-validation-rules.md](./24-validation-rules.md) - Validation matrix
7. Read: [10-error-handling.md](./10-error-handling.md) - Error patterns
8. Practice: @gen-viewmodel-test, @analyze-test-coverage

**Phase 3: Skills Framework** (For Agent Development)
9. Study: [30-skill-analysis.md](./30-skill-analysis.md) - Skill patterns
10. Read: [DomainSkillGuide.kt](../templates/examples/DomainSkillGuide.kt) - Full example
11. Practice: @gen-skill, @gen-pipeline, @analyze-skill

**Phase 4: Advanced** (Optional)
12. Read: [15-kmp-readiness.md](./15-kmp-readiness.md) - Multiplatform
13. Study: [27-refactoring-patterns.md](./27-refactoring-patterns.md) - Migrations
14. Read: [25-performance-benchmarks.md](./25-performance-benchmarks.md) - Optimization

---

## 🚨 Anti-Pattern Detection

### ❌ If You See These, Stop and Refer to Guide

| Anti-Pattern | Problem | Correct Approach | Guide |
|--------------|---------|------------------|-------|
| `var state = MutableStateFlow()` | Exposed mutable state | Use private _state | 07-state-management.md |
| `GlobalScope.launch` | Uncontrolled coroutine | Use viewModelScope | 03-coroutines-concurrency.md |
| `SharedFlow<Event>(replay=0)` | Events for UI | Use Channel<Event> | 07-state-management.md |
| Repository in ViewModel | Skipping domain layer | Inject UseCase instead | 01-architecture.md |
| `throw Exception` in skill | Exceptions in domain | Return SkillResult.failure() | 10-error-handling.md |
| No validation in skill | Missing validation | Use validation DSL | 24-validation-rules.md |
| `catch (e: Exception)` without rethrowing CancellationException | Swallowing cancellation | Rethrow explicitly | 03-coroutines-concurrency.md |
| Stateful @Composable | Recomposition issues | Separate Route/Screen | 05-jetpack-compose.md |
| No @Immutable on state | Compose instability | Add @Immutable | 06-compose-performance.md |

**Reference**: [20-anti-patterns.md](./20-anti-patterns.md)

---

## 📊 Quality Checklist

Before completing any task, verify:

### For Skills
- [ ] Extends BaseSkill<Input, Output>
- [ ] Input/Output marked @Immutable
- [ ] validate() covers 100% of fields
- [ ] doExecute() returns SkillResult
- [ ] Rethrows CancellationException
- [ ] Calls checkCancellation()
- [ ] Has comprehensive KDoc
- [ ] @analyze-skill score ≥ 90

### For ViewModels
- [ ] Extends AndroidX ViewModel
- [ ] Uses viewModelScope (not custom scope)
- [ ] Private _state, public StateFlow
- [ ] Channel for events (not SharedFlow)
- [ ] State class marked @Immutable
- [ ] Injects UseCases (not Repositories)
- [ ] Has ViewModel tests
- [ ] @analyze-test-coverage ≥ 80%

### For Compose
- [ ] Stateless composables
- [ ] Parameters: Required → State → Modifier → Callbacks
- [ ] State classes marked @Immutable
- [ ] No business logic in composables
- [ ] Route/Screen separation
- [ ] Has UI tests or screenshot tests

### For Tests
- [ ] JUnit5 + MockK
- [ ] runTest for coroutines
- [ ] Descriptive test names
- [ ] Covers happy path + errors + edge cases
- [ ] No real dependencies (mocked)
- [ ] @analyze-test-quality passes

---

## 🔗 Quick Links

### Most Used Prompts
- [@gen-viewmodel](../AI_PROMPTS.md#gen-viewmodel) - Generate ViewModel
- [@gen-skill](../AI_PROMPTS.md#gen-skill) - Generate Skill
- [@gen-screen](../AI_PROMPTS.md#gen-screen) - Generate Compose Screen
- [@analyze-skill](../AI_PROMPTS.md#analyze-skill) - Analyze Skill Quality
- [@gen-skill-test](../AI_PROMPTS.md#gen-skill-test) - Generate Skill Tests

### Most Referenced Guides
- [Architecture](./01-architecture.md) - Clean Architecture patterns
- [State Management](./07-state-management.md) - StateFlow + MVI
- [Compose](./05-jetpack-compose.md) - Jetpack Compose patterns
- [Validation](./24-validation-rules.md) - Input validation
- [Testing](./11-testing.md) - Test strategies

### Templates
- [UseCaseViewModelExample.kt](../templates/examples/UseCaseViewModelExample.kt)
- [DomainSkillGuide.kt](../templates/examples/DomainSkillGuide.kt)
- [PipelineExamples.kt](../templates/examples/PipelineExamples.kt)
- [ComposeExample.kt](../templates/compose/ComposeExample.kt)

---

**Last Updated**: 2026-01-24  
**Version**: 2.2.0  
**Maintained By**: AI Agent Framework
