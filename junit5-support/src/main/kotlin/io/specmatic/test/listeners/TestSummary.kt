package io.specmatic.test.listeners

data class TestSummary(val success: Int, val partialSuccesses: Int, val aborted: Int, val failure: Int, val wip: Int = 0) {
    val message: String
        get() {
            val total = success + aborted + failure + wip
            val partialSuccessNote = if(partialSuccesses > 0) " (of which $partialSuccesses are partial)" else ""
            return "Tests run: $total, Successes: ${success}$partialSuccessNote, Failures: $failure, WIP: $wip, Errors: $aborted"
        }
}
