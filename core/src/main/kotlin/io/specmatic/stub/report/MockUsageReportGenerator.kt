package io.specmatic.stub.report

import io.specmatic.reporter.ctrf.model.CoverageReportOperation
import io.specmatic.reporter.ctrf.model.CtrfOperationMetrics
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.internal.dto.coverage.OmittedStatus
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.test.TestResultRecord

data class MockUsageFacts(
    val operation: OpenAPIOperation,
    val tests: List<TestResultRecord>,
    val matchRecords: List<TestResultRecord>,
    val attemptRecords: List<TestResultRecord>,
)

class MockUsageReportGenerator {
    fun generate(context: MockUsageContext): List<CoverageReportOperation<OpenAPIOperation, TestResultRecord>> {
        val specOperations = context.specOperations()

        return context.allCoverageOperations().map { operation ->
            val facts = factsFor(operation, context.tests)
            val coverageStatus = coverageStatusFor(operation, facts, specOperations)

            CoverageReportOperation<OpenAPIOperation, TestResultRecord>(
                operation = operation,
                specConfig = specConfigFor(operation, coverageStatus, facts.attemptRecords, context),
                coverageStatus = coverageStatus,
                eligibleForCoverage = coverageStatus != CoverageStatus.MISSING_IN_SPEC,
                omittedStatus = omittedStatusFor(coverageStatus),
                tests = facts.tests,
                metrics = CtrfOperationMetrics(
                    attempts = facts.attemptRecords.size,
                    matches = facts.matchRecords.size,
                ),
            )
        }
    }

    private fun factsFor(operation: OpenAPIOperation, testResultRecords: List<TestResultRecord>): MockUsageFacts {
        val tests = testResultRecords.filter { it.operations.contains(operation) }

        return MockUsageFacts(
            operation = operation,
            tests = tests,
            matchRecords = tests.filter { it.matches(operation) },
            attemptRecords = tests,
        )
    }

    private fun coverageStatusFor(
        operation: OpenAPIOperation,
        facts: MockUsageFacts,
        specOperations: Set<OpenAPIOperation>,
    ): CoverageStatus {
        if (operation !in specOperations) {
            return CoverageStatus.MISSING_IN_SPEC
        }

        return coverageStatusFor(facts.attemptRecords.size, facts.matchRecords.size)
    }

    private fun coverageStatusFor(attempts: Int, matches: Int): CoverageStatus {
        return when {
            attempts > 0 && matches > 0 -> CoverageStatus.COVERED
            attempts > 0 && matches == 0 -> CoverageStatus.MISMATCH
            attempts == 0 && matches == 0 -> CoverageStatus.NOT_USED
            else -> throw IllegalArgumentException(
                "Cannot determine mock usage coverage status with attempts=$attempts and matches=$matches"
            )
        }
    }

    private fun omittedStatusFor(coverageStatus: CoverageStatus): OmittedStatus {
        return when (coverageStatus) {
            CoverageStatus.NOT_USED -> OmittedStatus.SKIPPED
            else -> OmittedStatus.NONE
        }
    }

    private fun specConfigFor(
        operation: OpenAPIOperation,
        coverageStatus: CoverageStatus,
        attemptRecords: List<TestResultRecord>,
        context: MockUsageContext,
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
        endpoints: List<StubEndpoint>,
    ): StubEndpoint? {
        return endpoints.firstOrNull { endpoint -> endpoint.toOpenApiOperation() == operation }
    }
}
