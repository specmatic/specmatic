package io.specmatic.core.utilities

import io.specmatic.core.RuleViolation
import io.specmatic.core.RuleViolationReport

data class Reasoning(val mainReason: RuleViolation? = null, val otherReasons: List<RuleViolation> = emptyList()) {
    fun withMainReason(reason: RuleViolation): Reasoning = copy(mainReason = reason, otherReasons = listOfNotNull(mainReason).plus(otherReasons))
    fun toRuleViolationText(): String = toRuleViolationReport().toText().orEmpty()
    fun toRuleViolationReport(): RuleViolationReport {
        val violations = linkedSetOf<RuleViolation>().apply { mainReason?.let(::add); addAll(otherReasons) }
        return RuleViolationReport(violations)
    }
}
