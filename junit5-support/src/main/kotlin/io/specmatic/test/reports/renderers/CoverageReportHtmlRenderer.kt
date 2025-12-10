package io.specmatic.test.reports.renderers

import io.specmatic.core.HttpRequest
import io.specmatic.core.log.HttpLogMessage
import io.specmatic.core.utilities.Flags
import io.specmatic.junit5.support.VersionInfo
import io.specmatic.test.SpecmaticJUnitSupport.Companion.HOST
import io.specmatic.test.SpecmaticJUnitSupport.Companion.PORT
import io.specmatic.test.SpecmaticJUnitSupport.Companion.TEST_BASE_URL
import io.specmatic.test.TestResultRecord
import io.specmatic.test.reports.coverage.OpenApiCoverageReportInput
import io.specmatic.test.reports.coverage.console.OpenAPICoverageConsoleReport
import io.specmatic.test.reports.coverage.console.OpenApiCoverageConsoleRow
import io.specmatic.test.reports.coverage.html.*

typealias GroupedScenarioData = Map<String, Map<String, Map<String, Map<String, List<ScenarioData>>>>>

class CoverageReportHtmlRenderer(private val openApiCoverageReportInput: OpenApiCoverageReportInput, val baseDir: String) : ReportRenderer<OpenAPICoverageConsoleReport> {
    val actuatorEnabled = openApiCoverageReportInput.endpointsAPISet


    private fun createTableConfig(report: OpenAPICoverageConsoleReport): HtmlTableConfig {
        return HtmlTableConfig(
            firstGroupName = "Path",
            firstGroupColSpan = 2,
            secondGroupName = if (report.isGherkinReport) "SoapAction" else "Method",
            secondGroupColSpan = if (report.isGherkinReport) 2 else 1,
            thirdGroupName = "Response",
            thirdGroupColSpan = 1
        )
    }

    private fun getSpecmaticVersion(): String {
        return VersionInfo.describe()
    }

    private fun getTotalDuration(): Long {
        return openApiCoverageReportInput.totalDuration()
    }

    private fun makeScenarioData(report: OpenAPICoverageConsoleReport): GroupedScenarioData {
        val testData: MutableMap<String, MutableMap<String, MutableMap<String, MutableMap<String, MutableList<ScenarioData>>>>> = mutableMapOf()

        for ((path, methodGroup) in report.getGroupedTestResultRecords(report.testResultRecords)) {
            for ((method, contentGroup) in methodGroup) {
                val methodMap = testData.getOrPut(path) { mutableMapOf() }
                for ((contentType, statusGroup) in contentGroup) {
                    val contentMap = methodMap.getOrPut(method) { mutableMapOf() }
                    for ((status, testResults) in statusGroup) {
                        val statusMap = contentMap.getOrPut(contentType.orEmpty()) { mutableMapOf() }
                        val scenarioDataList = statusMap.getOrPut(status) { mutableListOf() }

                        for (test in testResults) {
                            val matchingLogMessages = openApiCoverageReportInput.findMatchingScenarios { it.scenario == test.scenarioResult?.scenario }
                            val firstLogMessage = matchingLogMessages.firstOrNull()
                            val lastLogMessage = matchingLogMessages.lastOrNull()

                            val scenarioName = getTestName(test, firstLogMessage)
                            val (requestString, requestTime) = getRequestString(matchingLogMessages)
                            val (responseString, responseTime) = getResponseString(matchingLogMessages)
                            val firstLogMessageTime = firstLogMessage?.requestTime?.toEpochMillis() ?: 0
                            val lastLogMessageTime = lastLogMessage?.responseTime?.toEpochMillis() ?: lastLogMessage?.requestTime?.toEpochMillis() ?: 0

                            scenarioDataList.add(
                                ScenarioData(
                                    name = scenarioName,
                                    baseUrl = getBaseUrl(firstLogMessage),
                                    duration = lastLogMessageTime - firstLogMessageTime,
                                    testResult = test.result,
                                    valid = test.isValid,
                                    wip = test.isWip,
                                    request = requestString,
                                    requestTime = requestTime,
                                    response = responseString,
                                    responseTime = responseTime,
                                    specFileName = getSpecFileName(test, firstLogMessage),
                                    details = getReportDetail(test),
                                ),
                            )
                        }
                    }
                }
            }
        }

        return testData
    }

    private fun getTestName(testResult: TestResultRecord, httpLogMessage: HttpLogMessage?): String {
        return httpLogMessage?.displayName() ?: testResult.scenarioResult?.scenario?.testDescription() ?: "Scenario: ${testResult.path} -> ${testResult.responseStatus}"
    }

    private fun getBaseUrl(httpLogMessage: HttpLogMessage?): String {
        val host = Flags.getStringValue(HOST).orEmpty()
        val port = Flags.getStringValue(PORT).orEmpty()
        val baseUrlFromFlags = Flags.getStringValue(TEST_BASE_URL) ?: if (host.isNotBlank() && port.isNotBlank()) "$host:$port" else null
        return httpLogMessage?.targetServer ?: baseUrlFromFlags ?: "Unknown baseURL"
    }

    private fun getRequestString(httpLogMessage: List<HttpLogMessage>): Pair<String, Long> {
        val requests = httpLogMessage.map(HttpLogMessage::request).joinToString(separator = "---END BLOCK---", transform = HttpRequest::toLogString)
        return Pair(
            requests,
            httpLogMessage.firstOrNull()?.requestTime?.toEpochMillis() ?: 0,
        )
    }

    private fun getResponseString(httpLogMessage: List<HttpLogMessage>): Pair<String, Long> {
        val responses = httpLogMessage.map(HttpLogMessage::response).joinToString(separator = "---END BLOCK---") {
            it?.toLogString() ?: "No Response"
        }
        return Pair(
            responses,
            httpLogMessage.lastOrNull()?.responseTime?.toEpochMillis() ?: httpLogMessage.lastOrNull()?.requestTime?.toEpochMillis() ?: 0,
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

            methodGroup.forEach { (method, contentGroup) ->
                contentGroup.forEach { (_, statusGroup) ->
                    statusGroup.forEach { (_, coverageRows) ->
                        coverageRows.forEach {
                            if (rowGroup.isEmpty()) {
                                rowGroup.add(it.copy(showPath = true, showMethod = true))
                            } else {
                                val methodExists = rowGroup.any { row -> row.method == method }
                                rowGroup.add(it.copy(showPath = false, showMethod = !methodExists))
                            }
                        }
                    }
                }
            }

            updatedRows.addAll(rowGroup)
        }

        return updatedRows
    }
}
