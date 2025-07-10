package io.specmatic.stub.report

import io.specmatic.core.*
import io.specmatic.core.pattern.*
import io.specmatic.core.value.StringValue
import io.specmatic.stub.listener.MockEvent
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MockInteractionCollectorTest {
    
    @Test
    fun `should convert MockEvent to MockInteractionData and notify listeners`() {
        var capturedData: MockInteractionData? = null
        val listener = object : MockInteractionListener {
            override fun onMockInteraction(mockInteractionData: MockInteractionData) {
                capturedData = mockInteractionData
            }
        }
        
        val collector = MockInteractionCollector(
            baseUrl = "http://localhost:9000",
            mockInteractionListeners = listOf(listener)
        )
        
        val mockEvent = MockEvent(
            name = "Test Scenario",
            details = "Request matched example",
            request = HttpRequest(
                method = "GET",
                path = "/api/users",
                headers = mapOf("Content-Type" to "application/json")
            ),
            requestTime = 1234567890,
            response = HttpResponse(
                status = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = StringValue("response body")
            ),
            responseTime = 1234568000,
            scenario = Scenario(
                name = "Test Scenario",
                httpRequestPattern = HttpRequestPattern(method = "GET"),
                httpResponsePattern = HttpResponsePattern(status = 200),
                specification = "users.yaml",
                sourceProvider = "github",
                sourceRepository = "myrepo",
                sourceRepositoryBranch = "main",
                serviceType = "REST"
            ),
            result = TestResult.Success
        )
        
        collector.onRespond(mockEvent)
        
        assertEquals("Test Scenario", capturedData?.name)
        assertEquals("http://localhost:9000", capturedData?.baseUrl)
        assertEquals(110, capturedData?.duration) // 1234568000 - 1234567890
        assertEquals(true, capturedData?.valid)
        assertEquals("GET", capturedData?.method)
        assertEquals("/api/users", capturedData?.path)
        assertEquals(200, capturedData?.responseCode)
        assertEquals("example", capturedData?.stubType)
        assertEquals("LoadedStub", capturedData?.responseSource)
        assertEquals("users.yaml", capturedData?.specFileName)
        assertEquals("github", capturedData?.sourceProvider)
        assertEquals("myrepo", capturedData?.sourceRepository)
        assertEquals("main", capturedData?.sourceRepositoryBranch)
        assertEquals("REST", capturedData?.serviceType)
    }
    
    @Test
    fun `should determine stub type from details`() {
        val listener = object : MockInteractionListener {
            var stubType: String = ""
            override fun onMockInteraction(mockInteractionData: MockInteractionData) {
                stubType = mockInteractionData.stubType
            }
        }
        
        val collector = MockInteractionCollector(
            baseUrl = "http://localhost:9000",
            mockInteractionListeners = listOf(listener)
        )
        
        // Test example stub type
        collector.onRespond(createMockEvent("Request matched Example"))
        assertEquals("example", listener.stubType)
        
        // Test generated stub type
        collector.onRespond(createMockEvent("Generated response"))
        assertEquals("generated", listener.stubType)
        
        // Test pass-through stub type
        collector.onRespond(createMockEvent("Pass through to backend"))
        assertEquals("pass-through", listener.stubType)
        
        // Test explicit stub type (default)
        collector.onRespond(createMockEvent("Request matched"))
        assertEquals("explicit", listener.stubType)
    }
    
    @Test
    fun `should notify multiple listeners`() {
        val collectedData = mutableListOf<MockInteractionData>()
        
        val listener1 = object : MockInteractionListener {
            override fun onMockInteraction(mockInteractionData: MockInteractionData) {
                collectedData.add(mockInteractionData)
            }
        }
        
        val listener2 = object : MockInteractionListener {
            override fun onMockInteraction(mockInteractionData: MockInteractionData) {
                collectedData.add(mockInteractionData)
            }
        }
        
        val collector = MockInteractionCollector(
            baseUrl = "http://localhost:9000",
            mockInteractionListeners = listOf(listener1, listener2)
        )
        
        collector.onRespond(createMockEvent("Test"))
        
        assertEquals(2, collectedData.size)
        assertEquals("Test Event", collectedData[0].name)
        assertEquals("Test Event", collectedData[1].name)
    }
    
    private fun createMockEvent(details: String): MockEvent {
        return MockEvent(
            name = "Test Event",
            details = details,
            request = HttpRequest(method = "GET", path = "/test"),
            requestTime = 1234567890,
            response = HttpResponse(status = 200),
            responseTime = 1234567990,
            scenario = null,
            result = TestResult.Success
        )
    }
}