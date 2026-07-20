package application.backwardCompatibility

internal data class BCCExampleValidationStatus(
    val areExamplesInvalid: Boolean,
    val hasUnloadableExamples: Boolean
) {
    val hasErrors: Boolean
        get() = areExamplesInvalid || hasUnloadableExamples
}