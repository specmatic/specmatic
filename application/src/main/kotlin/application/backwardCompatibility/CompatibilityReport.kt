package application.backwardCompatibility

class CompatibilityReport(results: List<CompatibilityResult>) {
    val report: String
    val exitCode: Int

    init {
        val failed: Boolean = results.any { it == CompatibilityResult.FAILED }
        val failedCount = results.count { it == CompatibilityResult.FAILED }
        val passedCount = results.count { it == CompatibilityResult.PASSED }

        report = "Files checked: ${results.size} (Passed: ${passedCount}, Failed: $failedCount)"
        exitCode = if(failed) 1 else 0
    }

    companion object {
        fun emptyReport(): String {
            return CompatibilityReport(emptyList()).report
        }
    }
}
