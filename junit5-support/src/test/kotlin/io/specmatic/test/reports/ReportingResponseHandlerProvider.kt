package io.specmatic.test.reports

import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Result
import io.specmatic.core.Scenario
import io.specmatic.test.TestExecutor
import io.specmatic.test.handlers.ResponseHandler
import io.specmatic.test.handlers.ResponseHandlerProvider
import io.specmatic.test.handlers.ResponseHandlingResult

class ReportingResponseHandlerProvider : ResponseHandlerProvider {
    override fun handlersFor(feature: Feature, originalScenario: Scenario): List<ResponseHandler> {
        return if (originalScenario.path == HANDLED_RESPONSE_PATH) listOf(ReportingResponseHandler()) else emptyList()
    }

    companion object {
        const val HANDLED_RESPONSE_PATH = "/handled-responses"
        const val TRANSIENT_RESPONSE_STATUS = 598
    }
}

private class ReportingResponseHandler : ResponseHandler {
    override fun canHandle(response: HttpResponse, scenario: Scenario): Boolean {
        return response.status == ReportingResponseHandlerProvider.TRANSIENT_RESPONSE_STATUS
    }

    override fun handle(
        request: HttpRequest,
        response: HttpResponse,
        testScenario: Scenario,
        testExecutor: TestExecutor,
    ): ResponseHandlingResult {
        return handleResponseSequence(request, response, testScenario, testExecutor, remainingAttempts = 3)
    }

    private tailrec fun handleResponseSequence(
        request: HttpRequest,
        response: HttpResponse,
        testScenario: Scenario,
        testExecutor: TestExecutor,
        remainingAttempts: Int,
    ): ResponseHandlingResult {
        if (response.status != ReportingResponseHandlerProvider.TRANSIENT_RESPONSE_STATUS) {
            return ResponseHandlingResult.Continue(
                response = response,
                responseForTestResultOverride = response,
            )
        }

        if (remainingAttempts == 0) {
            return ResponseHandlingResult.Stop(
                result = Result.Failure("Test response handling exhausted"),
                response = response,
            )
        }

        testExecutor.preExecuteScenario(testScenario, request)
        return handleResponseSequence(
            request = request,
            response = testExecutor.execute(request),
            testScenario = testScenario,
            testExecutor = testExecutor,
            remainingAttempts = remainingAttempts - 1,
        )
    }
}
