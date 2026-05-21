package application.validate

import io.specmatic.core.Result
import java.io.File

data class SpecificationValidationOutcome<Feature>(
    val file: File,
    val validationResult: SpecValidationResult<Feature>
) {
    val hasFailed: Boolean
        get() = when (validationResult) {
            is SpecValidationResult.FailedToLoad<*> -> true
            is SpecValidationResult.LinterResult<*> -> false
            is SpecValidationResult.ValidationResult<*> -> validationResult.result is Result.Failure && !validationResult.result.isPartial
        }

    val hasErrors: Boolean
        get() = when (validationResult) {
            is SpecValidationResult.FailedToLoad -> !validationResult.result.isPartial
            is SpecValidationResult.LinterResult -> validationResult.result.totals.errors > 0
            is SpecValidationResult.ValidationResult -> validationResult.result is Result.Failure && !validationResult.result.isPartial
        }

    val result: Result
        get() = when (validationResult) {
            is SpecValidationResult.FailedToLoad -> validationResult.result
            is SpecValidationResult.ValidationResult -> validationResult.result
            is SpecValidationResult.LinterResult -> if (validationResult.result.totals.errors > 0) {
                Result.Failure("Specification lint failed")
            } else if (validationResult.result.totals.warnings > 0) {
                Result.Failure("Specification lint warnings", isPartial = true)
            } else {
                Result.Success()
            }
        }

    val feature: Feature?
        get() = when (validationResult) {
            is SpecValidationResult.FailedToLoad -> null
            is SpecValidationResult.LinterResult -> validationResult.feature
            is SpecValidationResult.ValidationResult -> validationResult.feature
        }
}

data class ExampleValidationOutcome(
    val file: File,
    val isSpecificationSpecific: Boolean,
    val validationResult: ExampleValidationResult,
) {
    val hasErrors: Boolean get() = when (validationResult) {
        is ExampleValidationResult.DoesNotBelong -> {
            val result = validationResult.result
            isSpecificationSpecific && result is Result.Failure && !result.isPartial
        }
        else -> result is Result.Failure && !result.isPartial
    }

    val result: Result = validationResult.result
}

data class SpecificationValidationResults<Feature>(
    val specFile: File,
    val globalExamplesResult: Result = Result.Success(),
    val specificationOutcome: SpecificationValidationOutcome<Feature>,
    val sharedExampleResults: List<ExampleValidationOutcome> = emptyList(),
    val inlineExampleResult: Map<String, ExampleValidationOutcome> = emptyMap(),
    val specificationExampleResults: List<ExampleValidationOutcome> = emptyList(),
) {
    val hasErrors: Boolean = specificationOutcome.hasErrors || specificationExampleResults.any { it.hasErrors } || sharedExampleResults.any { it.hasErrors } || !globalExamplesResult.isSuccess() || inlineExampleResult.any { it.value.hasErrors }
    val hasSpecErrors: Boolean  = specificationOutcome.hasErrors
}

data class ValidationSummary<Feature>(val results: List<SpecificationValidationResults<Feature>>) {
    val totalSpecifications: Int = results.size
    val isSuccess: Boolean = results.count { it.hasErrors } == 0
    val failedSpecifications: Int = results.count { it.hasSpecErrors }
    val totalExamples: Int = results.sumOf { it.specificationExampleResults.size + it.sharedExampleResults.size + it.inlineExampleResult.size }
    val failedExamples: Int = results.sumOf { result ->
        result.specificationExampleResults.count { it.hasErrors } + result.sharedExampleResults.count { it.hasErrors } + result.inlineExampleResult.count { it.value.hasErrors }
    }
}
