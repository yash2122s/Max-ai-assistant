# 🤖 Android Kotlin Agent Skills Guide
## Senior-Level Best Practices for High-Quality, High-Performance Android Apps

> **Purpose**: Comprehensive guidelines for AI agents and developers to ensure production-grade quality in Android Kotlin projects.
> **Version**: 2.2.0
> **Last Updated**: January 2026
> **Applicable To**: Any Android Kotlin project (modern architecture)
> **Compliance Level**: MANDATORY for production code

---

## 📋 Guide Index

This guide has been organized into smaller, maintainable modules for easier reference:

### 🌳 Quick Navigation
| File | Description |
|------|-------------|
| [00-decision-tree.md](./00-decision-tree.md) | **START HERE** - Quick decision guide for choosing patterns/templates |

### Core Architecture & Patterns
| File | Description |
|------|-------------|
| [01-architecture.md](./01-architecture.md) | Clean Architecture, Module Structure, Use Cases, Repository Pattern |
| [02-coding-conventions.md](./02-coding-conventions.md) | Naming Conventions, Documentation Standards, Kotlin Idioms |
| [04-project-structure.md](./04-project-structure.md) | Package-by-Feature, Flat Structure, Module Organization |

### Kotlin & Coroutines
| File | Description |
|------|-------------|
| [03-coroutines-concurrency.md](./03-coroutines-concurrency.md) | Dispatchers, Flow, StateFlow/SharedFlow, Structured Concurrency |

### Jetpack Compose
| File | Description |
|------|-------------|
| [05-jetpack-compose.md](./05-jetpack-compose.md) | Composable Structure, Recomposition, Side Effects, Modifiers |
| [06-compose-performance.md](./06-compose-performance.md) | Compiler Metrics, Stability, Layout Inspector, Baseline Profiles |
| [07-state-management.md](./07-state-management.md) | MVI/MVVM, StateStore Pattern, UI State Design, Event Handling |

### Dependency Management
| File | Description |
|------|-------------|
| [08-dependency-injection.md](./08-dependency-injection.md) | Hilt Setup, Module Organization, Scoping |
| [09-version-catalog.md](./09-version-catalog.md) | Gradle Version Catalog, Convention Plugins |

### Error Handling & Resilience
| File | Description |
|------|-------------|
| [10-error-handling.md](./10-error-handling.md) | Result Pattern, Retry Logic, Network Connectivity, Circuit Breaker |

### Testing
| File | Description |
|------|-------------|
| [11-testing.md](./11-testing.md) | Unit Tests, ViewModel Tests, Flow Tests, Compose UI Tests, Screenshot Tests |

### Security
| File | Description |
|------|-------------|
| [12-security.md](./12-security.md) | Sensitive Data, Network Security, Root Detection, Input Validation |
| [24-validation-rules.md](./24-validation-rules.md) | Validation Matrix, Business Rules, Security Patterns |

### Navigation & Architecture
| File | Description |
|------|-------------|
| [13-navigation.md](./13-navigation.md) | Type-Safe Navigation, Deep Links, Navigation with Results |
| [14-offline-first.md](./14-offline-first.md) | Single Source of Truth, Sync Manager, WorkManager |

### Multiplatform
| File | Description |
|------|-------------|
| [15-kmp-readiness.md](./15-kmp-readiness.md) | KMP Architecture, expect/actual, Ktor, SQLDelight |
| [16-compose-multiplatform.md](./16-compose-multiplatform.md) | Compose Multiplatform Setup, Shared UI, Platform-Specific Code |

### Build & DevOps
| File | Description |
|------|-------------|
| [17-build-configuration.md](./17-build-configuration.md) | Build Variants, ProGuard, Baseline Profiles |
| [18-ci-cd.md](./18-ci-cd.md) | GitHub Actions, Pre-commit Hooks, Security Scanning |

### Quality & Best Practices
| File | Description |
|------|-------------|
| [19-accessibility.md](./19-accessibility.md) | Compose Accessibility, Screen Readers, Touch Targets |
| [20-anti-patterns.md](./20-anti-patterns.md) | Common Mistakes & Detection Rules, God ViewModel, GlobalScope |
| [21-code-review.md](./21-code-review.md) | Review Guidelines, PR Template |
| [22-checklists.md](./22-checklists.md) | Quick Reference Checklists |
| [23-memory-performance.md](./23-memory-performance.md) | Memory Management, Leaks, Performance Patterns |
| [24-validation-rules.md](./24-validation-rules.md) | Validation Matrix, Business Rules, Security Patterns |

### 🚀 AI Intelligence & Optimization
| File | Description |
|------|-------------|
| [25-performance-benchmarks.md](./25-performance-benchmarks.md) | Performance Targets, Optimization Strategies, Profiling Tools |
| [26-context-aware-suggestions.md](./26-context-aware-suggestions.md) | File-Type-Specific Suggestions, AI Analysis, Priority System |
| [27-refactoring-patterns.md](./27-refactoring-patterns.md) | Step-by-Step Migration, LiveData→StateFlow, Hilt↔Koin, XML→Compose |
| [28-code-generation.md](./28-code-generation.md) | AI-Driven Code Generation, Templates, Strategies |
| [29-testing-automation.md](./29-testing-automation.md) | AI Test Coverage Analysis, Test Generation, Quality Validation |
| [30-skill-analysis.md](./30-skill-analysis.md) | Agent Skill Quality Analysis, Performance Optimization, Self-Improvement |
| [31-pipeline-patterns.md](./31-pipeline-patterns.md) | Pipeline DSL, Multi-Step Workflows, Error Handling |
| [32-modern-kotlin-features.md](./32-modern-kotlin-features.md) | Kotlin 2.0+, K2 Compiler, Data Objects, Value Classes |
| [33-agent-feedback-loop.md](./33-agent-feedback-loop.md) | Feedback Tracking, Metrics, Quality Improvement, Auto-Suggestions |
| [34-logging-analytics.md](./34-logging-analytics.md) | Timber, Analytics Abstraction, Crash Reporting, Performance Monitoring |
| [35-gradle-optimization.md](./35-gradle-optimization.md) | Convention Plugins, Build Cache, KSP, CI/CD Optimization |

### ⚡ Quick Access
| Resource | Description |
|----------|-------------|
| **[AI_CONTEXT.md](../AI_CONTEXT.md)** | Quick reference for AI agents (read first!) |
| **[AGENT_SUMMARY.md](../AGENT_SUMMARY.md)** | Core rules, validation functions, error mapping |
| **[AI_PROMPTS.md](../AI_PROMPTS.md)** | All Prompts: @gen-*, @analyze-*, skill analysis |
| **[21-code-review.md](./21-code-review.md)** | `@review` rules with severity levels (🔴🟡🔵) |

---

## 🎯 Key Principles Summary

1. **Clean Architecture**: Strict layer separation, dependency inversion
2. **Single Responsibility**: One class/function = one purpose
3. **Immutability**: Prefer `val` over `var`, immutable data classes
4. **Coroutines**: Proper dispatcher usage, structured concurrency
5. **Compose**: Stateless composables, proper recomposition handling
6. **Compose Performance**: Use compiler metrics, @Stable/@Immutable annotations
7. **Testing**: Unit tests, screenshot tests, UI tests
8. **Error Handling**: Never crash, graceful degradation, retry mechanisms
9. **Security**: Root detection, Play Integrity, encrypted storage, certificate pinning
10. **Performance**: Profile, measure, optimize with Baseline Profiles
11. **Accessibility**: Support screen readers, adequate touch targets
12. **Offline-First**: Local database as source of truth, background sync
13. **KMP-Ready**: Domain layer pure Kotlin, use multiplatform libraries
14. **CI/CD**: Automated testing, linting, security scanning on every PR
15. **Documentation**: Code should be self-documenting + KDoc for APIs

---

## 📚 Recommended Libraries

### Core Libraries

| Category | Library | Purpose |
|----------|---------|---------|
| **DI** | Hilt | Dependency Injection |
| **Async** | Coroutines + Flow | Concurrency |
| **Network** | Retrofit + OkHttp | REST API |
| **Serialization** | Kotlinx Serialization | JSON parsing |
| **Database** | Room | Local persistence |
| **DataStore** | DataStore Preferences | Key-value storage |
| **Image** | Coil | Image loading (Compose-first) |
| **Navigation** | Navigation Compose | Screen navigation |
| **Logging** | Timber | Debug logging |
| **Date/Time** | kotlinx-datetime | Date/time (KMP-ready) |
| **Collections** | kotlinx-collections-immutable | Immutable collections |

### Testing Libraries

| Category | Library | Purpose |
|----------|---------|---------|
| **Unit Testing** | JUnit5 | Test framework |
| **Mocking** | MockK | Kotlin-first mocking |
| **Flow Testing** | Turbine | Flow assertions |
| **Android Unit Tests** | Robolectric | JVM Android tests |
| **Screenshot Tests** | Paparazzi / Roborazzi | Visual regression |
| **UI Testing** | Compose Test | Compose UI testing |

### KMP-Ready Alternatives

| Android-Only | KMP Alternative | Notes |
|--------------|-----------------|-------|
| Room | SQLDelight | Database |
| Retrofit | Ktor Client | HTTP client |
| Hilt | Koin | Dependency injection |
| java.time | kotlinx-datetime | Date/time |
| Timber | Napier / Kermit | Logging |

---

**Document Maintained By**: AI Agent / TrongLB
**Created & Reviewed by**: TrongLB & AI Agents
**Applicable To**: All Android Kotlin Projects  
**Compliance**: Mandatory for production code  
**Version**: 2.2.0  
**Last Updated**: January 2026
