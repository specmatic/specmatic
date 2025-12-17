package io.specmatic.core

import io.specmatic.core.utilities.Flags

data class RuleViolationReport(private val ruleViolations: List<RuleViolation> = emptyList()) {
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
        return ruleViolations.joinToString("\n- ", transform = ::ruleViolationToText)
    }

    private fun ruleViolationToText(rule: RuleViolation): String = buildString {
        appendLine("${rule.id}: ${rule.title}")
        appendLine("Documentation: $RULES_DOCUMENTATION_URL#${rule.id}")
        rule.summary?.let { appendLine("summary: $it") }
    }

    companion object {
        private const val DEFAULT_RULES_DOCUMENTATION_URL = "https://docs.specmatic.io/rules"
        private val RULES_DOCUMENTATION_URL = Flags.getStringValue("RULES_DOCUMENTATION_URL") ?: DEFAULT_RULES_DOCUMENTATION_URL

        fun from(violationSegment: RuleViolation): RuleViolationReport {
            return RuleViolationReport(ruleViolations = listOf(violationSegment))
        }
    }
}
