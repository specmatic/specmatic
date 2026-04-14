package io.specmatic.test.reports.coverage.console

import io.specmatic.test.TestResultRecord
import io.specmatic.test.countsAsCoveredForApiCoverage
import io.specmatic.test.isPresentInSpecForApiCoverage
import io.specmatic.test.reports.TestReportListener
import io.specmatic.test.reports.onEachListener
import kotlin.math.roundToInt

// GroupedBy Path -> soapAction ?: method -> RequestContentType -> ResponseStatusCode
typealias GroupedTestResultRecords = Map<String, Map<String, Map<String?, Map<String, List<TestResultRecord>>>>>
typealias GroupedCoverageRows = Map<String, Map<String, Map<String?, Map<String, List<OpenApiCoverageConsoleRow>>>>>

data class OpenAPICoverageConsoleReport(
    val coverageRows: List<OpenApiCoverageConsoleRow>,
    val testResultRecords: List<TestResultRecord>,
    val totalOperations: Int,
    val missedOperations: Int,
    val notImplementedOperations: Int,
    val coverageHooks: List<TestReportListener> = emptyList(),
    val totalCoveragePercentage: Int = calculateTotalCoveragePercentage(totalOperations, coverageRows)
) {
    val isGherkinReport = testResultRecords.isNotEmpty() && testResultRecords.all { it.isGherkin }

    init {
        coverageHooks.onEachListener { onCoverageCalculated(totalCoveragePercentage) }
    }

    fun getGroupedTestResultRecords(testResultRecords: List<TestResultRecord>): GroupedTestResultRecords {
        return testResultRecords.groupRecords()
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

    companion object {
        private fun calculateTotalCoveragePercentage(totalOperations: Int, coverageRows: List<OpenApiCoverageConsoleRow>): Int {
            if (totalOperations == 0) return 0
            val countOfOperationsPresentInSpec = coverageRows.count { it.remarks.isPresentInSpecForApiCoverage() }
            if (countOfOperationsPresentInSpec == 0) return 0
            val countOfOperationsHitThatArePresentInSpec = coverageRows.count { it.count.toInt() > 0 && it.remarks.countsAsCoveredForApiCoverage() }
            return ((countOfOperationsHitThatArePresentInSpec * 100) / countOfOperationsPresentInSpec.toDouble()).roundToInt()
        }
    }
}

fun List<TestResultRecord>.groupRecords(): GroupedTestResultRecords {
    return groupBy { it.path }.mapValues { (_, pathMap) ->
        pathMap.groupBy { it.soapAction ?: it.method }.mapValues { (_, methodMap) ->
            methodMap.groupBy { it.requestContentType }.mapValues { (_, contentTypeMap) ->
                contentTypeMap.groupBy { it.responseStatus.toString() }
            }
        }
    }
}
