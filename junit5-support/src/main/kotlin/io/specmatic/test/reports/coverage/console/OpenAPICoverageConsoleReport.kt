package io.specmatic.test.reports.coverage.console

import io.specmatic.test.TestResultRecord
import io.specmatic.test.reports.TestReportListener
import io.specmatic.test.reports.onEachListener

data class OpenAPICoverageConsoleReport(
    val coverageRows: List<OpenApiCoverageConsoleRow>,
    val testResultRecords: List<TestResultRecord>,
    val totalOperations: Int,
    val missedOperations: Int,
    val notImplementedOperations: Int,
    val coverageHooks: List<TestReportListener>,
    val totalCoveragePercentage: Int,
) {
    val isGherkinReport = testResultRecords.isNotEmpty() && testResultRecords.all { it.isGherkin }
    init {
        coverageHooks.onEachListener { onCoverageCalculated(totalCoveragePercentage) }
    }
}

