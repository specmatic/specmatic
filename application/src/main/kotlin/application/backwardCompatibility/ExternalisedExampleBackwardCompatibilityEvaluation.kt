package application.backwardCompatibility

import io.specmatic.core.examples.module.ValidationResults

data class ExternalisedExampleBackwardCompatibilityEvaluation(
    val validationResults: ValidationResults = ValidationResults.forNoExamples(),
    val directories: Set<String> = emptySet(),
    val changedFileCount: Int = 0,
    val unloadableExamples: Set<String> = emptySet()
) {
    fun hasValidationErrors(): Boolean = !validationResults.success

    fun hasUnloadableExamples(): Boolean = unloadableExamples.isNotEmpty()

    fun shouldLogSummary(): Boolean =
        directories.isNotEmpty() ||
            validationResults.exampleValidationResults.isNotEmpty() ||
            changedFileCount > 0 ||
            unloadableExamples.isNotEmpty()
}
