package io.specmatic.stub.report

import io.specmatic.reporter.ctrf.model.CoverageReportOperation
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.test.TestResultRecord
import kotlin.math.roundToInt

class OpenApiMockUsage(
    private val mockUsageReportGenerator: MockUsageReportGenerator = MockUsageReportGenerator(),
) {
    private val testResultRecords: MutableList<TestResultRecord> = mutableListOf()
    private val allSpecEndpoints: MutableList<StubEndpoint> = mutableListOf()

    fun addTestResultRecord(testResultRecord: TestResultRecord) {
        testResultRecords.add(testResultRecord)
    }

    fun addEndpoints(endpoints: List<StubEndpoint>) {
        allSpecEndpoints.addAll(endpoints)
    }

    fun mockUsageContext(): MockUsageContext {
        return MockUsageContext(
            tests = testResultRecords.toList(),
            allSpecEndpoints = allSpecEndpoints.toList(),
        )
    }

    fun generate(): List<CoverageReportOperation<OpenAPIOperation, TestResultRecord>> {
        return mockUsageReportGenerator.generate(mockUsageContext())
    }

    fun testResultRecords(): List<TestResultRecord> {
        return mockUsageContext().tests
    }

    fun ctrfSpecConfigs(): List<CtrfSpecConfig> {
        return generate().map { it.specConfig }.distinct()
    }

    fun totalCoveragePercentage(): Int {
        val coverageReportOperations = generate().filter { it.eligibleForCoverage }
        if (coverageReportOperations.isEmpty()) {
            return 0
        }

        val coveredOperationCount = coverageReportOperations.count { it.coverageStatus == CoverageStatus.COVERED }
        return ((coveredOperationCount.toDouble() / coverageReportOperations.size) * 100).roundToInt()
    }
}
