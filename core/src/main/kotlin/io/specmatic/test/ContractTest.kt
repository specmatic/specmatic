package io.specmatic.test

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Result
import io.specmatic.core.Scenario
import io.specmatic.core.filters.HasScenarioMetadata

interface ResponseValidator {
    fun validate(scenario: Scenario, httpResponse: HttpResponse): Result? {
        return null
    }

    fun postValidate(scenario: Scenario, originalScenario: Scenario, httpRequest: HttpRequest, httpResponse: HttpResponse): Result? {
        return null
    }
}

interface ContractTest : HasScenarioMetadata {
    fun testResultRecord(result: Result, response: HttpResponse?): TestResultRecord?
    fun testDescription(): String
    fun runTest(testBaseURL: String, timeoutInMilliseconds: Long): Pair<Result, HttpResponse?>
    fun runTest(testExecutor: TestExecutor): Pair<Result, HttpResponse?>

    fun plusValidator(validator: ResponseValidator): ContractTest
}
