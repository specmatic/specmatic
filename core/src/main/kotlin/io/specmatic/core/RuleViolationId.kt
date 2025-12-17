package io.specmatic.core

import io.specmatic.core.utilities.Flags

data class RuleViolationId(private val ruleViolationContext: List<RuleViolationContext> = emptyList(), private val ruleViolationSegment: List<RuleViolationSegment> = emptyList()) {
    fun withContext(context: RuleViolationContext): RuleViolationId {
        return copy(ruleViolationContext = ruleViolationContext.plus(context))
    }

    fun withViolation(violation: RuleViolationSegment): RuleViolationId {
        return copy(ruleViolationSegment = ruleViolationSegment.plus(violation))
    }

    fun plus(other: RuleViolationId?): RuleViolationId {
        if (other == null) return this
        return copy(ruleViolationContext = ruleViolationContext + other.ruleViolationContext, ruleViolationSegment = ruleViolationSegment + other.ruleViolationSegment)
    }

    fun finalizeRuleId(): String? {
        if (ruleViolationSegment.isEmpty()) return null
        val contextPrefix = canonicalContextPrefix()
        val combinedRuleId = ruleViolationSegment.canonicalizeSegments().joinToString(RULE_VIOLATION_SEPARATOR) { it.id }
        val fullRuleId = listOf(contextPrefix, combinedRuleId).filter(String::isNotBlank).joinToString(RULE_VIOLATION_SEPARATOR)
        return "rule: ${rulesDocumentationUrl(fullRuleId)}"
    }

    private fun canonicalContextPrefix(): String = ruleViolationContext.canonicalizeContexts().joinToString(RULE_CONTEXT_SEPARATOR) { it.context }

    private fun List<RuleViolationContext>.canonicalizeContexts(): List<RuleViolationContext> = asReversed().distinctBy { it.groupId }.sortedBy { it.groupId }

    private fun List<RuleViolationSegment>.canonicalizeSegments(): List<RuleViolationSegment> = asReversed().distinctBy { it.id }

    private fun rulesDocumentationUrl(ruleId: String): String = listOf(RULES_DOCUMENTATION_URL, ruleId).filter(String::isNotBlank).joinToString("#")

    companion object {
        private const val DEFAULT_RULES_DOCUMENTATION_URL = "https://docs.specmatic.io/rules"
        private const val RULE_CONTEXT_SEPARATOR = "-"
        private const val RULE_VIOLATION_SEPARATOR = "-"
        private val RULES_DOCUMENTATION_URL = Flags.getStringValue("RULES_DOCUMENTATION_URL") ?: DEFAULT_RULES_DOCUMENTATION_URL

        fun from(violationSegment: RuleViolationSegment): RuleViolationId {
            return RuleViolationId(ruleViolationSegment = listOf(violationSegment))
        }

        fun from(context: RuleViolationContext): RuleViolationId {
            return RuleViolationId(ruleViolationContext = listOf(context))
        }
    }
}
