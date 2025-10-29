package io.specmatic.test.handlers

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Result
import io.specmatic.core.Scenario
import io.specmatic.test.TestExecutor

sealed interface ResponseHandlingResult {
    data class Continue(val response: HttpResponse) : ResponseHandlingResult
    data class Stop(val result: Result, val response: HttpResponse? = null) : ResponseHandlingResult
}

interface ResponseHandler {
    fun canHandle(response: HttpResponse, scenario: Scenario): Boolean
    fun handle(request: HttpRequest, response: HttpResponse, testScenario: Scenario, testExecutor: TestExecutor): ResponseHandlingResult
}
