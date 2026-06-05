package io.specmatic.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class IssueSeverity {
    @SerialName("error")
    ERROR,

    @SerialName("warning")
    WARNING
}

@Serializable
data class Issue(
    val breadCrumb: String,
    val path: List<String>,
    val ruleViolations: List<RuleViolationSnapshot>,
    val details: String,
    val severity: IssueSeverity,
    // Head-to-tail chain of $ref use-sites for this issue; the last element is the actual source.
    val sourceLocations: List<SourceLocation> = emptyList()
)

interface Report {
    override fun toString(): String
    fun toText(): String
    fun toIssues(breadCrumbToJsonPathConverter: BreadCrumbToJsonPathConverter = BreadCrumbToJsonPathConverter()): List<Issue>
}
