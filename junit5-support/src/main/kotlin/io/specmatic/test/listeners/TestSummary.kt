package io.specmatic.test.listeners

data class TestSummary(
    val success: Int,
    val failure: Int,
    val aborted: Int,
    val skipped: Int,
    val excluded: Int,
    val partialSuccess: Int,
) {
    val failed: Int = failure + aborted
    val total: Int = success + failed + skipped + excluded
    val message: String = "Success: $success, Failure: $failed, Skipped: $skipped, Excluded: $excluded, Total: $total"
}
