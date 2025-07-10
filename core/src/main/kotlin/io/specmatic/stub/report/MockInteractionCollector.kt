package io.specmatic.stub.report

import io.specmatic.stub.listener.MockEvent
import io.specmatic.stub.listener.MockEventListener

class MockInteractionCollector(
    private val baseUrl: String,
    private val mockInteractionListeners: List<MockInteractionListener> = emptyList()
) : MockEventListener {
    
    override fun onRespond(mockEvent: MockEvent) {
        val mockInteractionData = mockEvent.toMockInteractionData(
            baseUrl = baseUrl,
            stubType = determineStubType(mockEvent),
            responseSource = determineResponseSource(mockEvent),
            stubToken = null, // This would need to be passed from HttpStubResponse if available
            delayInMilliseconds = 0 // This would need to be passed from HttpStubResponse if available
        )
        
        mockInteractionListeners.forEach { listener ->
            listener.onMockInteraction(mockInteractionData)
        }
    }
    
    private fun determineStubType(mockEvent: MockEvent): String {
        return when {
            mockEvent.details.contains("Example", ignoreCase = true) -> StubType.EXAMPLE.value
            mockEvent.details.contains("Generated", ignoreCase = true) -> StubType.GENERATED.value
            mockEvent.details.contains("Pass", ignoreCase = true) -> StubType.PASS_THROUGH.value
            else -> StubType.EXPLICIT.value
        }
    }
    
    private fun determineResponseSource(mockEvent: MockEvent): String {
        return when {
            mockEvent.details.contains("Example", ignoreCase = true) -> "LoadedStub"
            mockEvent.details.contains("Transient", ignoreCase = true) -> "TransientStub"
            mockEvent.details.contains("Generated", ignoreCase = true) -> "GeneratedFake"
            mockEvent.details.contains("Pass", ignoreCase = true) -> "PassThrough"
            else -> "Default"
        }
    }
}