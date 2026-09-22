---
name: Validation Rules & Business Logic
description: Comprehensive validation rules matrix and business logic patterns
compliance_level: MANDATORY
tags: [validation, input-validation, business-rules, security]
version: 2.2.0
last_updated: 2026-01-24
---

# Validation Rules & Business Logic

## Context
Proper input validation is critical for security, data integrity, and user experience. This guide provides a comprehensive matrix of validation rules for common scenarios and business logic patterns.

## Input Validation Matrix

### String Fields

| Field Type | Validation Rules | Function | Example |
|------------|-----------------|----------|---------|
| **Email** | Not blank + RFC 5322 format | `requireValidEmail(email, "email")` | `user@example.com` |
| **Phone** | 10-15 digits + optional `+` prefix | `requireValidPhone(phone, "phone")` | `+1234567890` |
| **URL** | HTTP/HTTPS + valid format | `requireValidUrl(url, "url")` | `https://example.com` |
| **Name** | 2-50 chars, letters/spaces only | `requireMatches(name, nameRegex, "name")` | `John Doe` |
| **Username** | 3-20 chars, alphanumeric + `_-.` | `requireMatches(username, usernameRegex, "username")` | `user_name` |
| **Password** | 8-64 chars, mixed case + digit | `requireValidPassword(password, "password")` | See below |
| **Zipcode** | 5 digits or 5+4 format | `requireMatches(zip, zipRegex, "zipcode")` | `12345` or `12345-6789` |

### Numeric Fields

| Field Type | Validation Rules | Function | Example |
|------------|-----------------|----------|---------|
| **Age** | 13-150 years | `requireValidAge(age, "age", 13, 150)` | `25` |
| **Percentage** | 0-100 | `requireValidPercentage(value, "percentage")` | `75.5` |
| **Confidence** | 0.0-1.0 | `requireValidConfidence(confidence, "confidence")` | `0.95` |
| **Amount** | Positive + max 2 decimals | `requireValidAmount(amount, "amount")` | `99.99` |
| **Quantity** | Positive integer + max limit | `requireInRange(qty, 1..maxStock, "quantity")` | `5` |
| **Rating** | 1-5 | `requireInRange(rating, 1..5, "rating")` | `4` |

### Date/Time Fields

| Field Type | Validation Rules | Function | Example |
|------------|-----------------|----------|---------|
| **Birthdate** | Not null + past + age >= 13 | `requireDateInRange(birthdate, minDate..today, "birthdate")` | `1990-01-01` |
| **Event Date** | Not null + future | `requireDateInRange(eventDate, today..maxFuture, "eventDate")` | `2026-12-31` |
| **Timestamp** | Not null + reasonable range | `requireDateInRange(timestamp, minTime..maxTime, "timestamp")` | `1706076000000` |

### Collection Fields

| Field Type | Validation Rules | Function | Example |
|------------|-----------------|----------|---------|
| **Tags** | Not empty + size 1-10 | `requireSize(tags, 1..10, "tags")` | `["kotlin", "android"]` |
| **Options** | Not empty + valid choices | `require(options.all { it in validOptions })` | `["option1", "option2"]` |
| **Items** | Not empty + max 100 | `requireSize(items, 1..100, "items")` | `[item1, item2]` |

## Common Business Rules

### User Registration

```kotlin
override suspend fun validate(input: RegisterInput): SkillResult<Unit> {
    return validate {
        // Email validation
        requireNotBlank(input.email, "email")
        requireValidEmail(input.email, "email")
        
        // Password validation
        requireNotBlank(input.password, "password")
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
        
        // Confirm password
        require(input.password == input.confirmPassword, "confirmPassword") {
            "Passwords do not match"
        }
        
        // Age validation (COPPA compliance)
        requireNotNull(input.birthdate, "birthdate")
        requireValidAge(
            value = calculateAge(input.birthdate),
            field = "age",
            minAge = 13,
            maxAge = 150
        )
        
        // Terms acceptance
        require(input.acceptedTerms, "acceptedTerms") {
            "You must accept the terms and conditions"
        }
    }
}
```

### E-commerce Order

```kotlin
override suspend fun validate(input: CreateOrderInput): SkillResult<Unit> {
    return validate {
        // Order items
        requireNotEmpty(input.items, "items")
        requireSize(input.items, 1..100, "items")
        
        // Each item validation
        input.items.forEach { item ->
            requirePositive(item.quantity, "item.quantity")
            requireInRange(item.quantity, 1..item.maxStock, "item.quantity")
            requireValidAmount(item.price, "item.price", minValue = 0.01)
        }
        
        // Shipping address
        requireNotBlank(input.shippingAddress.street, "shippingAddress.street")
        requireNotBlank(input.shippingAddress.city, "shippingAddress.city")
        requireNotBlank(input.shippingAddress.zipcode, "shippingAddress.zipcode")
        requireMatches(
            input.shippingAddress.zipcode,
            Regex("^\\d{5}(-\\d{4})?$"),
            "shippingAddress.zipcode",
            "Invalid zipcode format"
        )
        
        // Payment validation
        requireValidAmount(input.totalAmount, "totalAmount", minValue = 0.01, maxValue = 100000.0)
        
        // Discount code (optional)
        input.discountCode?.let { code ->
            requireMatches(code, Regex("^[A-Z0-9]{6,10}$"), "discountCode")
        }
    }
}
```

### Content Creation

```kotlin
override suspend fun validate(input: CreatePostInput): SkillResult<Unit> {
    return validate {
        // Title validation
        requireNotBlank(input.title, "title")
        requireInRange(input.title.length, 5..200, "title.length")
        
        // Content validation
        requireNotBlank(input.content, "content")
        requireInRange(input.content.length, 10..10000, "content.length")
        
        // Tags validation
        requireNotEmpty(input.tags, "tags")
        requireSize(input.tags, 1..10, "tags")
        input.tags.forEach { tag ->
            requireMatches(tag, Regex("^[a-z0-9-]{2,30}$"), "tag")
        }
        
        // Category validation
        require(input.category in validCategories, "category") {
            "Invalid category. Must be one of: ${validCategories.joinToString()}"
        }
        
        // Media URLs (optional)
        input.mediaUrls.forEach { url ->
            requireValidUrl(url, "mediaUrl")
        }
    }
}
```

### User Profile Update

```kotlin
override suspend fun validate(input: UpdateProfileInput): SkillResult<Unit> {
    return validate {
        // Display name (optional)
        input.displayName?.let { name ->
            requireInRange(name.length, 2..50, "displayName.length")
            requireMatches(name, Regex("^[A-Za-z\\s]+$"), "displayName")
        }
        
        // Bio (optional)
        input.bio?.let { bio ->
            requireInRange(bio.length, 0..500, "bio.length")
        }
        
        // Website (optional)
        input.website?.let { website ->
            requireValidUrl(website, "website")
        }
        
        // Phone (optional)
        input.phone?.let { phone ->
            requireValidPhone(phone, "phone")
        }
        
        // Location (optional)
        input.location?.let { location ->
            requireInRange(location.length, 2..100, "location.length")
        }
    }
}
```

### API Request Validation

```kotlin
override suspend fun validate(input: SearchInput): SkillResult<Unit> {
    return validate {
        // Query validation
        requireNotBlank(input.query, "query")
        requireInRange(input.query.length, 2..100, "query.length")
        
        // Pagination validation
        requirePositive(input.page, "page")
        requireInRange(input.page, 1..1000, "page")
        
        requirePositive(input.limit, "limit")
        requireInRange(input.limit, 1..100, "limit")
        
        // Sort field validation
        require(input.sortBy in validSortFields, "sortBy") {
            "Invalid sort field. Must be one of: ${validSortFields.joinToString()}"
        }
        
        // Sort order validation
        require(input.sortOrder in listOf("asc", "desc"), "sortOrder") {
            "Sort order must be 'asc' or 'desc'"
        }
        
        // Filters validation
        input.filters.forEach { (key, value) ->
            require(key in validFilterKeys, "filter.$key") {
                "Invalid filter key: $key"
            }
            requireNotBlank(value, "filter.$key")
        }
    }
}
```

## Custom Validation Functions

### Complex Password Validation

```kotlin
fun ValidationBuilder.requireStrongPassword(
    value: String,
    field: String = "password"
) {
    requireValidPassword(
        value = value,
        field = field,
        minLength = 12,
        maxLength = 128,
        requireUppercase = true,
        requireLowercase = true,
        requireDigit = true,
        requireSpecialChar = true
    )
    
    // Additional checks
    val commonPasswords = setOf("password123", "qwerty123", "admin123")
    require(!commonPasswords.contains(value.lowercase()), field) {
        "Password is too common. Please choose a stronger password"
    }
    
    // Check for sequential characters
    require(!hasSequentialCharacters(value), field) {
        "Password contains sequential characters"
    }
}

private fun hasSequentialCharacters(password: String): Boolean {
    for (i in 0 until password.length - 2) {
        val char1 = password[i].code
        val char2 = password[i + 1].code
        val char3 = password[i + 2].code
        if (char2 == char1 + 1 && char3 == char2 + 1) return true
    }
    return false
}
```

### Credit Card Validation (Luhn Algorithm)

```kotlin
fun ValidationBuilder.requireValidCreditCard(
    value: String,
    field: String = "creditCard"
) {
    val digits = value.filter { it.isDigit() }
    
    requireInRange(digits.length, 13..19, "${field}.length")
    
    // Luhn algorithm
    var sum = 0
    var alternate = false
    for (i in digits.length - 1 downTo 0) {
        var digit = digits[i].toString().toInt()
        if (alternate) {
            digit *= 2
            if (digit > 9) digit -= 9
        }
        sum += digit
        alternate = !alternate
    }
    
    require(sum % 10 == 0, field) {
        "Invalid credit card number"
    }
}
```

### File Upload Validation

```kotlin
fun ValidationBuilder.requireValidFile(
    filename: String,
    fileSizeBytes: Long,
    field: String = "file"
) {
    requireNotBlank(filename, "${field}.filename")
    
    // File extension validation
    val allowedExtensions = setOf("jpg", "jpeg", "png", "pdf", "docx")
    val extension = filename.substringAfterLast('.', "").lowercase()
    require(extension in allowedExtensions, "${field}.extension") {
        "File type not allowed. Allowed types: ${allowedExtensions.joinToString()}"
    }
    
    // File size validation (max 10MB)
    val maxSizeBytes = 10 * 1024 * 1024L
    requireInRange(fileSizeBytes, 1L..maxSizeBytes, "${field}.size") {
        "File size must be between 1 byte and ${maxSizeBytes / 1024 / 1024}MB"
    }
}
```

## Security Considerations

### Input Sanitization

```kotlin
fun ValidationBuilder.requireSafeInput(
    value: String,
    field: String
) {
    // Check for SQL injection patterns
    val sqlInjectionPatterns = listOf(
        Regex(".*[';\"\\-\\-].*"),
        Regex(".*\\bOR\\b.*=.*", RegexOption.IGNORE_CASE),
        Regex(".*\\bUNION\\b.*", RegexOption.IGNORE_CASE),
        Regex(".*\\bDROP\\b.*", RegexOption.IGNORE_CASE),
        Regex(".*\\bDELETE\\b.*", RegexOption.IGNORE_CASE),
    )
    
    sqlInjectionPatterns.forEach { pattern ->
        require(!pattern.matches(value), field) {
            "Input contains potentially harmful patterns"
        }
    }
    
    // Check for XSS patterns
    val xssPatterns = listOf(
        Regex(".*<script.*>.*", RegexOption.IGNORE_CASE),
        Regex(".*javascript:.*", RegexOption.IGNORE_CASE),
        Regex(".*onerror=.*", RegexOption.IGNORE_CASE),
    )
    
    xssPatterns.forEach { pattern ->
        require(!pattern.matches(value), field) {
            "Input contains potentially harmful patterns"
        }
    }
}
```

### Rate Limiting Validation

```kotlin
data class RateLimitInput(
    val userId: String,
    val action: String,
    val timestamp: Long
)

override suspend fun validate(input: RateLimitInput): SkillResult<Unit> {
    return validate {
        requireNotBlank(input.userId, "userId")
        requireNotBlank(input.action, "action")
        
        // Check timestamp is recent (within last minute)
        val now = System.currentTimeMillis()
        val oneMinuteAgo = now - 60_000
        requireInRange(input.timestamp, oneMinuteAgo..now, "timestamp") {
            "Timestamp is too old or in the future"
        }
    }
}
```

## Testing Validation Rules

```kotlin
class ValidationTest {
    
    @Test
    fun `valid email passes validation`() {
        val input = RegisterInput(
            email = "user@example.com",
            password = "SecurePass123",
            confirmPassword = "SecurePass123",
            birthdate = LocalDate.of(1990, 1, 1),
            acceptedTerms = true
        )
        
        val result = skill.validate(input)
        
        assertTrue(result.isSuccess)
    }
    
    @Test
    fun `invalid email fails validation`() {
        val input = RegisterInput(
            email = "invalid-email",
            password = "SecurePass123",
            confirmPassword = "SecurePass123",
            birthdate = LocalDate.of(1990, 1, 1),
            acceptedTerms = true
        )
        
        val result = skill.validate(input)
        
        assertTrue(result.isFailure)
        assertTrue(result.error!!.message.contains("email"))
    }
    
    @Test
    fun `weak password fails validation`() {
        val input = RegisterInput(
            email = "user@example.com",
            password = "weak",
            confirmPassword = "weak",
            birthdate = LocalDate.of(1990, 1, 1),
            acceptedTerms = true
        )
        
        val result = skill.validate(input)
        
        assertTrue(result.isFailure)
        assertTrue(result.error!!.message.contains("password"))
    }
}
```

## Verification Checklist

- [ ] All user inputs are validated before processing
- [ ] Email addresses use `requireValidEmail()`
- [ ] Passwords use `requireValidPassword()` with appropriate strength
- [ ] Numeric ranges use `requireInRange()` with business-appropriate limits
- [ ] Collections use `requireNotEmpty()` and `requireSize()`
- [ ] Optional fields are validated only when present (using `?.let {}`)
- [ ] Error messages are clear and actionable
- [ ] Validation rules are tested with both valid and invalid inputs
- [ ] Security patterns (SQL injection, XSS) are checked for user-generated content
- [ ] File uploads validate extension and size
- [ ] API pagination parameters have reasonable limits

## Performance Considerations

1. **Fail Fast**: Place most likely-to-fail validations first
2. **Regex Compilation**: Compile regex patterns once, reuse them
3. **Avoid Database Calls**: Validation should be fast and synchronous
4. **Cache Valid Values**: Cache valid categories, options, etc.

## Related Guides

- [Error Handling](10-error-handling.md) - How to handle validation errors
- [Security](12-security.md) - Security best practices
- [Testing](11-testing.md) - Testing validation logic

## References

- RFC 5322 (Email format)
- COPPA (Children's Online Privacy Protection Act)
- OWASP Input Validation Cheat Sheet
- PCI DSS (Payment Card Industry Data Security Standard)
