package io.specmatic.test.reports.renderers

import io.specmatic.core.ReportFormatter
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.log.HttpLogMessage
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.Flags
import io.specmatic.test.SpecmaticJUnitSupport
import io.specmatic.test.SpecmaticJUnitSupport.Companion.HOST
import io.specmatic.test.SpecmaticJUnitSupport.Companion.PORT
import io.specmatic.test.SpecmaticJUnitSupport.Companion.TEST_BASE_URL
import io.specmatic.test.TestInteractionsLog.displayName
import io.specmatic.test.TestInteractionsLog.duration
import io.specmatic.test.TestResultRecord
import io.specmatic.test.reports.coverage.console.OpenAPICoverageConsoleReport
import io.specmatic.test.reports.coverage.console.OpenApiCoverageConsoleRow
import io.specmatic.test.reports.coverage.html.*
import java.util.*

class CoverageReportHtmlRenderer : ReportRenderer<OpenAPICoverageConsoleReport> {

    companion object {
        private val tableConfig = HtmlTableConfig(
            firstGroupName = "Path",
            firstGroupColSpan = 2,
            secondGroupName = "Method",
            secondGroupColSpan = 1,
            thirdGroupName = "Response",
            thirdGroupColSpan = 1
        )
        val actuatorEnabled = SpecmaticJUnitSupport.openApiCoverageReportInput.endpointsAPISet
    }

    override fun render(report: OpenAPICoverageConsoleReport, specmaticConfig: SpecmaticConfig): String {
        logger.log("Generating HTML report...")
        val reportConfiguration = specmaticConfig.getReport()!!
        val htmlReportConfiguration = reportConfiguration.getHTMLFormatter()!!
        val openApiSuccessCriteria = reportConfiguration.getSuccessCriteria()

        val reportData = HtmlReportData(
            totalCoveragePercentage = report.totalCoveragePercentage, actuatorEnabled = actuatorEnabled,
            tableRows = makeTableRows(report, htmlReportConfiguration),
            scenarioData = makeScenarioData(report), totalTestDuration = getTotalDuration(report)
        )

        val htmlReportInformation = HtmlReportInformation(
            reportFormat = htmlReportConfiguration, successCriteria = openApiSuccessCriteria,
            specmaticImplementation = "OpenAPI", specmaticVersion = getSpecmaticVersion(),
            tableConfig = tableConfig, reportData = reportData, specmaticConfig = specmaticConfig
        )

        HtmlReport(htmlReportInformation).generate()
        return "Successfully generated HTML report in ${htmlReportConfiguration.getOutputDirectoryOrDefault()}"
    }

    private fun getSpecmaticVersion(): String {
        val props = Properties()
        CoverageReportHtmlRenderer::class.java.classLoader.getResourceAsStream("version.properties").use {
            props.load(it)
        }
        return props.getProperty("version")
    }

    private fun makeTableRows(
        report: OpenAPICoverageConsoleReport,
        htmlReportConfiguration: ReportFormatter
    ): List<TableRow> {
        val updatedCoverageRows = when (htmlReportConfiguration.getLiteOrDefault()) {
            true -> reCreateCoverageRowsForLite(report, report.coverageRows)
            else -> report.coverageRows
        }

        return report.getGroupedCoverageRows(updatedCoverageRows).flatMap { (_, methodGroup) ->
            methodGroup.flatMap { (_, statusGroup) ->
                statusGroup.flatMap { (_, coverageRows) ->
                    coverageRows.map {
                        TableRow(
                            coveragePercentage = it.coveragePercentage,
                            firstGroupValue = it.path,
                            showFirstGroup = it.showPath,
                            firstGroupRowSpan = methodGroup.values.sumOf { rows -> rows.size },
                            secondGroupValue = it.method,
                            showSecondGroup = it.showMethod,
                            secondGroupRowSpan = statusGroup.values.sumOf { status -> status.size },
                            response = it.responseStatus,
                            exercised = it.count.toInt(),
                            result = it.remarks
                        )
                    }
                }
            }
        }
    }

    private fun getTotalDuration(report: OpenAPICoverageConsoleReport): Long {
        return report.httpLogMessages.sumOf { it.duration() }
    }

    private fun makeScenarioData(report: OpenAPICoverageConsoleReport): Map<String, Map<String, Map<String, List<ScenarioData>>>> {
        val testData: MutableMap<String, MutableMap<String, MutableMap<String, MutableList<ScenarioData>>>> = mutableMapOf()

        for ((path, methodGroup) in report.getGroupedTestResultRecords(report.testResultRecords)) {
            for ((method, statusGroup) in methodGroup) {
                val methodMap = testData.getOrPut(path) { mutableMapOf() }

                for ((status, testResults) in statusGroup) {
                    val statusMap = methodMap.getOrPut(method) { mutableMapOf() }
                    val scenarioDataList = statusMap.getOrPut(status) { mutableListOf() }

                    for (test in testResults) {
                        val matchingLogMessage = report.httpLogMessages.firstOrNull {
                            it.scenario == test.scenarioResult?.scenario
                        }
                        val scenarioName = getTestName(test, matchingLogMessage)
                        val (requestString, requestTime) = getRequestString(test, matchingLogMessage)
                        val (responseString, responseTime) = getResponseString(test, matchingLogMessage)

                        scenarioDataList.add(
                            ScenarioData(
                                name = scenarioName,
                                baseUrl = getBaseUrl(test, matchingLogMessage),
                                duration = matchingLogMessage?.duration() ?: 0,
                                testResult = test.result,
                                valid = test.isValid,
                                wip = test.isWip,
                                request = requestString,
                                requestTime = requestTime,
                                response = responseString,
                                responseTime = responseTime,
                                specFileName = getSpecFileName(test, matchingLogMessage),
                                details = getReportDetail(test)
                            )
                        )
                    }
                }
            }
        }

        return testData
    }

    private fun getTestName(testResult: TestResultRecord, httpLogMessage: HttpLogMessage?): String {
        return httpLogMessage?.displayName() ?: testResult.scenarioResult?.scenario?.testDescription() ?: "Scenario: ${testResult.path} -> ${testResult.responseStatus}"
    }

    private fun getBaseUrl(testResult: TestResultRecord, httpLogMessage: HttpLogMessage?): String {
        val host = Flags.getStringValue(HOST).orEmpty()
        val port = Flags.getStringValue(PORT).orEmpty()
        val baseUrlFromFlags = Flags.getStringValue(TEST_BASE_URL) ?: if (host.isNotBlank() && port.isNotBlank()) "$host:$port" else null
        return httpLogMessage?.targetServer ?: baseUrlFromFlags ?: "Unknown baseURL"
    }

    private fun getRequestString(testResult: TestResultRecord, httpLogMessage: HttpLogMessage?): Pair<String, Long> {
        return Pair(
            httpLogMessage?.request?.toLogString() ?: "No Request",
            httpLogMessage?.requestTime?.toEpochMillis() ?: 0
        )
    }

    private fun getResponseString(testResult: TestResultRecord, httpLogMessage: HttpLogMessage?): Pair<String, Long> {
        return Pair(
            httpLogMessage?.response?.toLogString() ?: "No Response",
            httpLogMessage?.responseTime?.toEpochMillis() ?: 0
        )
    }

    private fun getSpecFileName(testResult: TestResultRecord, httpLogMessage: HttpLogMessage?): String {
        return testResult.specification ?: httpLogMessage?.scenario?.specification ?: "Unknown Spec File"
    }

    private fun getReportDetail(testResult: TestResultRecord): String {
        return testResult.scenarioResult?.reportString() ?: ""
    }

    private fun reCreateCoverageRowsForLite(report: OpenAPICoverageConsoleReport, coverageRows: List<OpenApiCoverageConsoleRow>): List<OpenApiCoverageConsoleRow> {
        val exercisedRows = coverageRows.filter { it.count.toInt() > 0 }
        val updatedRows = mutableListOf<OpenApiCoverageConsoleRow>()

        report.getGroupedCoverageRows(exercisedRows).forEach { (_, methodGroup) ->
            val rowGroup = mutableListOf<OpenApiCoverageConsoleRow>()

            methodGroup.forEach { (method, statusGroup) ->
                statusGroup.forEach { (_, coverageRows) ->
                    coverageRows.forEach {
                        if (rowGroup.isEmpty()) {
                            rowGroup.add(it.copy(showPath = true, showMethod = true))
                        } else {
                            val methodExists = rowGroup.any {row ->  row.method == method }
                            rowGroup.add(it.copy(showPath = false, showMethod = !methodExists))
                        }
                    }
                }
            }

            updatedRows.addAll(rowGroup)
        }

        return updatedRows
    }
}