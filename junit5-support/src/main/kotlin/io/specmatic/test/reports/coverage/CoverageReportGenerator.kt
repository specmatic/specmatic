package io.specmatic.test.reports.coverage

import io.specmatic.core.report.OpenApiCoverageReportOperation
import io.specmatic.reporter.ctrf.model.CoverageReportOperation
import io.specmatic.reporter.ctrf.model.CtrfOperationMetrics
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.ctrf.model.CtrfTestQualifiers
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.test.TestResultRecord
import io.specmatic.test.reports.coverage.console.OpenAPICoverageConsoleReport

data class OpenApiCoverageFacts(
    val matchCount: Int,
    val operation: OpenAPIOperation,
    val tests: List<TestResultRecord>,
    val qualifiers: List<CtrfTestQualifiers>
)

class CoverageReportGenerator {
    fun generate(context: CoverageContext): OpenAPICoverageConsoleReport {
        val reportOperations = generateReportOperations(context)
        return OpenAPICoverageConsoleReport(
            testResultRecords = context.tests,
            totalOperations = reportOperations.size,
            coverageRows = reportOperations.toConsoleRows(),
            totalCoveragePercentage = reportOperations.calculateCoverage(),
            missedOperations = reportOperations.count { it.coverageStatus == CoverageStatus.MISSING_IN_SPEC },
            notImplementedOperations = reportOperations.count { it.coverageStatus == CoverageStatus.NOT_IMPLEMENTED },
        )
    }

    fun generateReportOperations(context: CoverageContext): List<OpenApiCoverageReportOperation> {
        val filteredTestResultRecords = context.tests
        val specOperations = context.specOperations()
        val allCoverageOperations = context.allCoverageOperations()

        return allCoverageOperations.map { operation ->
            val facts = factsFor(operation, filteredTestResultRecords)
            val coverageStatus = coverageStatusFor(operation, facts, specOperations)
            CoverageReportOperation(
                tests = facts.tests,
                operation = operation,
                qualifiers = facts.qualifiers,
                coverageStatus = coverageStatus,
                eligibleForCoverage = coverageStatus != CoverageStatus.MISSING_IN_SPEC,
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
            matchCount = tests.count { it.matchesResponseIdentifiers() },
            qualifiers = tests.flatMap { it.qualifiers() }.distinct()
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
