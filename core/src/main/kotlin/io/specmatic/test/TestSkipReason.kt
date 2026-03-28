package io.specmatic.test

import io.specmatic.core.RuleViolation

enum class TestSkipReason(override val id: String, override val title: String, override val summary: String? = null): RuleViolation {
    EXCLUDED(
        id = "T00001",
        title = "Excluded from Run",
        summary = "This operation was skipped because it did not match the selected filters"
    ),
    EXAMPLES_REQUIRED(
        id = "T00003",
        title = "Examples Required",
        summary = "This operation requires examples to run, but none were provided"
    ),
    EXAMPLES_REQUIRED_STRICT_MODE(
        id = "T00002",
        title = "Examples Required in Strict Mode",
        summary = "Strict mode requires at least one example, but none were found for this operation"
    ),
    ACCEPT_MISMATCH(
        id = "T00004",
        title = "Accept Mismatch",
        summary = "The request Accept header does not match the response content type of the operation"
    ),
    GENERATIVE_DISABLED(
        id = "T00005",
        title = "Generation Disabled",
        summary = "This operation was skipped because it required generation to be enabled"
    ),
    MAX_TEST_COUNT_EXCEEDED(
        id = "T00006",
        title = "Maximum Test Count Exceeded",
        summary = "This operation was skipped because it exceeded the maximum test count"
    );

    companion object {
        fun noExamplesNon2xxAndNon400(): TestSkipReason = EXAMPLES_REQUIRED
        fun noExamples2xxAnd400(strictMode: Boolean): TestSkipReason = if (strictMode) EXAMPLES_REQUIRED_STRICT_MODE else EXAMPLES_REQUIRED
    }
}
