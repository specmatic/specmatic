package io.specmatic.conversions

import io.specmatic.core.RuleViolation

enum class SchemaLintViolations(
    override val id: String,
    override val title: String,
    override val summary: String? = null
) : RuleViolation {
    INVALID_MIN_LENGTH(
        id = "S0004",
        title = "Invalid min length",
        summary = "minLength should never be less than 0, but it is -10. Please use a positive minLength, or drop the constraint."
    ),

    INVALID_MAX_LENGTH(
        id = "S0002",
        title = "Invalid max length",
        summary = "maxLength 10 should have been greater than minLength 20. Please make sure that maxLength and minLength are not in conflict."
    ),

    LENGTH_EXCEEDS_LIMIT(
        id = "S0003",
        title = "Excessive length",
        summary = "A length of 2GB is impractical. Limiting the maxLength for now to the more practical 4MB, which is enough for most purposes. Please double-check the maxLength needed for this value and adjust accordingly."
    ),

    PATTERN_LENGTH_INCOMPATIBLE(
        id = "S0002",
        title = "Pattern length conflict",
        summary = "The regex pattern \"a{20}\" is incompatible with maxLength 10. Either remove maxLength, or ensure that it is greater than the largest possible regex."
    ),

    INVALID_NUMERIC_BOUNDS(
        id = "S0002",
        title = "Invalid numeric bounds",
        summary = "maximum 10 should have been greater than minimum 20. Please make sure that maximum and minimum are not in conflict."
    ),
}
