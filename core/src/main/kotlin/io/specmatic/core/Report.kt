package io.specmatic.core

enum class ErrorSeverity { ERROR, WARNING }
data class Error(
    val breadCrumb: String,
    val path: List<String>,
    val ruleViolations: List<RuleViolation>,
    val details: String,
    val severity: ErrorSeverity
)

interface Report {
    override fun toString(): String
    fun toText(): String
    fun toErrors(): List<Error>
}
