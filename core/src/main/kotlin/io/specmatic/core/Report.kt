package io.specmatic.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ErrorSeverity {
    @SerialName("error")
    ERROR,

    @SerialName("warning")
    WARNING
}

@Serializable
data class Error(
    val breadCrumb: String,
    val path: List<String>,
    val ruleViolations: List<RuleViolationSnapshot>,
    val details: String,
    val severity: ErrorSeverity
)

interface Report {
    override fun toString(): String
    fun toText(): String
    fun toErrors(): List<Error>
}
