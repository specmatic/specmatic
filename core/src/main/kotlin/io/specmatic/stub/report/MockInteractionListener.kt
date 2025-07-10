package io.specmatic.stub.report

import io.specmatic.core.TestResult
import io.specmatic.stub.listener.MockEvent

interface MockInteractionListener {
    fun onMockInteraction(mockInteractionData: MockInteractionData)
}

fun MockEvent.toMockInteractionData(
    baseUrl: String,
    stubType: String = "unknown",
    responseSource: String = "",
    stubToken: String? = null,
    delayInMilliseconds: Long = 0
): MockInteractionData {
    val duration = (responseTime ?: requestTime) - requestTime
    
    return MockInteractionData(
        name = name,
        baseUrl = baseUrl,
        duration = duration,
        valid = result == TestResult.Success,
        request = request.toLogString(),
        requestTime = requestTime,
        response = response?.toLogString() ?: "No response",
        responseTime = responseTime ?: requestTime,
        specFileName = scenario?.specification ?: "",
        details = details,
        stubType = stubType,
        method = request.method ?: "",
        path = request.path ?: "",
        responseCode = response?.status ?: 0,
        responseSource = responseSource,
        stubToken = stubToken,
        delayInMilliseconds = delayInMilliseconds,
        sourceProvider = scenario?.sourceProvider ?: "",
        sourceRepository = scenario?.sourceRepository ?: "",
        sourceRepositoryBranch = scenario?.sourceRepositoryBranch ?: "",
        serviceType = scenario?.serviceType ?: ""
    )
}