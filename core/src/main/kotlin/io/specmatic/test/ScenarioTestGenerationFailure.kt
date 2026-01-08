package io.specmatic.test

import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.core.HttpRequest
import io.specmatic.core.Result
import io.specmatic.core.Scenario
import io.specmatic.core.log.LogMessage
import io.specmatic.core.log.logger
import io.specmatic.license.core.SpecmaticProtocol

class ScenarioTestGenerationFailure(
    var scenario: Scenario,
    val failure: Result.Failure,
    val message: String,
    override val protocol: SpecmaticProtocol?,
) : ContractTest {
    init {
        val exampleRow = scenario.examples.flatMap { it.rows }.firstOrNull { it.name == message }
        if (exampleRow != null) {
            scenario = scenario.copy(exampleRow = exampleRow, exampleName = message)
        }
    }

    private val httpRequest: HttpRequest = scenario.exampleRow?.requestExample ?: HttpRequest(path = scenario.path, method = scenario.method)
    private val failureCause: Result.Failure = if (scenario.exampleRow != null && failure.cause != null) failure.cause else failure

    override fun toScenarioMetadata() = scenario.toScenarioMetadata()

    override fun testResultRecord(executionResult: ContractTestExecutionResult): TestResultRecord {
        val (result, request, response) = executionResult
        return TestResultRecord(
            path = convertPathParameterStyle(scenario.path),
            method = scenario.method,
            requestContentType = scenario.requestContentType,
            responseStatus = scenario.status,
            request = request,
            response = response,
            result = result.testResult(),
            sourceProvider = scenario.sourceProvider,
            repository = scenario.sourceRepository,
            branch = scenario.sourceRepositoryBranch,
            specification = scenario.specification,
            protocol = scenario.protocol,
            actualResponseStatus = 0,
            scenarioResult = result,
            soapAction = scenario.httpRequestPattern.getSOAPAction().takeIf { scenario.isGherkinScenario },
            isGherkin = scenario.isGherkinScenario
        )
    }

    override fun testDescription(): String {
        return scenario.testDescription()
    }

    override fun runTest(testBaseURL: String, timeoutInMilliseconds: Long): ContractTestExecutionResult {
        val log: (LogMessage) -> Unit = { logMessage -> logger.log(logMessage) }
        val httpClient = LegacyHttpClient(testBaseURL, log = log, timeoutInMilliseconds = timeoutInMilliseconds)
        return runTest(httpClient)
    }

    override fun runTest(testExecutor: TestExecutor): ContractTestExecutionResult {
        testExecutor.preExecuteScenario(scenario, httpRequest)
        return ContractTestExecutionResult(
            result = failureCause.updateScenario(scenario)
        )
    }

    override fun plusValidator(validator: ResponseValidator): ContractTest {
        return this
    }

}
