package io.specmatic.conversions

import io.specmatic.core.RuleViolation

enum class SchemaLintViolations(override val id: String, override val title: String, override val summary: String? = null) : RuleViolation {
    BAD_VALUE(
        id = "S0001",
        title = "Invalid Value",
        summary = "The provided value is invalid and hence cannot be used"
    ),

    IMPRACTICAL_VALUE(
        id = "S0002",
        title = "Impractical value",
        summary = "The provided value is valid but is impractical to interpret or use"
    ),

    CONFLICTING_CONSTRAINTS(
        id = "S0003",
        title = "Conflicting Constaints",
        summary = "One or more constraints contradict each other and cannot be satisfied together"
    ),
}
