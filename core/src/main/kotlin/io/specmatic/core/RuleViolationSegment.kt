package io.specmatic.core

interface RuleViolationSegment {
    val id: String
}

enum class StandardRuleViolationSegment(override val id: String): RuleViolationSegment {
    ValueMismatch(id = "value-mismatch"),
    TypeMismatch(id = "type-mismatch"),
    ParseFailure(id = "parse-failure"),
    PatternMismatch(id = "pattern-mismatch"),
    ConstraintViolation(id = "constraint-violation"),

    MissingMandatoryKey(id = "missing-mandatory-key"),
    FuzzyMatchMissingMandatoryKey(id = "missing-mandatory-key-fuzzy"),
    MissingOptionalKey(id = "missing-optional-key"),
    FuzzyMatchMissingOptionalKey(id = "missing-optional-key-fuzzy"),
    UnknownKey(id = "unknown-key"),
}
