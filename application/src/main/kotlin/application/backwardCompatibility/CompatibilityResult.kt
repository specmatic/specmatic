package application.backwardCompatibility

sealed interface CompatibilityResult {
    data object Passed : CompatibilityResult
    data object Failed : CompatibilityResult

    data class Unknown(
        val reason: UnknownReason,
    ) : CompatibilityResult

    companion object {
        val FAILED = Failed
        val PASSED = Passed
        val UNSPECIFIED = Unknown(UnknownReason.UNSPECIFIED)
    }

}

enum class UnknownReason {
    UNSPECIFIED,
    INSIGHTS_CHECK_FAILED,
    UNSUPPORTED_PROTOCOL,
    LICENSE_UNAVAILABLE,
    NO_OPERATION_REQUESTS,
}
