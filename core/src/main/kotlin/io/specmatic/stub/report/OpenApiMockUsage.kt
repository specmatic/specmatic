package io.specmatic.stub.report

import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.report.calculateAbsoluteCoverage
import io.specmatic.core.report.calculateCoverage
import io.specmatic.core.report.ctrfSpecConfigsFrom
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.test.TestResultRecord

class OpenApiMockUsage(
    private val specmaticConfig: SpecmaticConfig,
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

    fun ctrfSpecConfigs(): List<CtrfSpecConfig> {
        return ctrfSpecConfigsFrom(specmaticConfig, mockUsageContext().tests)
    }
}
