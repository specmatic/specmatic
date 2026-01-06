package io.specmatic.conversions.lenient

import io.specmatic.core.FailureReason
import io.specmatic.core.Result

class FailureDeDuper(private val failures: List<Result.Failure>) {
    fun deDuplicate(): Result.Failure {
        val deduped = failures.groupBy { it.key() }.mapValues { (_, fs) -> fs.reduce { a, b -> a.merge(b) } }.values.toList()
        return Result.Failure.fromFailures(deduped)
    }

    private fun Result.Failure.key(): FailureKey = FailureKey(
        breadCrumb = breadCrumb,
        failureReason = failureReason,
        ruleViolationKey = ruleViolationReport?.ruleViolations?.map { it.id }
    )

    private fun Result.Failure.merge(other: Result.Failure): Result.Failure {
        require(this.key() == other.key())
        return this.copy(
            causes = this.causes.merge(other.causes),
            isPartial = this.isPartial && other.isPartial,
            ruleViolationReport = when {
                this.ruleViolationReport != null && other.ruleViolationReport != null -> this.ruleViolationReport.plus(other.ruleViolationReport)
                else -> this.ruleViolationReport ?: other.ruleViolationReport
            }
        )
    }

    private fun Result.FailureCause.key(): CauseKey = CauseKey(message = message, childKey = cause?.key())
    private fun List<Result.FailureCause>.merge(other: List<Result.FailureCause>): List<Result.FailureCause> {
        val map = LinkedHashMap<CauseKey, Result.FailureCause>()
        for (cause in this.plus(other)) {
            val key = cause.key()
            map[key] = map[key]?.merge(cause) ?: cause
        }

        return map.values.toList()
    }

    private fun Result.FailureCause.merge(other: Result.FailureCause): Result.FailureCause {
        val thisCause = this.cause
        val otherCause = other.cause
        return Result.FailureCause(
            message = message,
            cause = when {
                thisCause != null && otherCause != null -> thisCause.merge(otherCause)
                else -> thisCause ?: otherCause
            }
        )
    }

    private data class FailureKey(val breadCrumb: String, val failureReason: FailureReason?, val ruleViolationKey: List<String>?)

    private data class CauseKey(val message: String, val childKey: FailureKey?)
}
