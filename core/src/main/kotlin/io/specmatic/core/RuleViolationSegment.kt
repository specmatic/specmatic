package io.specmatic.core

interface RuleViolationSegment {
    val id: String
}

enum class StandardRuleViolationSegment(override val id: String): RuleViolationSegment {
    ValueMismatch(id = "value-mismatch"),
    TypeMismatch(id = "type-mismatch"),
    PatternMismatch(id = "pattern-mismatch"),
    ConstraintViolation(id = "constraint-violation"),
}
