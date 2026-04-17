package io.specmatic.test.reports.coverage

import io.specmatic.core.report.OpenApiCoverageReportOperation
import io.specmatic.core.utilities.Reasoning
import io.specmatic.reporter.ctrf.model.CoverageReportOperation
import io.specmatic.reporter.ctrf.model.CtrfOperationMetrics
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.ctrf.model.CtrfOperationQualifiers
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.test.TestResultRecord
import io.specmatic.test.TestSkipReason

data class OpenApiCoverageFacts(
    val matchCount: Int,
    val operation: OpenAPIOperation,
    val tests: List<TestResultRecord>,
    val qualifiers: List<CtrfOperationQualifiers>
)

class CoverageReportGenerator {
    fun generateReportOperations(context: CoverageContext): List<OpenApiCoverageReportOperation> {
        val filteredTestResultRecords = context.tests
        val specOperations = context.specOperations()
        val allCoverageOperations = context.allCoverageOperations()

        return allCoverageOperations.map { operation ->
            val facts = factsFor(operation, filteredTestResultRecords)
            val coverageStatus = coverageStatusFor(operation, facts, specOperations)
            val skipReasons = context.getSkipDecisionsFor(operation)

            CoverageReportOperation(
                tests = facts.tests,
                operation = operation,
                qualifiers = facts.qualifiers,
                coverageStatus = coverageStatus,
                reasons = skipReasons.flatMap { it.toCtrfSnapshots() },
                eligibleForCoverage = isEligibleForCoverage(coverageStatus, skipReasons),
                metrics = CtrfOperationMetrics(matches = facts.matchCount, attempts = facts.tests.size),
                specConfig = specConfigFor(operation, coverageStatus, facts.tests, context),
            )
        }
    }

    private fun factsFor(operation: OpenAPIOperation, testResultRecords: List<TestResultRecord>): OpenApiCoverageFacts {
        val tests = testResultRecords.filter { it.operations.contains(operation) }
        return OpenApiCoverageFacts(
            tests = tests,
            operation = operation,
            matchCount = tests.count { it.matchesResponseIdentifiers },
            qualifiers = tests.flatMap { it.operationQualifiers() }.distinct()
        )
    }

    private fun isEligibleForCoverage(coverageStatus: CoverageStatus, reasons: List<Reasoning>): Boolean {
        if (coverageStatus == CoverageStatus.MISSING_IN_SPEC) return false
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

        return coverageStatusFor(operation = operation, attempts = facts.tests.size, matches = facts.matchCount)
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

    private fun specConfigFor(
        operation: OpenAPIOperation,
        coverageStatus: CoverageStatus,
        attemptRecords: List<TestResultRecord>,
        context: CoverageContext,
    ): CtrfSpecConfig {
        exactMatchingEndpoint(operation, context.allSpecEndpoints)?.let { return it.toCtrfSpecConfig() }
        attemptRecords.firstNotNullOfOrNull { it.toCtrfSpecConfigOrNull() }?.let { return it }

        if (coverageStatus == CoverageStatus.MISSING_IN_SPEC) {
            closestMatchingEndpointFor(operation.path, operation.method, context.allSpecEndpoints)
                ?.toCtrfSpecConfig()
                ?.let { return it }
        }

        throw IllegalArgumentException("Cannot determine spec config for $operation")
    }

    private fun exactMatchingEndpoint(
        operation: OpenAPIOperation,
        endpoints: List<Endpoint>,
    ): Endpoint? {
        return endpoints.firstOrNull { endpoint ->
            endpoint.toOpenApiOperation() == operation
        }
    }
}
