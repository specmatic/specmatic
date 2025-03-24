package io.specmatic.core.pattern

import io.specmatic.core.FailureReport
import io.specmatic.core.Result
import io.specmatic.core.ScenarioDetailsForResult
import io.specmatic.core.utilities.exceptionCauseMessage

fun isCycle(throwable: Throwable?): Boolean = when(throwable) {
    is ContractException -> throwable.isCycle
    else -> false
}

data class ContractException(
    val errorMessage: String = "",
    val breadCrumb: String = "",
    val exceptionCause: Throwable? = null,
    val scenario: ScenarioDetailsForResult? = null,
    val isCycle: Boolean = isCycle(exceptionCause)
) : Exception(errorMessage, exceptionCause) {
    constructor(failureReport: FailureReport): this(failureReport.errorMessage(), failureReport.breadCrumbs())

    fun failure(): Result.Failure =
        Result.Failure(
            message = errorMessage,
            cause = when(exceptionCause) {
                is ContractException -> exceptionCause.failure()
                is Throwable -> Result.Failure(exceptionCauseMessage(exceptionCause))
                else -> null
            },
            breadCrumb = breadCrumb
        ).also { result ->
            if(scenario != null) result.updateScenario(scenario)
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

fun resultOf(f: () -> Result): Result {
    return try {
        f()
    } catch(e: Throwable) { Result.Failure(e.localizedMessage) }
}
