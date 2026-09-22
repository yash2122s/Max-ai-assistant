package com.agentcore.skill.base

/**
 * Validation DSL utilities for skills.
 * 
 * Provides a clean, declarative way to validate skill inputs
 * following the guide's patterns.
 */

/**
 * Validation result that can accumulate multiple errors.
 */
data class ValidationResult(
    val errors: List<ValidationError> = emptyList(),
) {
    val isValid: Boolean get() = errors.isEmpty()
    val isInvalid: Boolean get() = errors.isNotEmpty()
    
    fun toSkillResult(): SkillResult<Unit> {
        return if (isValid) {
            SkillResult.success(Unit)
        } else {
            SkillResult.failure(
                error = SkillError(
                    code = ErrorCode.VALIDATION,
                    message = errors.joinToString("; ") { it.message },
                    details = mapOf("validationErrors" to errors.map { it.toMap() }),
                )
            )
        }
    }
    
    operator fun plus(other: ValidationResult): ValidationResult {
        return ValidationResult(errors + other.errors)
    }
}

/**
 * Represents a single validation error.
 */
data class ValidationError(
    val field: String,
    val message: String,
    val code: String = ErrorCode.VALIDATION,
) {
    fun toMap(): Map<String, String> = mapOf(
        "field" to field,
        "message" to message,
        "code" to code,
    )
}

/**
 * Builder for accumulating validation errors.
 */
class ValidationBuilder {
    private val errors = mutableListOf<ValidationError>()
    
    /**
     * Validates a condition, adding an error if it fails.
     */
    fun require(
        condition: Boolean,
        field: String = "",
        message: () -> String,
    ) {
        if (!condition) {
            errors.add(ValidationError(field, message()))
        }
    }
    
    /**
     * Validates that a string is not blank.
     */
    fun requireNotBlank(
        value: String,
        field: String,
        message: String = "$field cannot be blank",
    ) {
        require(value.isNotBlank(), field) { message }
    }
    
    /**
     * Validates that a value is not null.
     */
    fun <T> requireNotNull(
        value: T?,
        field: String,
        message: String = "$field cannot be null",
    ) {
        require(value != null, field) { message }
    }
    
    /**
     * Validates that a number is positive.
     */
    fun requirePositive(
        value: Number,
        field: String,
        message: String = "$field must be positive",
    ) {
        require(value.toDouble() > 0, field) { message }
    }
    
    /**
     * Validates that a number is non-negative.
     */
    fun requireNonNegative(
        value: Number,
        field: String,
        message: String = "$field must be non-negative",
    ) {
        require(value.toDouble() >= 0, field) { message }
    }
    
    /**
     * Validates that a value is within a range.
     */
    fun <T : Comparable<T>> requireInRange(
        value: T,
        range: ClosedRange<T>,
        field: String,
        message: String = "$field must be in range $range",
    ) {
        require(value in range, field) { message }
    }
    
    /**
     * Validates that a collection is not empty.
     */
    fun <T> requireNotEmpty(
        collection: Collection<T>,
        field: String,
        message: String = "$field cannot be empty",
    ) {
        require(collection.isNotEmpty(), field) { message }
    }
    
    /**
     * Validates that a collection size is within bounds.
     */
    fun <T> requireSize(
        collection: Collection<T>,
        range: IntRange,
        field: String,
        message: String = "$field size must be in range $range",
    ) {
        require(collection.size in range, field) { message }
    }
    
    /**
     * Validates using a regex pattern.
     */
    fun requireMatches(
        value: String,
        pattern: Regex,
        field: String,
        message: String = "$field does not match required pattern",
    ) {
        require(pattern.matches(value), field) { message }
    }
    
    /**
     * Validates an email format.
     */
    fun requireValidEmail(
        value: String,
        field: String = "email",
        message: String = "Invalid email format",
    ) {
        val emailPattern = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
        require(emailPattern.matches(value), field) { message }
    }
    
    /**
     * Validates a phone number format (basic validation).
     */
    fun requireValidPhone(
        value: String,
        field: String = "phone",
        message: String = "Invalid phone number format",
    ) {
        // Basic phone validation: 10-15 digits, optional + prefix
        val phonePattern = Regex("^\\+?[0-9]{10,15}$")
        require(phonePattern.matches(value.replace("[\\s()-]".toRegex(), "")), field) { message }
    }
    
    /**
     * Validates a URL format.
     */
    fun requireValidUrl(
        value: String,
        field: String = "url",
        message: String = "Invalid URL format",
    ) {
        val urlPattern = Regex("^https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=]+$")
        require(urlPattern.matches(value), field) { message }
    }
    
    // ============================================================
    // DOMAIN-SPECIFIC VALIDATORS
    // ============================================================
    
    /**
     * Validates a username format.
     * Default: 3-30 chars, alphanumeric with underscores.
     */
    fun requireValidUsername(
        value: String,
        field: String = "username",
        minLength: Int = 3,
        maxLength: Int = 30,
        allowSpecialChars: Boolean = false,
        message: String? = null,
    ) {
        val pattern = if (allowSpecialChars) {
            Regex("^[a-zA-Z0-9_.-]{$minLength,$maxLength}$")
        } else {
            Regex("^[a-zA-Z0-9_]{$minLength,$maxLength}$")
        }
        val defaultMessage = "$field must be $minLength-$maxLength characters, alphanumeric with underscores"
        require(pattern.matches(value), field) { message ?: defaultMessage }
    }
    
    /**
     * Validates a currency code (ISO 4217).
     */
    fun requireValidCurrency(
        value: String,
        field: String = "currency",
        message: String = "Invalid currency code (expected ISO 4217 format like USD, EUR)",
    ) {
        val currencyPattern = Regex("^[A-Z]{3}$")
        require(currencyPattern.matches(value.uppercase()), field) { message }
    }
    
    /**
     * Validates a country code (ISO 3166-1 alpha-2).
     */
    fun requireValidCountryCode(
        value: String,
        field: String = "countryCode",
        message: String = "Invalid country code (expected ISO 3166-1 alpha-2 like US, VN)",
    ) {
        val countryPattern = Regex("^[A-Z]{2}$")
        require(countryPattern.matches(value.uppercase()), field) { message }
    }
    
    /**
     * Validates a language code (ISO 639-1).
     */
    fun requireValidLanguageCode(
        value: String,
        field: String = "languageCode",
        message: String = "Invalid language code (expected ISO 639-1 like en, vi)",
    ) {
        val languagePattern = Regex("^[a-z]{2}(-[A-Z]{2})?$")
        require(languagePattern.matches(value), field) { message }
    }
    
    /**
     * Validates a locale string (e.g., en_US, vi_VN).
     */
    fun requireValidLocale(
        value: String,
        field: String = "locale",
        message: String = "Invalid locale format (expected format like en_US, vi_VN)",
    ) {
        val localePattern = Regex("^[a-z]{2}[_-][A-Z]{2}$")
        require(localePattern.matches(value), field) { message }
    }
    
    /**
     * Validates a timezone identifier (IANA format).
     */
    fun requireValidTimezone(
        value: String,
        field: String = "timezone",
        message: String = "Invalid timezone (expected IANA format like Asia/Ho_Chi_Minh, America/New_York)",
    ) {
        val timezonePattern = Regex("^[A-Za-z]+(/[A-Za-z_]+)+$|^UTC([+-]\\d{1,2})?$")
        require(timezonePattern.matches(value), field) { message }
    }
    
    /**
     * Validates a credit card number (basic Luhn check).
     */
    fun requireValidCreditCard(
        value: String,
        field: String = "creditCard",
        message: String = "Invalid credit card number",
    ) {
        val digits = value.filter { it.isDigit() }
        require(digits.length in 13..19, field) { message }
        require(luhnCheck(digits), field) { message }
    }
    
    /**
     * Validates a hashtag format.
     */
    fun requireValidHashtag(
        value: String,
        field: String = "hashtag",
        message: String = "Invalid hashtag format (must start with # and contain only alphanumeric characters)",
    ) {
        val hashtagPattern = Regex("^#[a-zA-Z0-9_]+$")
        require(hashtagPattern.matches(value), field) { message }
    }
    
    /**
     * Validates a UUID format.
     */
    fun requireValidUuid(
        value: String,
        field: String = "uuid",
        message: String = "Invalid UUID format",
    ) {
        val uuidPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        require(uuidPattern.matches(value), field) { message }
    }
    
    /**
     * Validates an IP address (IPv4).
     */
    fun requireValidIpv4(
        value: String,
        field: String = "ip",
        message: String = "Invalid IPv4 address format",
    ) {
        val ipv4Pattern = Regex("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$")
        require(ipv4Pattern.matches(value), field) { message }
    }
    
    /**
     * Validates a hex color code.
     */
    fun requireValidHexColor(
        value: String,
        field: String = "color",
        message: String = "Invalid hex color format (expected #RRGGBB or #RGB)",
    ) {
        val hexColorPattern = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")
        require(hexColorPattern.matches(value), field) { message }
    }
    
    /**
     * Validates a semantic version string (e.g., 1.0.0, 2.1.3-beta).
     */
    fun requireValidSemVer(
        value: String,
        field: String = "version",
        message: String = "Invalid semantic version format (expected X.Y.Z or X.Y.Z-label)",
    ) {
        val semverPattern = Regex("^\\d+\\.\\d+\\.\\d+(-[a-zA-Z0-9.]+)?$")
        require(semverPattern.matches(value), field) { message }
    }
    
    // ============================================================
    // E-COMMERCE VALIDATORS
    // ============================================================
    
    /**
     * Validates a SKU (Stock Keeping Unit) format.
     */
    fun requireValidSku(
        value: String,
        field: String = "sku",
        minLength: Int = 3,
        maxLength: Int = 50,
        message: String = "Invalid SKU format",
    ) {
        val skuPattern = Regex("^[A-Za-z0-9_-]{$minLength,$maxLength}$")
        require(skuPattern.matches(value), field) { message }
    }
    
    /**
     * Validates a barcode (EAN-13, UPC-A, etc.).
     */
    fun requireValidBarcode(
        value: String,
        field: String = "barcode",
        message: String = "Invalid barcode format",
    ) {
        val digits = value.filter { it.isDigit() }
        // EAN-13, EAN-8, UPC-A, UPC-E
        require(digits.length in listOf(8, 12, 13, 14), field) { message }
    }
    
    /**
     * Validates a tracking number format (generic).
     */
    fun requireValidTrackingNumber(
        value: String,
        field: String = "trackingNumber",
        minLength: Int = 10,
        maxLength: Int = 40,
        message: String = "Invalid tracking number format",
    ) {
        val trackingPattern = Regex("^[A-Za-z0-9]{$minLength,$maxLength}$")
        require(trackingPattern.matches(value), field) { message }
    }
    
    // ============================================================
    // COMPOUND/CONDITIONAL VALIDATORS
    // ============================================================
    
    /**
     * Validates that exactly one of the provided values is non-null.
     */
    fun <T> requireExactlyOne(
        vararg values: T?,
        fields: List<String>,
        message: String? = null,
    ) {
        val nonNullCount = values.count { it != null }
        val defaultMessage = "Exactly one of ${fields.joinToString(", ")} must be provided"
        require(nonNullCount == 1, fields.firstOrNull() ?: "") { message ?: defaultMessage }
    }
    
    /**
     * Validates that at least one of the provided values is non-null.
     */
    fun <T> requireAtLeastOne(
        vararg values: T?,
        fields: List<String>,
        message: String? = null,
    ) {
        val nonNullCount = values.count { it != null }
        val defaultMessage = "At least one of ${fields.joinToString(", ")} must be provided"
        require(nonNullCount >= 1, fields.firstOrNull() ?: "") { message ?: defaultMessage }
    }
    
    /**
     * Validates that if condition is true, then value must not be null.
     */
    fun <T> requireIfCondition(
        condition: Boolean,
        value: T?,
        field: String,
        message: String? = null,
    ) {
        if (condition) {
            val defaultMessage = "$field is required when condition is met"
            require(value != null, field) { message ?: defaultMessage }
        }
    }
    
    /**
     * Validates that start value is less than end value (for ranges).
     */
    fun <T : Comparable<T>> requireValidRange(
        start: T,
        end: T,
        startField: String,
        endField: String,
        message: String? = null,
    ) {
        val defaultMessage = "$startField must be less than $endField"
        require(start < end, startField) { message ?: defaultMessage }
    }
    
    /**
     * Validates that start date is before end date.
     */
    fun requireValidDateRange(
        startDate: Long,
        endDate: Long,
        startField: String = "startDate",
        endField: String = "endDate",
        message: String = "$startField must be before $endField",
    ) {
        require(startDate < endDate, startField) { message }
    }
    
    // ============================================================
    // COLLECTION VALIDATORS (ENHANCED)
    // ============================================================
    
    /**
     * Validates each element in a collection.
     */
    inline fun <T> requireEach(
        collection: Collection<T>,
        field: String,
        validator: (T, Int) -> Unit,
    ) {
        collection.forEachIndexed { index, item ->
            try {
                validator(item, index)
            } catch (e: Exception) {
                addError("$field[$index]", e.message ?: "Invalid element at index $index")
            }
        }
    }
    
    /**
     * Validates that all elements in a collection are unique.
     */
    fun <T> requireAllUnique(
        collection: Collection<T>,
        field: String,
        message: String = "$field must contain unique elements",
    ) {
        require(collection.size == collection.toSet().size, field) { message }
    }
    
    /**
     * Validates that collection contains no null elements.
     */
    fun <T> requireNoNulls(
        collection: Collection<T?>,
        field: String,
        message: String = "$field cannot contain null elements",
    ) {
        require(collection.none { it == null }, field) { message }
    }
    
    // ============================================================
    // HELPER FUNCTIONS
    // ============================================================
    
    /**
     * Luhn algorithm for credit card validation.
     */
    private fun luhnCheck(digits: String): Boolean {
        var sum = 0
        var alternate = false
        for (i in digits.length - 1 downTo 0) {
            var n = digits[i].digitToInt()
            if (alternate) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alternate = !alternate
        }
        return sum % 10 == 0
    }
    
    /**
     * Validates a password strength.
     * Default: 8-64 chars, at least one uppercase, one lowercase, one digit.
     */
    fun requireValidPassword(
        value: String,
        field: String = "password",
        minLength: Int = 8,
        maxLength: Int = 64,
        requireUppercase: Boolean = true,
        requireLowercase: Boolean = true,
        requireDigit: Boolean = true,
        requireSpecialChar: Boolean = false,
        message: String? = null,
    ) {
        val errors = mutableListOf<String>()
        
        if (value.length < minLength) errors.add("at least $minLength characters")
        if (value.length > maxLength) errors.add("at most $maxLength characters")
        if (requireUppercase && !value.any { it.isUpperCase() }) errors.add("one uppercase letter")
        if (requireLowercase && !value.any { it.isLowerCase() }) errors.add("one lowercase letter")
        if (requireDigit && !value.any { it.isDigit() }) errors.add("one digit")
        if (requireSpecialChar && !value.any { !it.isLetterOrDigit() }) errors.add("one special character")
        
        if (errors.isNotEmpty()) {
            val defaultMessage = "$field must contain ${errors.joinToString(", ")}"
            require(false, field) { message ?: defaultMessage }
        }
    }
    
    /**
     * Validates a date is in range.
     */
    fun requireDateInRange(
        value: Long,
        range: LongRange,
        field: String = "date",
        message: String = "$field must be between ${range.first} and ${range.last}",
    ) {
        require(value in range, field) { message }
    }
    
    /**
     * Validates a monetary amount (positive, max 2 decimal places).
     */
    fun requireValidAmount(
        value: Double,
        field: String = "amount",
        minValue: Double = 0.01,
        maxValue: Double = Double.MAX_VALUE,
        message: String? = null,
    ) {
        val defaultMessage = "$field must be between $minValue and $maxValue"
        requireInRange(value, minValue..maxValue, field, message ?: defaultMessage)
        
        // Check decimal places
        val decimalPlaces = value.toString().substringAfter('.', "").length
        require(decimalPlaces <= 2, field) { "$field can have at most 2 decimal places" }
    }
    
    /**
     * Validates age range (for human age typically 13-150).
     */
    fun requireValidAge(
        value: Int,
        field: String = "age",
        minAge: Int = 13,
        maxAge: Int = 150,
        message: String = "$field must be between $minAge and $maxAge",
    ) {
        requireInRange(value, minAge..maxAge, field, message)
    }
    
    /**
     * Validates a percentage (0-100).
     */
    fun requireValidPercentage(
        value: Double,
        field: String = "percentage",
        message: String = "$field must be between 0 and 100",
    ) {
        requireInRange(value, 0.0..100.0, field, message)
    }
    
    /**
     * Validates a confidence score (0.0-1.0).
     */
    fun requireValidConfidence(
        value: Float,
        field: String = "confidence",
        message: String = "$field must be between 0.0 and 1.0",
    ) {
        requireInRange(value, 0f..1f, field, message)
    }
    
    /**
     * Adds a custom validation error.
     */
    fun addError(field: String, message: String, code: String = ErrorCode.VALIDATION) {
        errors.add(ValidationError(field, message, code))
    }
    
    /**
     * Builds the validation result.
     */
    fun build(): ValidationResult = ValidationResult(errors.toList())
}

/**
 * DSL function for validating skill input.
 * 
 * Usage:
 * ```kotlin
 * override suspend fun validate(input: MyInput): SkillResult<Unit> {
 *     return validate {
 *         requireNotBlank(input.name, "name")
 *         requirePositive(input.count, "count")
 *         requireInRange(input.confidence, 0f..1f, "confidence")
 *     }
 * }
 * ```
 */
inline fun validate(block: ValidationBuilder.() -> Unit): SkillResult<Unit> {
    return ValidationBuilder().apply(block).build().toSkillResult()
}

/**
 * Quick validation for a single condition.
 * 
 * Usage:
 * ```kotlin
 * override suspend fun validate(input: MyInput): SkillResult<Unit> {
 *     return requireValid(input.text.isNotBlank()) { "text cannot be blank" }
 * }
 * ```
 */
inline fun requireValid(
    condition: Boolean,
    errorCode: String = ErrorCode.VALIDATION,
    lazyMessage: () -> String,
): SkillResult<Unit> {
    return if (condition) {
        SkillResult.success(Unit)
    } else {
        SkillResult.failure(SkillError(errorCode, lazyMessage()))
    }
}

/**
 * Combines multiple validation results.
 */
fun combineValidations(vararg results: SkillResult<Unit>): SkillResult<Unit> {
    val firstFailure = results.firstOrNull { it.isFailure }
    return firstFailure ?: SkillResult.success(Unit)
}

/**
 * Extension to validate and then execute if valid.
 */
suspend inline fun <I, O> Skill<I, O>.validateAndExecute(
    input: I,
    context: SkillContext,
): SkillResult<O> {
    val validationResult = validate(input)
    return if (validationResult.isSuccess) {
        execute(input, context)
    } else {
        @Suppress("UNCHECKED_CAST")
        validationResult as SkillResult<O>
    }
}
