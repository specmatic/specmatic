package io.specmatic.test.reports.coverage.console

import io.specmatic.test.TestInteractionsLog
import io.specmatic.test.TestResultRecord
import io.specmatic.test.reports.TestReportHooks
import kotlin.math.roundToInt

// GroupedBy Path -> soapAction ?: method -> RequestContentType -> ResponseStatusCode
typealias GroupedTestResultRecords = Map<String, Map<String, Map<String?, Map<String, List<TestResultRecord>>>>>
typealias GroupedCoverageRows = Map<String, Map<String, Map<String?, Map<String, List<OpenApiCoverageConsoleRow>>>>>

data class OpenAPICoverageConsoleReport(
    val coverageRows: List<OpenApiCoverageConsoleRow>,
    val testResultRecords: List<TestResultRecord>,
    val totalEndpointsCount: Int,
    val missedEndpointsCount: Int,
    val notImplementedAPICount: Int,
    val partiallyMissedEndpointsCount: Int,
    val partiallyNotImplementedAPICount: Int
) {
    val totalCoveragePercentage: Int = calculateTotalCoveragePercentage()
    val httpLogMessages = TestInteractionsLog.testHttpLogMessages
    val isGherkinReport = testResultRecords.all { it.isGherkin }

    init {
        TestReportHooks.onEachListener { onCoverageCalculated(totalCoveragePercentage) }
    }

    private fun calculateTotalCoveragePercentage(): Int {
        if (totalEndpointsCount == 0) return 0

        val totalCountOfEndpointsWithResponseStatuses = coverageRows.count()
        val totalCountOfCoveredEndpointsWithResponseStatuses = coverageRows.count { it.count.toInt() > 0 }

        return ((totalCountOfCoveredEndpointsWithResponseStatuses * 100) / totalCountOfEndpointsWithResponseStatuses).toDouble().roundToInt()
    }

    fun getGroupedTestResultRecords(testResultRecords: List<TestResultRecord>): GroupedTestResultRecords {
        return testResultRecords.groupBy { it.path }.mapValues { (_, pathMap) ->
            pathMap.groupBy { it.soapAction ?: it.method }.mapValues { (_, methodMap) ->
                methodMap.groupBy { it.requestContentType }.mapValues { (_, contentTypeMap) ->
                    contentTypeMap.groupBy { it.responseStatus.toString() }
                }
            }
        }
    }

    fun getGroupedCoverageRows(coverageRows: List<OpenApiCoverageConsoleRow>): GroupedCoverageRows {
        return coverageRows.groupBy { it.path }.mapValues { (_, pathMap) ->
            pathMap.groupBy { it.method }.mapValues { (_, methodMap) ->
                methodMap.groupBy { it.requestContentType }.mapValues { (_, contentTypeMap) ->
                    contentTypeMap.groupBy { it.responseStatus }
                }
            }
        }
    }
}