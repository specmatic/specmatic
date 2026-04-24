package io.specmatic.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ResultTest {

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
}