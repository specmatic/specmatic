package io.specmatic.stub.report

import io.specmatic.core.report.calculateAbsoluteCoverage
import io.specmatic.core.report.calculateCoverage
import io.specmatic.core.report.toCoverageReportSpecifications
import io.specmatic.reporter.ctrf.CoverageReportSpecification
import io.specmatic.reporter.ctrf.model.BaseCoverageReportOperation
import io.specmatic.test.TestResultRecord

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

    fun generate(): OpenApiMockUsageReport {
        val context = mockUsageContext()
        val coverageReportOperations = mockUsageReportGenerator.generate(context)
        return OpenApiMockUsageReport(
            coverageReportOperations = coverageReportOperations,
            testResultRecords = context.tests,
            coverage = coverageReportOperations.calculateCoverage(),
            absoluteCoverage = coverageReportOperations.calculateAbsoluteCoverage(),
        )
    }

    fun coverageReportSpecifications(coverageReportOperations: List<BaseCoverageReportOperation>): List<CoverageReportSpecification> {
        return coverageReportOperations.toCoverageReportSpecifications()
    }
}
