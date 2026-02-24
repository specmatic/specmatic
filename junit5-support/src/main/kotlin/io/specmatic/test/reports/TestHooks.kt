package io.specmatic.test.reports

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Result
import io.specmatic.core.Scenario
import io.specmatic.core.log.HttpLogMessage
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.API
import io.specmatic.test.TestResultRecord
import io.specmatic.test.reports.coverage.Endpoint

// TODO: Result is currently only applicable to the last response, no data for the rest
data class TestExecutionResult(
    val name: String,
    val result: Result,
    val scenario: Scenario,
    val testResult: TestResult,
    val wip: Boolean,
    val request: List<HttpRequest>,
    val requestTime: Long,
    val response: List<HttpResponse?>,
    val actualResponseStatus: Int,
    val responseTime: Long?
)

interface TestReportListener {
    fun onActuator(enabled: Boolean)
    fun onActuatorApis(apisNotExcluded: List<API>, apisExcluded: List<API>)
    fun onEndpointApis(endpointsNotExcluded: List<Endpoint>, endpointsExcluded: List<Endpoint>)
    fun onTestResult(result: TestExecutionResult)
    fun onTestsComplete()
    fun onEnd()
    fun onCoverageCalculated(coverage: Int)
    fun onPathCoverageCalculated(path: String, pathCoverage: Int)
}

internal fun List<TestReportListener>.onEachListener(block: TestReportListener.() -> Unit) {
    for (listener in this) {
        listener.block()
    }
}

internal fun getTestName(testResult: TestResultRecord, httpLogMessage: HttpLogMessage?): String {
    return httpLogMessage?.displayName() ?: testResult.scenarioResult?.scenario?.testDescription() ?: "Scenario: ${testResult.path} -> ${testResult.responseStatus}"
}

internal fun List<TestReportListener>.onTestResult(testResultRecord: TestResultRecord, testHttpLogMessages: List<HttpLogMessage>) {
    val httpLogMessages = testHttpLogMessages.filter { it.scenario == testResultRecord.scenarioResult?.scenario }
    if (httpLogMessages.isEmpty()) return
    val firstHttpLogMessage = httpLogMessages.first()
    val lastHttpLogMessage = httpLogMessages.last()
    val testExecutionResult = TestExecutionResult(
        name = getTestName(testResultRecord, firstHttpLogMessage),
        scenario = firstHttpLogMessage.scenario!!,
        testResult = testResultRecord.result,
        wip = testResultRecord.isWip,
        request = httpLogMessages.map(HttpLogMessage::request),
        requestTime = firstHttpLogMessage.requestTime.toEpochMillis(),
        response = httpLogMessages.map(HttpLogMessage::response),
        responseTime = lastHttpLogMessage.responseTime?.toEpochMillis(),
        actualResponseStatus = testResultRecord.actualResponseStatus,
        result = testResultRecord.scenarioResult ?: Result.Failure("No details found for this test"),
    )
    onEachListener { onTestResult(testExecutionResult) }
}
