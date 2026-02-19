package io.specmatic.stub.listener

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Result
import io.specmatic.core.Scenario
import io.specmatic.core.log.HttpLogMessage
import io.specmatic.reporter.model.TestResult
import io.specmatic.stub.InterceptorResult

interface MockEventListener {
    fun onRespond(data: MockEvent)
}

data class MockEvent (
    val name: String,
    val details: String,
    val result: Result,
    val preHookRequestTime: Long? = null,
    val preHookRequest: InterceptorResult<HttpRequest>? = null,
    val request: HttpRequest,
    val requestTime: Long,
    val response: HttpResponse?,
    val responseTime: Long?,
    val postHookResponse: InterceptorResult<HttpResponse>? = null,
    val postHookResponseTime: Long? = null,
    val scenario: Scenario?,
    val stubResult: TestResult,
) {
    constructor(logMessage: HttpLogMessage) : this(
        name = logMessage.toName(),
        details = logMessage.toDetails(),
        result = logMessage.result ?: if (logMessage.toResult() == TestResult.Success) {
            Result.Success()
        } else {
            Result.Failure("No failure details found for this stub event")
        },
        preHookRequestTime = logMessage.preHookRequestTime?.toEpochMillis(),
        preHookRequest = logMessage.preHookRequest,
        request = logMessage.request,
        requestTime = logMessage.requestTime.toEpochMillis(),
        response = logMessage.response,
        responseTime = logMessage.responseTime?.toEpochMillis(),
        postHookResponse = logMessage.postHookResponse,
        postHookResponseTime = logMessage.postHookResponseTime?.toEpochMillis(),
        scenario = logMessage.scenario,
        stubResult = logMessage.toResult()
    )
}
