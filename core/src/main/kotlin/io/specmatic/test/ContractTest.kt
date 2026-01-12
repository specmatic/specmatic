package io.specmatic.test

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Result
import io.specmatic.core.Scenario
import io.specmatic.core.filters.HasScenarioMetadata
import io.specmatic.license.core.SpecmaticProtocol

interface ResponseValidator {
    fun validate(scenario: Scenario, httpResponse: HttpResponse): Result? {
        return null
    }

    fun postValidate(scenario: Scenario, originalScenario: Scenario, httpRequest: HttpRequest, httpResponse: HttpResponse): Result? {
        return null
    }
}

interface ContractTest : HasScenarioMetadata {
    fun testResultRecord(executionResult: ContractTestExecutionResult): TestResultRecord?
    fun testDescription(): String
    fun runTest(testBaseURL: String, timeoutInMilliseconds: Long): ContractTestExecutionResult
    fun runTest(testExecutor: TestExecutor): ContractTestExecutionResult

    fun plusValidator(validator: ResponseValidator): ContractTest
    val protocol: SpecmaticProtocol?
}

data class ContractTestExecutionResult(
    val result: Result,
    val request: HttpRequest? = null,
    val response: HttpResponse? = null
)
