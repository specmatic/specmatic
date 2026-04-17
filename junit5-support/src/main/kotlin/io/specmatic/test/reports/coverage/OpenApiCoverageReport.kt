package io.specmatic.test.reports.coverage

import io.specmatic.core.Result
import io.specmatic.core.log.HttpLogMessage
import io.specmatic.core.report.OpenApiCoverageReportOperation
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.internal.dto.coverage.CoverageStatus
import io.specmatic.test.API
import io.specmatic.test.HttpInteractionsLog
import io.specmatic.test.TestResultRecord
import io.specmatic.test.reports.TestReportListener
import io.specmatic.test.reports.coverage.console.OpenAPICoverageConsoleReport
import io.specmatic.test.reports.coverage.console.OpenApiCoverageConsoleRow
import io.specmatic.test.reports.onEachListener
import kotlin.math.roundToInt

data class OpenApiCoverageDeprecatedData(
    val endpointsApiSet: Boolean = false,
    val filterExpression: String = "",
    val excludedAPIs: List<String> = emptyList(),
    val applicationAPIs: List<API> = emptyList(),
    val allSpecEndpoints: List<Endpoint> = emptyList(),
)

data class OpenApiCoverageReport(
    val configFilePath: String,
    val testResultRecords: List<TestResultRecord> = emptyList(),
    val coverageOperations: List<OpenApiCoverageReportOperation> = emptyList(),
    val actuatorEnabled: Boolean = false,
    val deprecatedData: OpenApiCoverageDeprecatedData = OpenApiCoverageDeprecatedData(),
    private val coverageHooks: List<TestReportListener> = emptyList(),
    private val httpInteractionsLog: HttpInteractionsLog = HttpInteractionsLog(),
) {
    val totalCoveragePercentage: Int = coverageOperations.calculateCoverage()
    val absoluteCoveragePercentage: Int = coverageOperations.calculateAbsoluteCoverage()

    fun getSpecConfigs(): List<CtrfSpecConfig> {
        return coverageOperations.map { it.specConfig }.distinct()
    }

    fun onProcessingComplete() {
        coverageHooks.onEachListener { onEnd() }
    }

    fun onGovernanceResult(fromResults: Result) {
        coverageHooks.onEach { hook -> hook.onGovernance(fromResults) }
    }

    fun totalDuration(): Long {
        return httpInteractionsLog.totalDuration()
    }

    fun findMatchingScenarios(logMessageSelector: (HttpLogMessage) -> Boolean): List<HttpLogMessage> {
        return httpInteractionsLog.testHttpLogMessages.filter(logMessageSelector)
    }

    fun toConsoleReport(): OpenAPICoverageConsoleReport {
        return OpenAPICoverageConsoleReport(
            coverageHooks = coverageHooks,
            testResultRecords = testResultRecords,
            totalOperations = coverageOperations.size,
            operationsEligibleForCoverage = coverageOperations.count { it.eligibleForCoverage },
            coverageRows = coverageOperations.toConsoleRows(),
            coveragePercentage = coverageOperations.calculateCoverage(),
            absoluteCoveragePercentage = coverageOperations.calculateAbsoluteCoverage(),
            missedOperations = coverageOperations.count { it.coverageStatus == CoverageStatus.MISSING_IN_SPEC },
            notImplementedOperations = coverageOperations.count { it.coverageStatus == CoverageStatus.NOT_IMPLEMENTED },
        )
    }

    private fun List<OpenApiCoverageReportOperation>.toConsoleRows(): List<OpenApiCoverageConsoleRow> {
        val reportsByPath = this.groupBy(keySelector = { it.operation.path })
        val coverageByPath = reportsByPath.mapValues { (path, operations) ->
            val pathCoveragePercentage = operations.calculateCoverage()
            coverageHooks.onEachListener { onPathCoverageCalculated(path, pathCoveragePercentage) }
            pathCoveragePercentage
        }

        return reportsByPath.values.flatten().zipWithPrevious { previous, current ->
            val samePath = previous?.operation?.path == current.operation.path
            val sameMethod = samePath && previous.operation.method == current.operation.method
            val sameRequestContentType = sameMethod && previous.operation.contentType == current.operation.contentType
            OpenApiCoverageConsoleRow(
                coverageReportOperation = current,
                showPath = !samePath,
                showMethod = !sameMethod,
                showRequestContentType = !sameRequestContentType,
                coveragePercentage = coverageByPath.getOrDefault(current.operation.path, 0),
            )
        }
    }

    private fun List<OpenApiCoverageReportOperation>.calculateCoverage(): Int {
        val coverageReportOperations = this.filter { it.eligibleForCoverage }
        if (coverageReportOperations.isEmpty()) return 0
        val coveredOperationCount = coverageReportOperations.count { it.coverageStatus == CoverageStatus.COVERED }
        return ((coveredOperationCount.toDouble() / coverageReportOperations.size) * 100).roundToInt()
    }

    private fun List<OpenApiCoverageReportOperation>.calculateAbsoluteCoverage(): Int {
        val denominatorOperations = this.filter { operation ->
            operation.eligibleForCoverage || operation.excludedFromRun
        }
        if (denominatorOperations.isEmpty()) return 0

        val coveredOperationCount = denominatorOperations.count { operation ->
            operation.eligibleForCoverage && operation.coverageStatus == CoverageStatus.COVERED
        }

        return ((coveredOperationCount.toDouble() / denominatorOperations.size) * 100).roundToInt()
    }
}
