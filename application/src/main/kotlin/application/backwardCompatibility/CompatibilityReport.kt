package application.backwardCompatibility

class CompatibilityReport(
    results: List<CompatibilityResult>,
    summary: Summary = Summary()
) {
    val report: String
    val exitCode: Int

    init {
        val failed: Boolean = results.any { it == CompatibilityResult.FAILED }
        val failedCount = results.count { it == CompatibilityResult.FAILED }
        val passedCount = results.count { it == CompatibilityResult.PASSED }

        report = buildString {
            appendLine("Specs checked: ${results.size} (Passed: ${passedCount}, Failed: $failedCount)")
            appendLine("Changed specs: ${summary.changedSpecsCount}")
            appendLine("Changed externalised example files: ${summary.changedExternalisedExampleFilesCount}")
            appendLine("Specs with backward compatibility failures: ${summary.specBackwardCompatibilityFailureCount}")
            append("Specs with externalised example validation failures: ${summary.specExternalExampleValidationFailureCount}")
        }
        exitCode = if(failed) 1 else 0
    }

    data class Summary(
        val changedSpecsCount: Int = 0,
        val changedExternalisedExampleFilesCount: Int = 0,
        val specBackwardCompatibilityFailureCount: Int = 0,
        val specExternalExampleValidationFailureCount: Int = 0
    )

    companion object {
        fun emptyReport(): String {
            return CompatibilityReport(emptyList()).report
        }
    }
}
