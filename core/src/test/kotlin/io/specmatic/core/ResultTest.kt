package io.specmatic.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ResultTest {
    @Test
    fun `transformRecursive should transform leaf failure`() {
        val failure = Result.Failure(message = "error", breadCrumb = "leaf")
        val transformed = failure.transformRecursive { it.copy(breadCrumb = "changed") }

        assertThat(transformed.breadCrumb).isEqualTo("changed")
        assertThat(transformed.message).isEqualTo("error")
        assertThat(failure.breadCrumb).isEqualTo("leaf")
    }

    @Test
    fun `transformRecursive should transform every failure in tree`() {
        val nestedFailure = Result.Failure(
            breadCrumb = "outer",
            cause = Result.Failure(cause = Result.Failure("inner", breadCrumb = "inner"), breadCrumb = "middle"),
        )

        val transformed = nestedFailure.transformRecursive { it.copy(contractPath = "transformed") }
        assertThat(transformed.contractPath).isEqualTo("transformed")
        assertThat(transformed.cause?.contractPath).isEqualTo("transformed")
        assertThat(transformed.cause?.cause?.contractPath).isEqualTo("transformed")
    }

    @Test
    fun `should return result failure without rule violation report`() {
        val result = Result.Failure(
            causes = listOf(
                Result.FailureCause(
                    cause = Result.Failure(
                        ruleViolationReport = RuleViolationReport(ruleViolations = setOf(OpenApiRuleViolation.METHOD_MISMATCH)),
                        causes = listOf(
                            Result.FailureCause(
                                cause = Result.Failure(
                                    ruleViolationReport = RuleViolationReport(ruleViolations = setOf(OpenApiRuleViolation.STATUS_MISMATCH)),
                                )
                            )
                        )
                    )
                )
            ),
            ruleViolationReport = RuleViolationReport(ruleViolations = setOf(OpenApiRuleViolation.STATUS_MISMATCH)),
            contractPath = "something"
        )

        val resultWithoutRuleViolation = result.withoutRuleViolation()


        assertThat(resultWithoutRuleViolation).isEqualTo(
            Result.Failure(
                causes = listOf(
                    Result.FailureCause(
                        cause = Result.Failure(
                            ruleViolationReport = null,
                            causes = listOf(
                                Result.FailureCause(
                                    cause = Result.Failure(ruleViolationReport = null)
                                )
                            )
                        )
                    )
                ),
                ruleViolationReport = null,
                contractPath = "something"
            )
        )
    }

    @Test
    fun `mapSourceLocations rewrites file paths across the failure tree and via chains`() {
        val leaf = Result.Failure(
            message = "type mismatch",
            ruleViolation = StandardRuleViolation.TYPE_MISMATCH,
        ).breadCrumb(
            "name",
            SourceLocation(
                filePath = "/repo/common.yaml", line = 38, column = 9, pointer = "",
                via = listOf(SourceLocation(filePath = "/repo/api.yaml", line = 27, column = 13, pointer = "")),
            ),
        ).breadCrumb("BODY").breadCrumb("REQUEST")

        val relativized = leaf.mapSourceLocations { it.removePrefix("/repo/") }

        val location = relativized.toMatchFailureDetailList().single().sourceLocation!!
        assertThat(location.filePath).isEqualTo("common.yaml")
        assertThat(location.via.single().filePath).isEqualTo("api.yaml")
        // original tree is untouched
        assertThat(leaf.toMatchFailureDetailList().single().sourceLocation!!.filePath).isEqualTo("/repo/common.yaml")
    }
}
