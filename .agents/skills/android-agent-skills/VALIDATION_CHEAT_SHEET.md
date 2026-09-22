# Validation Cheat Sheet

> **Purpose**: Quick reference for validation patterns in Agent Skills
> 
> **Use When**: Writing validation logic for skill inputs
> 
> **Reference**: [24-validation-rules.md](guides/24-validation-rules.md), [Validation.kt](templates/skill/base/Validation.kt)

---

## 🎯 Quick Validation DSL Reference

```kotlin
override suspend fun validate(input: MyInput): SkillResult<Unit> {
    return validate {
        // Add validations here
    }
}
```

---

## 📝 String Validations

| Validation | Code | Use Case |
|------------|------|----------|
| **Not Blank** | `requireNotBlank(input.name, "name")` | Required text fields |
| **Email** | `requireValidEmail(input.email, "email")` | Email addresses |
| **URL** | `requireValidUrl(input.website, "website")` | Web links, API endpoints |
| **Phone** | `requireValidPhone(input.phone, "phone")` | Phone numbers (10-15 digits) |
| **Regex Pattern** | `requireMatches(input.code, Regex("^[A-Z]{3}\\d{3}$"), "code")` | Custom formats (codes, IDs) |
| **Length Range** | `requireInRange(input.name.length, 2..50, "name length")` | Text length constraints |

### String Examples

```kotlin
// Basic string validation
requireNotBlank(input.username, "username")

// Email validation
requireValidEmail(input.email, "email")

// URL validation (requires http:// or https://)
requireValidUrl(input.profileUrl, "profileUrl")

// Phone validation (10-15 digits, optional +)
requireValidPhone(input.phone, "phone")

// Custom regex pattern (ZIP code)
requireMatches(input.zipCode, Regex("^\\d{5}(-\\d{4})?$"), "zipCode")

// Length constraints
requireInRange(input.bio.length, 0..500, "bio length")
```

---

## 🔢 Numeric Validations

| Validation | Code | Use Case |
|------------|------|----------|
| **Positive** | `requirePositive(input.count, "count")` | Counts, quantities |
| **Non-Negative** | `requireNonNegative(input.balance, "balance")` | Balances, scores |
| **Range** | `requireInRange(input.age, 13..150, "age")` | Age, percentages, ratings |
| **Confidence** | `requireValidConfidence(input.score, "score")` | ML confidence (0.0-1.0) |
| **Percentage** | `requireValidPercentage(input.discount, "discount")` | Percentages (0-100) |
| **Amount** | `requireValidAmount(input.price, "price")` | Money (2 decimals max) |
| **Age** | `requireValidAge(input.age, "age")` | Human age (13-150) |

### Numeric Examples

```kotlin
// Positive numbers only
requirePositive(input.quantity, "quantity")

// Zero or positive
requireNonNegative(input.accountBalance, "accountBalance")

// Integer range
requireInRange(input.age, 18..65, "age")
requireInRange(input.rating, 1..5, "rating")

// Floating point range
requireInRange(input.temperature, -40.0..50.0, "temperature")

// Confidence score (0.0 to 1.0)
requireValidConfidence(input.confidence, "confidence")

// Percentage (0 to 100)
requireValidPercentage(input.progress, "progress")

// Monetary amount (positive, max 2 decimals)
requireValidAmount(input.price, "price", minValue = 0.01, maxValue = 999999.99)

// Age with custom range
requireValidAge(input.userAge, "userAge", minAge = 18, maxAge = 120)
```

---

## 📦 Collection Validations

| Validation | Code | Use Case |
|------------|------|----------|
| **Not Empty** | `requireNotEmpty(input.items, "items")` | Required lists/sets |
| **Size Range** | `requireSize(input.tags, 1..10, "tags")` | Limited collection size |

### Collection Examples

```kotlin
// Collection must not be empty
requireNotEmpty(input.selectedIds, "selectedIds")

// Collection size constraints
requireSize(input.tags, 1..10, "tags")
requireSize(input.attachments, 0..5, "attachments")

// Validate each item in collection
input.emails.forEach { email ->
    requireValidEmail(email, "email")
}
```

---

## 📅 Date/Time Validations

| Validation | Code | Use Case |
|------------|------|----------|
| **Date Range** | `requireDateInRange(input.date, startDate..endDate, "date")` | Birth dates, event dates |

### Date Examples

```kotlin
// Date must be in range (using epoch millis)
val now = System.currentTimeMillis()
val minDate = now - (100 * 365 * 24 * 60 * 60 * 1000L) // 100 years ago
val maxDate = now

requireDateInRange(input.birthDate, minDate..maxDate, "birthDate")

// Future date only
val tomorrow = now + (24 * 60 * 60 * 1000L)
requireDateInRange(input.eventDate, tomorrow..Long.MAX_VALUE, "eventDate")
```

---

## 🔐 Password Validations

| Validation | Code | Use Case |
|------------|------|----------|
| **Password** | `requireValidPassword(input.password, "password")` | User passwords with strength |

### Password Examples

```kotlin
// Default: 8-64 chars, uppercase, lowercase, digit
requireValidPassword(input.password, "password")

// Custom requirements
requireValidPassword(
    value = input.password,
    field = "password",
    minLength = 12,
    maxLength = 128,
    requireUppercase = true,
    requireLowercase = true,
    requireDigit = true,
    requireSpecialChar = true
)

// Weak password (for testing only!)
requireValidPassword(
    value = input.pin,
    field = "pin",
    minLength = 4,
    maxLength = 6,
    requireUppercase = false,
    requireLowercase = false,
    requireDigit = true,
    requireSpecialChar = false
)
```

---

## 🎨 Custom Validations

| Validation | Code | Use Case |
|------------|------|----------|
| **Custom Condition** | `require(condition) { "Error message" }` | Business rules |
| **Not Null** | `requireNotNull(input.value, "value")` | Nullable fields that must have value |

### Custom Examples

```kotlin
// Custom business rule
require(input.startDate < input.endDate) {
    "startDate must be before endDate"
}

// Not null check
requireNotNull(input.optionalField, "optionalField")

// Complex validation
require(input.password == input.confirmPassword) {
    "Password and confirmation must match"
}

// Cross-field validation
if (input.hasCoupon) {
    requireNotBlank(input.couponCode, "couponCode")
}
```

---

## 🔄 Combining Validations

### Multiple Fields Example

```kotlin
override suspend fun validate(input: CreateUserInput): SkillResult<Unit> {
    return validate {
        // Personal info
        requireNotBlank(input.firstName, "firstName")
        requireInRange(input.firstName.length, 2..50, "firstName length")
        
        requireNotBlank(input.lastName, "lastName")
        requireInRange(input.lastName.length, 2..50, "lastName length")
        
        // Contact info
        requireValidEmail(input.email, "email")
        requireValidPhone(input.phone, "phone")
        
        // Account info
        requireValidPassword(
            value = input.password,
            field = "password",
            minLength = 8,
            requireUppercase = true,
            requireLowercase = true,
            requireDigit = true
        )
        
        // Profile info
        requireInRange(input.age, 18..120, "age")
        input.website?.let { requireValidUrl(it, "website") }
        
        // Terms & conditions
        require(input.agreedToTerms) { "Must agree to terms and conditions" }
    }
}
```

### Optional Fields Example

```kotlin
override suspend fun validate(input: UpdateProfileInput): SkillResult<Unit> {
    return validate {
        // Required fields
        requireNotBlank(input.userId, "userId")
        
        // Optional fields - validate only if present
        input.name?.let { name ->
            requireNotBlank(name, "name")
            requireInRange(name.length, 2..100, "name length")
        }
        
        input.email?.let { email ->
            requireValidEmail(email, "email")
        }
        
        input.age?.let { age ->
            requireInRange(age, 13..150, "age")
        }
        
        input.website?.let { website ->
            requireValidUrl(website, "website")
        }
    }
}
```

---

## ⚡ Common Patterns

### Search/Query Pattern

```kotlin
override suspend fun validate(input: SearchInput): SkillResult<Unit> {
    return validate {
        // Query
        requireNotBlank(input.query, "query")
        requireInRange(input.query.length, 1..200, "query length")
        
        // Pagination
        requireInRange(input.page, 1..Int.MAX_VALUE, "page")
        requireInRange(input.pageSize, 1..100, "pageSize")
        
        // Filters (optional)
        input.minPrice?.let { requirePositive(it, "minPrice") }
        input.maxPrice?.let { requirePositive(it, "maxPrice") }
        
        // Price range validation
        if (input.minPrice != null && input.maxPrice != null) {
            require(input.minPrice <= input.maxPrice) {
                "minPrice must be less than or equal to maxPrice"
            }
        }
    }
}
```

### User Registration Pattern

```kotlin
override suspend fun validate(input: RegisterUserInput): SkillResult<Unit> {
    return validate {
        // Username
        requireNotBlank(input.username, "username")
        requireInRange(input.username.length, 3..30, "username length")
        requireMatches(
            input.username,
            Regex("^[a-zA-Z0-9_]+$"),
            "username"
        )
        
        // Email
        requireValidEmail(input.email, "email")
        
        // Password
        requireValidPassword(
            value = input.password,
            field = "password",
            minLength = 8,
            maxLength = 64,
            requireUppercase = true,
            requireLowercase = true,
            requireDigit = true,
            requireSpecialChar = false
        )
        
        // Age
        requireValidAge(input.age, "age", minAge = 13)
        
        // Terms
        require(input.acceptedTerms) {
            "You must accept the terms and conditions"
        }
    }
}
```

### Payment/Transaction Pattern

```kotlin
override suspend fun validate(input: ProcessPaymentInput): SkillResult<Unit> {
    return validate {
        // Amount
        requireValidAmount(
            input.amount,
            "amount",
            minValue = 0.01,
            maxValue = 999999.99
        )
        
        // Card number (basic check)
        requireNotBlank(input.cardNumber, "cardNumber")
        requireMatches(
            input.cardNumber.replace(" ", ""),
            Regex("^\\d{13,19}$"),
            "cardNumber"
        )
        
        // CVV
        requireMatches(input.cvv, Regex("^\\d{3,4}$"), "cvv")
        
        // Expiry date (MM/YY format)
        requireMatches(
            input.expiryDate,
            Regex("^(0[1-9]|1[0-2])/\\d{2}$"),
            "expiryDate"
        )
    }
}
```

---

## 🚨 Validation Anti-Patterns

### ❌ DON'T: Skip Validation

```kotlin
// ❌ BAD - No validation
override suspend fun validate(input: MyInput): SkillResult<Unit> {
    return SkillResult.success(Unit)
}
```

### ❌ DON'T: Manual Checks Without DSL

```kotlin
// ❌ BAD - Manual validation
override suspend fun validate(input: MyInput): SkillResult<Unit> {
    if (input.email.isBlank()) {
        return SkillResult.failure(SkillError.Validation("Email is blank"))
    }
    if (!input.email.contains("@")) {
        return SkillResult.failure(SkillError.Validation("Invalid email"))
    }
    return SkillResult.success(Unit)
}

// ✅ GOOD - Use DSL
override suspend fun validate(input: MyInput): SkillResult<Unit> {
    return validate {
        requireValidEmail(input.email, "email")
    }
}
```

### ❌ DON'T: Incomplete Validation

```kotlin
// ❌ BAD - Only validates some fields
override suspend fun validate(input: CreateUserInput): SkillResult<Unit> {
    return validate {
        requireNotBlank(input.name, "name")
        // Missing: email, age, password, etc.
    }
}
```

---

## 📊 Validation Coverage Goals

| Coverage | Status | Action |
|----------|--------|--------|
| 100% | ✅ Excellent | Deploy with confidence |
| 80-99% | ⚠️ Good | Review missing fields |
| 50-79% | ❌ Poor | Add more validations |
| < 50% | 🚫 Critical | Rewrite validation |

**Use [@analyze-skill](AI_PROMPTS.md#analyze-skill) to check coverage!**

---

## 🔗 Related Resources

- **Full Guide**: [24-validation-rules.md](guides/24-validation-rules.md)
- **DSL Source**: [Validation.kt](templates/skill/base/Validation.kt)
- **Examples**: [DomainSkillGuide.kt](templates/examples/DomainSkillGuide.kt)
- **Analysis**: [30-skill-analysis.md](guides/30-skill-analysis.md)
- **AI Prompt**: [@add-validation](AI_PROMPTS.md#add-validation)

---

**Quick Tip**: Aim for 100% validation coverage on all inputs. Use `@analyze-skill` to verify!

**Last Updated**: 2026-01-24  
**Version**: 2.2.0
