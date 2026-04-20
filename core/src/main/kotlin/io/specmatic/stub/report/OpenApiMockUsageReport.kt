package io.specmatic.stub.report

import io.specmatic.reporter.ctrf.model.CoverageReportOperation
import io.specmatic.reporter.model.OpenAPIOperation
import io.specmatic.test.TestResultRecord

data class OpenApiMockUsageReport(
    val coverageReportOperations: List<CoverageReportOperation<OpenAPIOperation, TestResultRecord>>,
    val testResultRecords: List<TestResultRecord>,
    val coverage: Int,
    val absoluteCoverage: Int,
)
