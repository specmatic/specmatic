package io.specmatic.test

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Result
import io.specmatic.core.Scenario
import io.specmatic.core.filters.HasScenarioMetadata
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.ctrf.model.FixtureExecutionResult
import io.specmatic.reporter.model.SpecType

interface ResponseValidator {
    fun validate(scenario: Scenario, httpResponse: HttpResponse): Result? {
        return null
    }

    fun postValidate(scenario: Scenario, originalScenario: Scenario, httpRequest: HttpRequest, httpResponse: HttpResponse): Result? {
        return null
    }
}

interface ContractTest : HasScenarioMetadata {
    val protocol: SpecmaticProtocol?
    val specType: SpecType
    val scenario: Scenario

    fun testResultRecord(executionResult: ContractTestExecutionResult): TestResultRecord?
    fun testDescription(): String
    fun runTest(testBaseURL: String, timeoutInMilliseconds: Long): ContractTestExecutionResult
    fun runTest(testExecutor: TestExecutor): ContractTestExecutionResult
    fun plusValidator(validator: ResponseValidator): ContractTest

    companion object {
        internal fun updateBasedOnResponseIfNegativeGeneration(scenario: Scenario, httpResponse: HttpResponse?): Scenario {
            return scenario.updateBasedOnResponseIfNegativeGeneration(httpResponse ?: HttpResponse())
        }
    }
}

data class ContractTestExecutionResult(
    val result: Result,
    val request: HttpRequest? = null,
    val response: HttpResponse? = null,
    val beforeFixtureExecutionResult: List<FixtureExecutionResult>? = null,
    val afterFixtureExecutionResult: List<FixtureExecutionResult>? = null
)

data class FixtureExecutionDetails(
    val combinedResult: Result,
    val fixtureExecutionResults: List<FixtureExecutionResult>? = null
)

