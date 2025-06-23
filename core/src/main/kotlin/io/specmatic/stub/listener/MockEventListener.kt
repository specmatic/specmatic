package io.specmatic.stub.listener

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Scenario
import io.specmatic.core.TestResult
import io.specmatic.core.log.HttpLogMessage

interface MockEventListener {
    fun onRespond(data: MockEvent)
}

data class MockEvent (
    val name: String,
    val details: String,
    val request: HttpRequest,
    val requestTime: Long,
    val response: HttpResponse?,
    val responseTime: Long?,
    val scenario: Scenario?,
    val result: TestResult,
) {
    constructor(logMessage: HttpLogMessage) : this(
        name = logMessage.toName(),
        details = logMessage.toDetails(),
        request = logMessage.request,
        requestTime = logMessage.requestTime.toEpochMillis(),
        response = logMessage.response,
        responseTime = logMessage.responseTime?.toEpochMillis(),
        scenario = logMessage.scenario,
        result = logMessage.toResult()
    )
}

