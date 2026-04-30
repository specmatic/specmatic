package io.specmatic.test.reports.coverage

import io.specmatic.core.report.specConfigFor
import io.specmatic.core.utilities.Reasoning
import io.specmatic.reporter.ctrf.model.CoverageReportOperation
import io.specmatic.reporter.ctrf.model.CtrfOperationMetrics
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.ctrf.model.CtrfOperationQualifiers
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.internal.dto.coverage.OmittedStatus
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.test.TestResultRecord
import io.specmatic.test.TestSkipReason

data class OpenApiCoverageFacts(
    val operation: OpenAPIOperation,
    val tests: List<TestResultRecord>,
    val metrics: CtrfOperationMetrics,
    val qualifiers: List<CtrfOperationQualifiers>
)

class CoverageReportGenerator {
    fun generateReportOperations(context: CoverageContext): List<OpenApiCoverageReportOperation> {
        val filteredTestResultRecords = context.tests
        val specOperations = context.specOperations()
        val allCoverageOperations = context.allCoverageOperations()

        return allCoverageOperations.map { operation ->
            val skipReasons = context.getSkipDecisionsFor(operation)
            val facts = factsFor(operation, filteredTestResultRecords, context)
            val coverageStatus = coverageStatusFor(operation, facts, specOperations)
            val operationInPreviousRunMetrics = context.hasPreviousRunMetricsFor(operation)

            CoverageReportOperation(
                tests = facts.tests,
                operation = operation,
                metrics = facts.metrics,
                qualifiers = facts.qualifiers,
                coverageStatus = coverageStatus,
                omittedStatus = omittedStatusFor(coverageStatus, skipReasons),
                reasons = skipReasons.flatMap { it.toCtrfSnapshots() },
                specConfig = specConfigFor(
                    operation = operation,
                    coverageStatus = coverageStatus,
                    attemptRecords = facts.tests,
                    allSpecEndpoints = context.allSpecEndpoints,
                ),
                eligibleForCoverage = isEligibleForCoverage(
                    reasons = skipReasons,
                    coverageStatus = coverageStatus,
                    operationInPreviousRunMetrics = operationInPreviousRunMetrics
                ),
            )
        }
    }

    private fun omittedStatusFor(coverageStatus: CoverageStatus, skipReasons: List<Reasoning>): OmittedStatus {
        if (coverageStatus != CoverageStatus.NOT_TESTED) return OmittedStatus.NONE
        if (skipReasons.any { it.hasReason(TestSkipReason.EXCLUDED) }) return OmittedStatus.EXCLUDED
        return OmittedStatus.SKIPPED
    }

    private fun factsFor(
        operation: OpenAPIOperation,
        testResultRecords: List<TestResultRecord>,
        context: CoverageContext
    ): OpenApiCoverageFacts {
        val factsBasedOnTests = factsBasedOnTestsFor(operation, testResultRecords)
        return accumulateWithPreviousRunMetrics(factsBasedOnTests, context.previousRunMetricsFor(operation))
    }

    private fun factsBasedOnTestsFor(
        operation: OpenAPIOperation,
        testResultRecords: List<TestResultRecord>
    ): OpenApiCoverageFacts {
        val tests = testResultRecords.filter { it.operations.contains(operation) }
        return OpenApiCoverageFacts(
            tests = tests,
            operation = operation,
            qualifiers = tests.flatMap { it.operationQualifiers() }.distinct(),
            metrics = CtrfOperationMetrics(
                attempts = tests.size,
                matches = tests.count { it.matchesResponseIdentifiers },
            ),
        )
    }

    private fun accumulateWithPreviousRunMetrics(
        facts: OpenApiCoverageFacts,
        previousRunMetrics: CtrfOperationMetrics?
    ): OpenApiCoverageFacts {
        if (previousRunMetrics == null) return facts
        return facts.copy(
            metrics = CtrfOperationMetrics(
                attempts = facts.metrics.attempts + previousRunMetrics.attempts,
                matches = facts.metrics.matches + previousRunMetrics.matches,
            )
        )
    }

    private fun isEligibleForCoverage(
        coverageStatus: CoverageStatus,
        reasons: List<Reasoning>,
        operationInPreviousRunMetrics: Boolean
    ): Boolean {
        if (coverageStatus == CoverageStatus.MISSING_IN_SPEC) return false
        if (operationInPreviousRunMetrics) return true
        return reasons.none { reasoning -> reasoning.hasReason(TestSkipReason.EXCLUDED) }
    }

    private fun coverageStatusFor(
        operation: OpenAPIOperation,
        facts: OpenApiCoverageFacts,
        specOperations: Set<OpenAPIOperation>,
    ): CoverageStatus {
        if (operation !in specOperations) {
            return CoverageStatus.MISSING_IN_SPEC
        }

        return coverageStatusFor(
            operation = operation,
            matches = facts.metrics.matches,
            attempts = facts.metrics.attempts,
        )
    }

    private fun coverageStatusFor(
        operation: OpenAPIOperation,
        attempts: Int,
        matches: Int,
    ): CoverageStatus {
        return when {
            attempts > 0 && matches > 0 -> CoverageStatus.COVERED
            attempts > 0 && matches == 0 -> CoverageStatus.NOT_IMPLEMENTED
            attempts == 0 && matches == 0 -> CoverageStatus.NOT_TESTED
            else -> throw IllegalArgumentException(
                "Cannot determine coverage status for $operation with attempts=$attempts and matches=$matches"
            )
        }
    }
}
