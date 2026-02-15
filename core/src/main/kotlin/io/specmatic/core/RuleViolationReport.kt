package io.specmatic.core

data class RuleViolationReport(val ruleViolations: Set<RuleViolation> = emptySet()) {
    fun withViolation(violation: RuleViolation): RuleViolationReport {
        return copy(ruleViolations = ruleViolations.plus(violation))
    }

    fun plus(other: RuleViolationReport?): RuleViolationReport {
        if (other == null) return this
        return copy(ruleViolations = ruleViolations + other.ruleViolations)
    }

    fun toText(): String? {
        if (ruleViolations.isEmpty()) return null
        if (ruleViolations.size == 1) return ruleViolationToText(ruleViolations.first())
        return ruleViolations.joinToString(prefix = "- ", separator = "\n\n- ", transform = ::ruleViolationToText)
    }

    fun toSnapShots(): List<RuleViolationSnapshot> {
        return ruleViolations.map {
            it.snapshot(it.toDocumentationUrl())
        }
    }

    private fun ruleViolationToText(rule: RuleViolation): String = buildString {
        append("${rule.id}: ${rule.title}\n")
        append("Documentation: ${rule.toDocumentationUrl()}")
        rule.summary?.let { append("\nSummary: $it") }
    }

    private fun RuleViolation.toDocumentationUrl(): String {
        return listOf(RULES_DOCUMENTATION_URL, id.lowercase()).joinToString("#")
    }

    companion object {
        private const val RULES_DOCUMENTATION_URL = "https://docs.specmatic.io/rules"

        fun from(violationSegment: RuleViolation): RuleViolationReport {
            return RuleViolationReport(ruleViolations = setOf(violationSegment))
        }
    }
}
