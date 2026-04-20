package io.specmatic.test.reports.coverage.console

import io.specmatic.test.TestResultRecord
import io.specmatic.test.reports.TestReportListener
import io.specmatic.test.reports.onEachListener

data class OpenAPICoverageConsoleReport(
    val coverageRows: List<OpenApiCoverageConsoleRow>,
    val testResultRecords: List<TestResultRecord>,
    val totalOperations: Int,
    val operationsEligibleForCoverage: Int,
    val missedOperations: Int,
    val notImplementedOperations: Int,
    val coverageHooks: List<TestReportListener>,
    val coveragePercentage: Int,
    val absoluteCoveragePercentage: Int,
) {
    val isGherkinReport = testResultRecords.isNotEmpty() && testResultRecords.all { it.isGherkin }
    init {
        coverageHooks.onEachListener {
            onCoverageCalculated(coveragePercentage, absoluteCoveragePercentage)
        }
    }
}
