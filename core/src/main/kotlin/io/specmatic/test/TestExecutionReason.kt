package io.specmatic.test

import io.specmatic.core.RuleViolation

enum class TestExecutionReason(override val id: String, override val title: String, override val summary: String? = null) : RuleViolation {
    HAS_EXAMPLE(
        id = "T10001",
        title = "Executed Using Example",
        summary = "This operation was executed by using an available example"
    ),
    NO_EXAMPLE(
        id = "T10002",
        title = "Executed Using Generation",
        summary = "This operation was executed by generating payloads, due to the absence of an example"
    ),
    POSITIVE_GENERATION_ENABLED(
        id = "T10003",
        title = "Executed Using Positive Generation",
        summary = "This operation was executed by generating +ve payloads, due to positive generation being enabled"
    ),
    NEGATIVE_GENERATION_ENABLED(
        id = "T10004",
        title = "Executed Using Negative Generation",
        summary = "This operation was executed by generating -ve payloads, due to negative generation being enabled"
    );

    companion object {
        fun executed(hasExamples: Boolean): TestExecutionReason = if (hasExamples) HAS_EXAMPLE else NO_EXAMPLE
        fun executedPositiveGen(): TestExecutionReason = POSITIVE_GENERATION_ENABLED
        fun executedNegativeGen(): TestExecutionReason = NEGATIVE_GENERATION_ENABLED
    }
}
