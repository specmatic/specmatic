package application.backwardCompatibility

internal data class BCCExampleValidationStatus(
    val areExamplesInvalid: Boolean,
    val hasUnloadableExamples: Boolean,
    val hasInvalidChangedExternalisedExamples: Boolean = false
) {
    val hasErrors: Boolean
        get() = areExamplesInvalid || hasUnloadableExamples || hasInvalidChangedExternalisedExamples
}
