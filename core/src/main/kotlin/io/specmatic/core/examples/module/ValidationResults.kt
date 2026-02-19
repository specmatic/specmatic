package io.specmatic.core.examples.module

import io.specmatic.core.Result

class ValidationResults(val exampleValidationResults: Map<String, Result>, private val hookValidationResult: Result) {
    val success: Boolean
        get() {
            if(exampleValidationResults.containsOnlyCompleteFailures())
                return false

            if(hookValidationResult is Result.Failure && !hookValidationResult.isPartialFailure())
                return false

            return true

        }

    val exitCode: Int
        get() {
            if(success) return SUCCESS_EXIT_CODE
            return FAILURE_EXIT_CODE
        }

    private fun Map<String, Result>.containsOnlyCompleteFailures(): Boolean {
        return this.any { it.value is Result.Failure && !it.value.isPartialFailure() }
    }

    fun plus(exampleValidationResults: Map<String, Result>) =
        ValidationResults(this.exampleValidationResults + exampleValidationResults, hookValidationResult)

    fun merge(other: ValidationResults): ValidationResults = ValidationResults(
        exampleValidationResults = this.exampleValidationResults + other.exampleValidationResults,                 // maps: later wins if same key
        hookValidationResult = Result.fromResults(listOf(this.hookValidationResult, other.hookValidationResult))
    )

    companion object {
        fun forNoExamples(): ValidationResults {
            return ValidationResults(emptyMap(), Result.Success())
        }
    }
}

fun List<ValidationResults>.ofAllExamples() =
    flatMap { it.exampleValidationResults.entries }.associate { entry -> entry.toPair() }

fun List<ValidationResults>.exitCode(): Int {
    return if (any { !it.success })
        FAILURE_EXIT_CODE
    else
        SUCCESS_EXIT_CODE
}
