---
name: Code Review Guidelines
description: Best practices for reviewing code effectively with severity-based rules.
compliance_level: MANDATORY
tags: [code-review, best-practices, pr, quality, ai-review]
version: 2.2.0
---

# Code Review Guidelines

## Context
Code reviews are the primary quality gate. They transfer knowledge, catch bugs early, and ensure consistency with project standards.

**Related Guides:**
- [20-anti-patterns.md](./20-anti-patterns.md) - Common mistakes
- [22-checklists.md](./22-checklists.md) - Quick checklists
- [11-testing.md](./11-testing.md) - Testing standards

---

## 🎯 AI Quick Reference

```
REVIEW COMMANDS:
@review [file]              - Review single file
@review-changes             - Review staged/unstaged changes
@review-commit [hash]       - Review specific commit
@review-pr [number]         - Review pull request
@review-report [output.md]  - Generate markdown report

REVIEW ORDER:
1. Critical (🔴) - Architecture, cancellation, state
2. Warning (🟡) - Code quality, patterns
3. Info (🔵) - Best practices, suggestions
4. Testing - Coverage, quality
5. Style (LAST) - Automate formatting
```

---

## 🚨 Severity-Based Rules

### 🔴 CRITICAL Rules (Must Fix - Breaks App)

These are **non-negotiable**. Code MUST NOT be merged if any of these are violated:

| # | Rule | Guide Reference | Auto-Fix |
|---|------|-----------------|----------|
| C1 | **CancellationException must be rethrown** | [03-coroutines](./03-coroutines-concurrency.md) | See fix below |
| C2 | **State: private mutable, public immutable** | [07-state-management](./07-state-management.md) | See fix below |
| C3 | **Events use Channel, NOT SharedFlow** | [07-state-management](./07-state-management.md) | See fix below |
| C4 | **@Immutable on all state classes** | [05-jetpack-compose](./05-jetpack-compose.md) | Add `@Immutable` |
| C5 | **ViewModel → UseCase → Repository** | [01-architecture](./01-architecture.md) | Refactor layers |

**Auto-Fix Code Snippets:**

```kotlin
// C1: CancellationException - ALWAYS rethrow
catch (e: CancellationException) { throw e }  // ← MANDATORY
catch (e: Exception) { /* handle */ }

// C2: State encapsulation
private val _state = MutableStateFlow(UiState())
val state: StateFlow<UiState> = _state.asStateFlow()

// C3: Events with Channel (NOT SharedFlow)
private val _events = Channel<Event>(Channel.BUFFERED)
val events = _events.receiveAsFlow()

// C4: Immutable state
@Immutable
data class UiState(val data: List<Item> = emptyList())
```

### 🟡 WARNING Rules (Should Fix - Code Quality)

| # | Rule | Guide Reference |
|---|------|-----------------|
| W1 | Domain layer has NO Android imports | [01-architecture](./01-architecture.md) |
| W2 | UseCase does ONE thing only (SRP) | [AGENT_SUMMARY](../AGENT_SUMMARY.md) |
| W3 | Use `collectAsStateWithLifecycle()` not `collectAsState()` | [05-jetpack-compose](./05-jetpack-compose.md) |
| W4 | Skills return `SkillResult<T>`, never throw | [30-skill-analysis](./30-skill-analysis.md) |
| W5 | Validation DSL used for all inputs | [24-validation-rules](./24-validation-rules.md) |
| W6 | `modifier` parameter should be last | [05-jetpack-compose](./05-jetpack-compose.md) |
| W7 | Use `val` over `var` | [02-coding-conventions](./02-coding-conventions.md) |
| W8 | Error mapping follows type pattern | [10-error-handling](./10-error-handling.md) |
| W9 | Open/Closed principle - extend via interface | [AGENT_SUMMARY](../AGENT_SUMMARY.md) |
| W10 | Interface Segregation - small focused interfaces | [AGENT_SUMMARY](../AGENT_SUMMARY.md) |
| W11 | Dependency Inversion - depend on abstractions | [AGENT_SUMMARY](../AGENT_SUMMARY.md) |
| W12 | Proper naming conventions (PascalCase, camelCase) | [02-coding-conventions](./02-coding-conventions.md) |

### 🔵 INFO Rules (Nice to Have - Best Practices)

| # | Rule | Guide Reference |
|---|------|-----------------|
| I1 | Route/Screen separation pattern | [05-jetpack-compose](./05-jetpack-compose.md) |
| I2 | Use `sealed interface` for states | [02-coding-conventions](./02-coding-conventions.md) |
| I3 | `data class` for models | [02-coding-conventions](./02-coding-conventions.md) |
| I4 | `checkCancellation()` in long operations | [03-coroutines](./03-coroutines-concurrency.md) |
| I5 | Type-safe navigation | [13-navigation](./13-navigation.md) |
| I6 | Inject dispatchers, never hardcode | [03-coroutines](./03-coroutines-concurrency.md) |
| I7 | Liskov Substitution - subtypes are replaceable | [AGENT_SUMMARY](../AGENT_SUMMARY.md) |
| I8 | Trailing commas in multiline constructs | [02-coding-conventions](./02-coding-conventions.md) |
| I9 | Consistent file/import organization | [02-coding-conventions](./02-coding-conventions.md) |
| I10 | Function names use verb + object pattern | [02-coding-conventions](./02-coding-conventions.md) |

---

## 📋 Full Review Checklist by Category

```
ARCHITECTURE (01-architecture.md):
[ ] 🔴 C5: ViewModel → UseCase → Repository (not ViewModel → Repository)?
[ ] 🟡 W1: Domain layer has NO Android imports?
[ ] 🟡 W2: UseCase does ONE thing (SRP)?
[ ] 🔵 I6: Dispatchers injected, not hardcoded?

SOLID PRINCIPLES (AGENT_SUMMARY.md):
[ ] 🟡 W2: Single Responsibility - 1 class = 1 reason to change?
[ ] 🟡 W9: Open/Closed - extend via interface, not modification?
[ ] 🔵 I7: Liskov Substitution - subtypes replaceable?
[ ] 🟡 W10: Interface Segregation - small focused interfaces?
[ ] 🟡 W11: Dependency Inversion - depend on abstractions?

NAMING CONVENTIONS (02-coding-conventions.md):
[ ] 🟡 W12: Classes use PascalCase (UserRepository)?
[ ] 🟡 W12: Functions use camelCase (getUserById)?
[ ] 🟡 W12: Constants use SCREAMING_SNAKE_CASE (MAX_RETRY_COUNT)?
[ ] 🔵 I10: Functions use verb + object pattern (getUserById, saveProfile)?
[ ] 🔵 I10: Booleans use is/has/can prefix (isValid, hasPermission)?
[ ] 🔵 I9: Proper file organization (package → imports → class)?

COROUTINES (03-coroutines-concurrency.md):
[ ] 🔴 C1: CancellationException always rethrown?
[ ] 🔵 I4: checkCancellation() in long loops?
[ ] 🔵 I6: Dispatchers injected?

STATE MANAGEMENT (07-state-management.md):
[ ] 🔴 C2: State is private mutable, public immutable?
[ ] 🔴 C3: Events use Channel (not SharedFlow)?
[ ] 🔴 C4: @Immutable on UiState?
[ ] 🟡 W3: collectAsStateWithLifecycle() used?

COMPOSE (05-jetpack-compose.md):
[ ] 🔴 C4: @Immutable on all state classes?
[ ] 🟡 W3: collectAsStateWithLifecycle() (not collectAsState())?
[ ] 🟡 W6: modifier is last parameter?
[ ] 🔵 I1: Route/Screen separation?
[ ] 🔵 I8: Trailing commas in multiline composables?

ERROR HANDLING (10-error-handling.md):
[ ] 🔴 C1: CancellationException rethrown?
[ ] 🟡 W4: Skills return SkillResult<T>?
[ ] 🟡 W8: Error mapping follows type pattern?

VALIDATION (24-validation-rules.md):
[ ] 🟡 W5: Validation DSL used?
[ ] 🟡 W5: All input fields validated?

NAVIGATION (13-navigation.md):
[ ] 🔵 I5: Type-safe navigation used?
[ ] 🔵 I5: NavArgs defined properly?

KOTLIN BEST PRACTICES (02-coding-conventions.md):
[ ] 🟡 W7: val over var?
[ ] 🔵 I2: sealed interface for states?
[ ] 🔵 I3: data class for models?
[ ] 🔵 I8: Trailing commas in multiline?
[ ] 🔵 I9: Imports organized (Android → Third-party → Project)?
```

---

## 📝 @review Output Format

When reviewing code, use this format:

```markdown
## 🔍 Code Review: [filename]

### 🔴 Critical Violations (MUST FIX)
1. **[C1] CancellationException not rethrown** - Line X
   - Issue: Catching CancellationException without rethrowing
   - Fix: Add `catch (e: CancellationException) { throw e }` before other catches
   - Ref: [03-coroutines](skills/guides/03-coroutines-concurrency.md)

2. **[C5] ViewModel accesses Repository directly** - Line Y
   - Issue: Bypassing UseCase layer
   - Fix: Create UseCase and inject it into ViewModel
   - Ref: [01-architecture](skills/guides/01-architecture.md)

### 🟡 Warnings (SHOULD FIX)
1. **[W3] Using collectAsState()** - Line 23
   - Issue: Not lifecycle-aware, can cause memory leaks
   - Fix: Replace with `collectAsStateWithLifecycle()`
   - Ref: [05-compose](skills/guides/05-jetpack-compose.md)

2. **[W9] Violates Open/Closed Principle** - Line 45
   - Issue: Adding new payment type requires modifying existing class
   - Current:
     ```kotlin
     class PaymentProcessor {
         fun process(type: String) {
             when (type) {
                 "credit" -> processCreditCard()
                 "paypal" -> processPaypal()
                 // Adding new type requires modifying this
             }
         }
     }
     ```
   - Fix: Use interface for extensibility
     ```kotlin
     interface PaymentMethod {
         fun process()
     }
     class CreditCardPayment : PaymentMethod
     class PaypalPayment : PaymentMethod
     ```
   - Ref: [AGENT_SUMMARY](skills/AGENT_SUMMARY.md)

3. **[W12] Improper naming convention** - Line 67
   - Issue: Function name doesn't follow camelCase
   - Current: `fun GetUser(): User`
   - Fix: `fun getUser(): User`
   - Ref: [02-coding-conventions](skills/guides/02-coding-conventions.md)

4. **[W10] Interface Segregation violation** - Line 89
   - Issue: Fat interface forces implementations to implement unused methods
   - Current:
     ```kotlin
     interface UserRepository {
         fun getUser()
         fun saveUser()
         fun deleteUser()
         fun exportToCSV()  // Not all impls need this
     }
     ```
   - Fix: Split into focused interfaces
     ```kotlin
     interface UserReader { fun getUser() }
     interface UserWriter { fun saveUser() }
     interface UserExporter { fun exportToCSV() }
     ```
   - Ref: [AGENT_SUMMARY](skills/AGENT_SUMMARY.md)

### 🔵 Suggestions (NICE TO HAVE)
1. **[I2] Consider sealed interface** - Line 15
   - Current: `sealed class State`
   - Better: `sealed interface State`
   - Benefit: More flexible, can have multiple base types
   - Ref: [02-coding-conventions](skills/guides/02-coding-conventions.md)

2. **[I10] Function naming could be more descriptive** - Line 102
   - Current: `fun handle(data: Data)`
   - Better: `fun processUserData(data: Data)` or `fun validateUserInput(data: Data)`
   - Ref: [02-coding-conventions](skills/guides/02-coding-conventions.md)

3. **[I8] Missing trailing commas** - Line 120
   - Current:
     ```kotlin
     Row(
         modifier = modifier,
         horizontalArrangement = Arrangement.Center
     )
     ```
   - Better:
     ```kotlin
     Row(
         modifier = modifier,
         horizontalArrangement = Arrangement.Center,  // ← trailing comma
     )
     ```
   - Ref: [02-coding-conventions](skills/guides/02-coding-conventions.md)

### ✅ Compliance (What's Good)
- ✅ @Immutable on UiState
- ✅ ViewModel uses UseCase
- ✅ Validation DSL used correctly
- ✅ Single Responsibility principle followed in UseCases
- ✅ Proper PascalCase for classes
- ✅ Dependencies injected via constructor
- ✅ Validation DSL used

### 📊 Score: 7/10
- 🔴 Critical: 1 issue
- 🟡 Warning: 2 issues  
- 🔵 Info: 1 suggestion

### 🔧 Quick Fixes
```kotlin
// Line X: Add CancellationException handler
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    // handle
}
```
```

---

## 📝 @review-changes Output Format

For reviewing git changes:

```markdown
## 🔄 Changes Review

### Files Changed
- [list files with change type: added/modified/deleted]

### 🔴 Critical Violations in Changes
| File | Line | Rule | Issue | Fix |
|------|------|------|-------|-----|
| ViewModel.kt | 45 | C1 | Missing CancellationException rethrow | Add `throw e` |

### 🟡 Warnings in Changes
| File | Line | Rule | Issue |
|------|------|------|-------|
| Screen.kt | 23 | W3 | Using collectAsState() | Use collectAsStateWithLifecycle() |

### 🔵 Suggestions
- Consider using sealed interface at Screen.kt:15

### ✅ Good Practices Found
- ✅ @Immutable annotation added
- ✅ Validation DSL used correctly

### 📊 Summary
- 🔴 Critical: X issues (MUST FIX before commit)
- 🟡 Warning: Y issues (SHOULD FIX)
- 🔵 Info: Z suggestions

### Recommendation
[ ] ✅ Ready to commit
[ ] ❌ Needs fixes (see 🔴 Critical above)
```

### Quick Reference for Common Changes

| Change Type | What to Check | Critical Rules |
|-------------|---------------|----------------|
| New ViewModel | State encapsulation, UseCase injection | C2, C3, C5 |
| New Composable | @Immutable, lifecycle collection | C4, W3, W6 |
| Try-Catch block | CancellationException handling | C1 |
| New Skill | SkillResult return, validation | W4, W5 |
| Repository call | Through UseCase, not direct | C5 |

---

## 📦 @review-commit - Review Specific Commit

Review a specific commit by hash:

```
@review-commit <commit-hash> [--output report.md]
```

### Workflow

1. **Get commit diff**: `git show <hash> --no-color`
2. **Analyze changed lines** using severity rules
3. **Group by file** and violation type
4. **Generate report** (console or markdown file)

### Output Format

```markdown
## 🔍 Commit Review: [hash] - [commit message]

**Author**: [author]  
**Date**: [date]  
**Files Changed**: X files

---

### 📁 Files in Commit
| File | Changes | Status |
|------|---------|--------|
| ViewModel.kt | +45, -12 | ⚠️ Has violations |
| Screen.kt | +23, -5 | ✅ Clean |

---

### 🔴 Critical Violations (MUST FIX)
**File: ViewModel.kt**
- **Line 45** - [C1] CancellationException not rethrown
  ```kotlin
  } catch (e: Exception) {  // ❌ Missing CancellationException check
  ```
  **Fix**: Add `catch (e: CancellationException) { throw e }` before this

---

### 🟡 Warnings (SHOULD FIX)
**File: Screen.kt**
- **Line 23** - [W3] Using collectAsState()
  ```kotlin
  val state by viewModel.state.collectAsState()  // ⚠️
  ```
  **Fix**: Use `collectAsStateWithLifecycle()`

---

### 📊 Commit Quality Score: 6/10
- 🔴 Critical: 1 violation
- 🟡 Warning: 2 violations
- 🔵 Info: 1 suggestion
- ✅ Good practices: 3

### Recommendation
❌ **Needs fixes** - Cannot merge due to critical violation (C1)

---

### 🔧 Auto-Fix Suggestions
```kotlin
// ViewModel.kt:45
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    // handle
}
```
```

---

## 📝 @review-report - Generate Markdown Report

Generate a detailed markdown report file:

```
@review-report [source] --output reviews/report-2026-01-26.md
```

### Sources

| Source | Command | Description |
|--------|---------|-------------|
| Single file | `@review-report src/ViewModel.kt` | Review one file |
| Changes | `@review-report --changes` | Review staged/unstaged |
| Commit | `@review-report --commit abc123` | Review specific commit |
| PR | `@review-report --pr 123` | Review pull request |
| Branch | `@review-report --branch feature/new` | Review all commits in branch |

### Report Structure

```markdown
# Code Review Report

**Generated**: 2026-01-26 14:30:00  
**Reviewer**: AI Agent (based on [21-code-review.md])  
**Source**: [file/commit/pr/branch]  
**Status**: ✅ PASSED | ⚠️ WARNINGS | ❌ FAILED

---

## Executive Summary

- **Total Files Reviewed**: X
- **Critical Issues**: 🔴 X (MUST FIX)
- **Warnings**: 🟡 X (SHOULD FIX)
- **Suggestions**: 🔵 X (NICE TO HAVE)
- **Overall Score**: X/10

### Recommendation
[ ] ✅ Ready to merge
[ ] ⚠️ Can merge with warnings
[ ] ❌ Must fix critical issues before merge

---

## Detailed Findings

### 🔴 Critical Violations

#### C1: CancellationException not rethrown
**Files affected**: 2
- `ViewModel.kt:45` - Missing rethrow in try-catch
- `Repository.kt:123` - Swallowing cancellation

**Impact**: HIGH - Can break coroutine cancellation flow  
**Reference**: [03-coroutines](./03-coroutines-concurrency.md)

---

### 🟡 Warnings

#### W3: Using collectAsState() instead of collectAsStateWithLifecycle()
**Files affected**: 3
- `Screen.kt:23`
- `ProfileScreen.kt:45`
- `SettingsScreen.kt:67`

**Impact**: MEDIUM - Memory leaks in background  
**Reference**: [05-jetpack-compose](./05-jetpack-compose.md)

---

### 🔵 Suggestions

#### I2: Consider sealed interface instead of sealed class
**Files affected**: 1
- `UiState.kt:15`

**Impact**: LOW - Better API design  
**Reference**: [02-coding-conventions](./02-coding-conventions.md)

---

## Files Reviewed

| File | Score | 🔴 | 🟡 | 🔵 | Status |
|------|-------|-----|-----|-----|--------|
| ViewModel.kt | 6/10 | 1 | 2 | 1 | ⚠️ |
| Screen.kt | 8/10 | 0 | 1 | 0 | ⚠️ |
| Repository.kt | 9/10 | 0 | 0 | 1 | ✅ |

---

## Auto-Fix Code Snippets

### ViewModel.kt:45
```kotlin
// Before
} catch (e: Exception) {
    handleError(e)
}

// After (✅ Fixed)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    handleError(e)
}
```

---

## Next Steps

1. ❌ **Fix critical issues (C1)** in ViewModel.kt, Repository.kt
2. ⚠️ **Address warnings (W3)** in Screen files
3. 🔵 **Consider suggestions** for better code quality
4. ✅ **Re-run review** after fixes

---

**Generated by**: AI Agent following [android-agent-skills](../ARCHITECTURE.md)  
**Report Version**: 2.2.0  
**Rules Reference**: [21-code-review.md](./21-code-review.md)
```

### Usage Examples

```bash
# Review current staged changes and save report
@review-report --changes --output reviews/pre-commit-$(date +%Y%m%d).md

# Review specific commit
@review-report --commit abc123 --output reviews/commit-abc123.md

# Review entire PR
@review-report --pr 456 --output reviews/pr-456-review.md

# Review branch before merge
@review-report --branch feature/user-profile --output reviews/feature-review.md
```

---

## 1. Review Priority Order

### What to Check First

| Priority | Area | Key Questions |
|----------|------|---------------|
| 1️⃣ | Architecture | Does it follow Clean Architecture? Proper layer separation? |
| 2️⃣ | Logic | Is the business logic correct? Edge cases handled? |
| 3️⃣ | Testing | Are tests present and meaningful? |
| 4️⃣ | Security | Input validated? Sensitive data protected? |
| 5️⃣ | Performance | Any memory leaks? Unnecessary recompositions? |
| 6️⃣ | Accessibility | Content descriptions? Touch targets? |
| 7️⃣ | Style | (Should be automated via lint) |

---

## 2. Comment Examples

### ✅ DO: Constructive Feedback

```markdown
**Architecture Question:**
> This repository is calling another repository directly. Should we consider
> creating a UseCase that coordinates between them? This would make the logic
> more testable and keep the repository focused on data access.
>
> Reference: [01-architecture.md](./01-architecture.md)

**Suggestion with Alternative:**
> Consider using `stateIn()` instead of manually managing the StateFlow:
> ```kotlin
> val state = repository.observeUser()
>     .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
> ```
> This handles the subscription lifecycle automatically.

**Non-Blocking (nit):**
> nit: This could be simplified using `let`:
> ```kotlin
> user?.let { saveUser(it) }
> ```
> Not blocking, just a suggestion.

**Security Concern:**
> ⚠️ This logs the auth token:
> ```kotlin
> Timber.d("Token: $token") // ❌ Security risk
> ```
> Please remove sensitive data from logs.
> Reference: [12-security.md](./12-security.md)

**Testing Request:**
> Could you add a test for the error case? Specifically:
> - Network timeout
> - Invalid response
>
> Happy to help if you need examples from the testing guide.
```

### ❌ DON'T: Destructive Feedback

```markdown
**❌ Demanding:**
> Change this. It's wrong.

**❌ Vague:**
> This doesn't look right.

**❌ Personal:**
> Why would you do it this way?

**❌ Overwhelming:**
> [50 comments on style and formatting that should be automated]
```

---

## 3. Review Checklist by Area

### Architecture Review
```markdown
- [ ] Follows Clean Architecture (Domain → Data → Presentation)
- [ ] No layer violations (UI doesn't access Data directly)
- [ ] Dependencies flow inward (Domain has no framework deps)
- [ ] Repository interface in Domain, implementation in Data
- [ ] UseCase for complex business logic
```

### ViewModel Review
```markdown
- [ ] Extends `ViewModel`
- [ ] Uses `viewModelScope` (not custom scope)
- [ ] `StateFlow` for state (not LiveData)
- [ ] `Channel` for one-time events
- [ ] `_state.update { }` for atomic updates
- [ ] No Context/View references
```

### Compose Review
```markdown
- [ ] Route (stateful) and Screen (stateless) separated
- [ ] State collected with `collectAsStateWithLifecycle()`
- [ ] Method references for callbacks
- [ ] `@Immutable` on state classes
- [ ] `ImmutableList` for collections
- [ ] Preview functions added
```

### Repository Review
```markdown
- [ ] Returns `Result<T>` or `Flow<T>`
- [ ] Exception handling (no uncaught exceptions)
- [ ] Proper dispatcher usage
- [ ] Database as SSOT (offline-first)
```

### Testing Review
```markdown
- [ ] Unit tests for ViewModel
- [ ] Unit tests for UseCase
- [ ] Repository tests with fakes
- [ ] Edge cases covered
- [ ] Test names are descriptive
```

---

## 4. PR Description Template

### ✅ Good PR Description
```markdown
## Summary
Add user profile editing feature with offline support.

## Changes
- `EditProfileScreen` - New Compose UI with form validation
- `EditProfileViewModel` - Handles edit state and saves
- `UpdateUserUseCase` - Business logic for profile updates
- `UserRepository.updateUser()` - Saves to local DB, syncs to server

## Architecture
```
┌─────────────────────┐
│  EditProfileScreen  │ ← UI Layer
└──────────┬──────────┘
           │
┌──────────▼──────────┐
│EditProfileViewModel │ ← Presentation
└──────────┬──────────┘
           │
┌──────────▼──────────┐
│  UpdateUserUseCase  │ ← Domain
└──────────┬──────────┘
           │
┌──────────▼──────────┐
│   UserRepository    │ ← Data
└─────────────────────┘
```

## Testing
- [x] ViewModel unit tests (85% coverage)
- [x] UseCase unit tests
- [x] Manual testing on device
- [ ] UI tests (TODO in follow-up)

## Screenshots
| Before | After |
|--------|-------|
| N/A    | ![Edit Profile](url) |

## Checklist
- [x] Self-reviewed
- [x] Tests pass
- [x] Lint passes
- [x] Documentation updated
```

---

## 5. When to Approve

### Approve Immediately
- All checks pass
- No architectural concerns
- Tests are adequate
- Minor style suggestions only (nit)

### Request Changes
- Architectural violations
- Missing tests for critical paths
- Security vulnerabilities
- Logic errors

### Approve with Comments
- Non-blocking suggestions
- Performance improvements that aren't critical
- Future refactoring ideas
- "Nice to have" improvements

---

## 6. Responding to Review

### As the Author

```markdown
**Accepting feedback:**
> Great point! Updated to use UseCase. Thanks for the reference to the guide.

**Disagreeing respectfully:**
> I considered that approach, but chose this because [reason]. Happy to discuss
> if you think the trade-offs favor the other approach.

**Asking for clarification:**
> Could you elaborate on what you mean by "should be in Domain"? Are you 
> referring to the validation logic or the data transformation?
```

---

## 7. Verification Checklist

### Before Submitting Review
- [ ] Read through all files first
- [ ] Architecture reviewed before details
- [ ] Constructive tone used
- [ ] References to guides included
- [ ] Clear approve/request changes decision

### As Author Before PR
- [ ] Self-reviewed all changes
- [ ] PR description complete
- [ ] Tests included and passing
- [ ] Screenshots if UI changes
