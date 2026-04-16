package io.specmatic.core.utilities

import io.specmatic.core.RuleViolation
import io.specmatic.core.RuleViolationReport
import io.specmatic.reporter.ctrf.model.CtrfRuleSnapshot

data class Reasoning(val mainReason: RuleViolation? = null, val otherReasons: List<RuleViolation> = emptyList()) {
    fun withMainReason(reason: RuleViolation): Reasoning = copy(mainReason = reason, otherReasons = listOfNotNull(mainReason).plus(otherReasons))
    fun toRuleViolationText(): String = toRuleViolationReport().toText().orEmpty()

    fun toRuleViolationReport(): RuleViolationReport {
        val violations = linkedSetOf<RuleViolation>().apply { mainReason?.let(::add); addAll(otherReasons) }
        return RuleViolationReport(violations)
    }

    fun reasonsMatching(predicate: (RuleViolation) -> Boolean): List<RuleViolation>? {
        val reasons = listOfNotNull(mainReason).plus(otherReasons).filter(predicate)
        if (reasons.isEmpty()) return null
        return reasons
    }

    fun hasReason(reason: RuleViolation): Boolean {
        return mainReason == reason || otherReasons.contains(reason)
    }

    fun toCtrfSnapshots(): List<CtrfRuleSnapshot> {
        return this.toRuleViolationReport().toSnapShots().map { snapShot ->
            CtrfRuleSnapshot(id = snapShot.id, title = snapShot.title, documentationUrl = snapShot.documentationUrl, summary = snapShot.summary)
        }
    }
}
