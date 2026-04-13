package io.specmatic.test.reports.coverage

import io.specmatic.reporter.ctrf.model.CoverageReportOperation
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.test.TestResultRecord

data class OpenApiCoverageFacts(
    val operation: OpenAPIOperation,
    val attemptRecords: List<TestResultRecord>,
    val matchRecords: List<TestResultRecord>,
)

class CoverageReportGenerator {
    fun generate(context: CoverageContext): List<CoverageReportOperation> {
        val filteredTestResultRecords = context.tests
        val specOperations = context.specOperations()
        val allCoverageOperations = context.allCoverageOperations()

        return allCoverageOperations.map { operation ->
            val facts = factsFor(operation, filteredTestResultRecords)
            val coverageStatus = coverageStatusFor(operation, facts, specOperations)

            CoverageReportOperation(
                operation = operation,
                specConfig = specConfigFor(operation, coverageStatus, facts.attemptRecords, context),
                tests = (facts.attemptRecords + facts.matchRecords).distinct(),
                coverageStatus = coverageStatus,
                eligibleForCoverage = coverageStatus != CoverageStatus.MISSING_IN_SPEC,
            )
        }
    }

    private fun factsFor(
        operation: OpenAPIOperation,
        testResultRecords: List<TestResultRecord>,
    ): OpenApiCoverageFacts {
        val attemptRecords = testResultRecords.filter { it.attempts(operation) }
        val matchRecords = testResultRecords.filter { it.matches(operation) }

        return OpenApiCoverageFacts(
            operation = operation,
            attemptRecords = attemptRecords,
            matchRecords = matchRecords,
        )
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
            attempts = facts.attemptRecords.size,
            matches = facts.matchRecords.size,
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

    private fun specConfigFor(
        operation: OpenAPIOperation,
        coverageStatus: CoverageStatus,
        attemptRecords: List<TestResultRecord>,
        context: CoverageContext,
    ): CtrfSpecConfig {
        exactMatchingEndpoint(operation, context.specEndpointsInScope)?.let { return it.toCtrfSpecConfig() }
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
