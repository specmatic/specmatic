package io.specmatic.core.pattern

import io.specmatic.core.FailureReport
import io.specmatic.core.FailureReason
import io.specmatic.core.MismatchMessages
import io.specmatic.core.Result
import io.specmatic.core.RuleViolation
import io.specmatic.core.RuleViolationReport
import io.specmatic.core.ScenarioDetailsForResult
import io.specmatic.core.dataTypeMismatchResult
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.value.StringValue

fun isCycle(throwable: Throwable?): Boolean = when(throwable) {
    is ContractException -> throwable.isCycle
    else -> false
}

data class ContractException(
    val errorMessage: String = "",
    val breadCrumb: String = "",
    val exceptionCause: Throwable? = null,
    val scenario: ScenarioDetailsForResult? = null,
    val failureReason: FailureReason? = null,
    val isCycle: Boolean = isCycle(exceptionCause),
    val ruleViolationReport: RuleViolationReport? = null,
) : Exception(errorMessage, exceptionCause) {
    constructor(failureReport: FailureReport, ruleViolationReport: RuleViolationReport? = null) : this(
        errorMessage = failureReport.errorMessage(),
        breadCrumb = failureReport.breadCrumbs(),
        ruleViolationReport = ruleViolationReport
    )

    fun failure(): Result.Failure {
        val failure = Result.Failure(
            message = errorMessage,
            cause = when (exceptionCause) {
                is ContractException -> exceptionCause.failure()
                is Throwable -> Result.Failure(exceptionCauseMessage(exceptionCause))
                else -> null
            },
            breadCrumb = breadCrumb,
            failureReason = failureReason
        ).withRuleViolationReport(ruleViolationReport)

        return scenario?.let(failure::updateScenario) ?: failure
    }

    fun report(): String = failure().toReport().toText()
}

fun <ReturnType> attempt(errorMessage: String = "", breadCrumb: String = "", f: ()->ReturnType): ReturnType {
    try {
        return f()
    }
    catch(contractException: ContractException) {
        throw ContractException(errorMessage, breadCrumb, contractException, contractException.scenario)
    }
    catch(throwable: Throwable) {
        throw ContractException("$errorMessage\nError: $throwable", breadCrumb, throwable)
    }
}

fun <ReturnType> attempt(f: ()->ReturnType): ReturnType {
    try {
        return f()
    }
    catch(throwable: Throwable) {
        throw ContractException("Error: ${throwable.localizedMessage}", exceptionCause = throwable)
    }
}

inline fun <ReturnType> scenarioBreadCrumb(scenario: ScenarioDetailsForResult, f: ()->ReturnType): ReturnType {
    try {
        return f()
    } catch(e: ContractException) {
        throw e.copy(scenario = scenario)
    }
}

fun <ReturnType> attemptParse(pattern: Pattern, value: String, mismatchMessages: MismatchMessages, f: () -> ReturnType): ReturnType {
    return attemptParse(pattern.typeName, value, mismatchMessages, f)
}

fun <ReturnType> attemptParse(typeName: String, value: String, mismatchMessages: MismatchMessages, f: () -> ReturnType): ReturnType {
    try {
        return f()
    } catch (throwable: Throwable) {
        val mismatchFailure = dataTypeMismatchResult(typeName, StringValue(value), mismatchMessages)
        throw ContractException(mismatchFailure.removeViolationReport().toFailureReport(), mismatchFailure.ruleViolationReport).copy(exceptionCause = throwable)
    }
}

fun resultOf(ruleViolation: RuleViolation? = null, f: () -> Result): Result {
    return try {
        f()
    } catch (e: ContractException) {
        val failure = e.failure()
        if (ruleViolation != null) failure.withRuleViolation(ruleViolation) else failure
    } catch(e: Throwable) {
        Result.Failure(message = exceptionCauseMessage(e), ruleViolation = ruleViolation)
    }
}

fun Throwable.toFailure(): Result.Failure {
    return when (this) {
        is ContractException -> this.failure()
        else -> Result.Failure(exceptionCauseMessage(this))
    }
}
