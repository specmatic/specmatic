package io.specmatic.test.reports

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Scenario
import io.specmatic.core.TestResult
import io.specmatic.core.log.HttpLogMessage
import io.specmatic.test.API
import io.specmatic.test.TestResultRecord
import io.specmatic.test.reports.coverage.Endpoint

data class TestExecutionResult(
    val name: String,
    val details: String,
    val scenario: Scenario,
    val testResult: TestResult,
    val valid: Boolean,
    val wip: Boolean,
    val request: HttpRequest,
    val requestTime: Long,
    val response: HttpResponse?,
    val responseTime: Long?
)

interface TestReportListener {
    fun onActuator(enabled: Boolean)
    fun onActuatorApis(apis: List<API>)
    fun onEndpointApis(apis: List<Endpoint>)
    fun onTestResult(result: TestExecutionResult)
    fun onTestsComplete()
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
    val httpLogMessage = testHttpLogMessages.find { it.scenario == testResultRecord.scenarioResult?.scenario }
    if (httpLogMessage == null) return
    val testExecutionResult = TestExecutionResult(
        name = getTestName(testResultRecord, httpLogMessage),
        scenario = httpLogMessage.scenario!!,
        testResult = testResultRecord.result,
        valid = testResultRecord.isValid,
        wip = testResultRecord.isWip,
        request = httpLogMessage.request,
        requestTime = httpLogMessage.requestTime.toEpochMillis(),
        response = httpLogMessage.response,
        responseTime = httpLogMessage.responseTime?.toEpochMillis(),
        details = testResultRecord.scenarioResult?.reportString() ?: "No details found for this test"
    )

    onEachListener { onTestResult(testExecutionResult) }
}

