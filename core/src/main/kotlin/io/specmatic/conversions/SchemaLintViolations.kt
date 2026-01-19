package io.specmatic.conversions

import io.specmatic.core.RuleViolation

enum class SchemaLintViolations(override val id: String, override val title: String, override val summary: String? = null) : RuleViolation {
    INVALID_MIN_LENGTH(
        id = "S0004",
        title = "Invalid min length",
        summary = "Minimum length must be a positive integer"
    ),

    INVALID_MAX_LENGTH(
        id = "S0002",
        title = "Invalid max length",
        summary = "Maximum length must be greater than or equal to minimum length"
    ),

    LENGTH_EXCEEDS_LIMIT(
        id = "S0003",
        title = "Excessive length",
        summary = "Length should not exceed recommended maximum of 4MB"
    ),

    PATTERN_LENGTH_INCOMPATIBLE(
        id = "S0002",
        title = "Pattern length conflict",
        summary = "Pattern must be able to generate values matching the minimum and maximum length"
    ),

    INVALID_NUMERIC_BOUNDS(
        id = "S0002",
        title = "Invalid numeric bounds",
        summary = "Maximum must be greater than or equal to minimum"
    ),
}
