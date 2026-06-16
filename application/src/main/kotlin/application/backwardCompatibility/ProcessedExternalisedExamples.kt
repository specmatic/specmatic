package application.backwardCompatibility

import io.specmatic.core.examples.module.ValidationResults

data class ProcessedExternalisedExamples(
    val validationResults: ValidationResults = ValidationResults.forNoExamples(),
    val directories: Set<String> = emptySet(),
    val changedFileCount: Int = 0,
    val unloadableExamples: Set<String> = emptySet()
)