package io.specmatic.test.reports

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Result
import io.specmatic.core.Scenario
import io.specmatic.core.log.HttpLogMessage
import io.specmatic.core.utilities.Decision
import io.specmatic.test.API
import io.specmatic.test.ContractTest
import io.specmatic.test.TestResultRecord
import io.specmatic.test.reports.coverage.Endpoint

// TODO: Result is currently only applicable to the last response, no data for the rest
data class TestExecutionResult(
    val name: String,
    val result: Result,
    val scenario: Scenario,
    val testRecord: TestResultRecord,
    val request: List<HttpRequest>,
    val requestTime: Long,
    val response: List<HttpResponse?>,
    val responseTime: Long?
)

interface TestReportListener {
    fun onActuator(enabled: Boolean)
    fun onActuatorApis(apisNotExcluded: List<API>, apisExcluded: List<API>)
    fun onEndpointApis(endpointsNotExcluded: List<Endpoint>, endpointsExcluded: List<Endpoint>)
    fun onTestResult(result: TestExecutionResult)
    fun onExampleErrors(resultsBySpecFile: Map<String, Result>)
    fun onTestsComplete()
    fun onEnd()
    fun onCoverageCalculated(coverage: Int, absoluteCoverage: Int)
    fun onPathCoverageCalculated(path: String, pathCoverage: Int)
    fun onGovernance(result: Result)
    fun onTestDecision(decision: Decision<ContractTest, Scenario>)
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
    val scenarioAssociatedWithTest = testResultRecord.scenarioResult?.scenario as? Scenario
    val httpLogMessages = testHttpLogMessages.filter { it.scenario == scenarioAssociatedWithTest }
    if (httpLogMessages.isEmpty() && testResultRecord.beforeFixtureExecutionResult.isNullOrEmpty()) return

    val firstHttpLogMessage = httpLogMessages.firstOrNull()
    val scenario = scenarioAssociatedWithTest ?: return
    val lastHttpLogMessage = httpLogMessages.lastOrNull()

    val testExecutionResult = TestExecutionResult(
        testRecord = testResultRecord,
        scenario = scenario,
        name = getTestName(testResultRecord, firstHttpLogMessage),
        request = httpLogMessages.map(HttpLogMessage::request),
        requestTime = firstHttpLogMessage?.requestTime?.toEpochMillis() ?: 0L,
        response = httpLogMessages.map(HttpLogMessage::response),
        responseTime = lastHttpLogMessage?.responseTime?.toEpochMillis(),
        result = testResultRecord.scenarioResult ?: Result.Failure("No details found for this test"),
    )
    onEachListener { onTestResult(testExecutionResult) }
}
