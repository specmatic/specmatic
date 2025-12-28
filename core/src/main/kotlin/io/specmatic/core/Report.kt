package io.specmatic.core

import io.specmatic.reporter.ctrf.model.CtrfIssue
import io.specmatic.reporter.ctrf.model.CtrfIssueSeverity
import io.specmatic.reporter.ctrf.model.CtrfRuleViolationSnapshot
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
    val severity: IssueSeverity
) {
    fun toCtrfIssue(): CtrfIssue {
        return CtrfIssue(
            path = this.path,
            details = this.details,
            breadCrumb = this.breadCrumb,
            severity = CtrfIssueSeverity.fromValue(this.severity.name),
            ruleViolations = this.ruleViolations.map { ruleViolationSnapshot ->
                CtrfRuleViolationSnapshot(
                    id = ruleViolationSnapshot.id,
                    title = ruleViolationSnapshot.title,
                    documentationUrl = ruleViolationSnapshot.documentationUrl,
                    summary = ruleViolationSnapshot.summary
                )
            },
        )
    }
}

interface Report {
    override fun toString(): String
    fun toText(): String
    fun toIssues(breadCrumbToJsonPathConverter: BreadCrumbToJsonPathConverter = BreadCrumbToJsonPathConverter()): List<Issue>
}
