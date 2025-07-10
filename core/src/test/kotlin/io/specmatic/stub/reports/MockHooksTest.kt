package io.specmatic.stub.reports

import io.specmatic.core.*
import io.specmatic.core.value.StringValue
import io.specmatic.stub.listener.MockEvent
import io.specmatic.stub.listener.MockEventListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class MockHooksTest {

    @AfterEach
    fun cleanup() {
        // Clear all listeners after each test - collect first to avoid ConcurrentModificationException
        val listenersToRemove = mutableListOf<MockEventListener>()
        MockHooks.onEachListener {
            listenersToRemove.add(this)
        }
        listenersToRemove.forEach { MockHooks.removeListener(it) }
    }

    @Test
    fun `should register and notify listeners`() {
        var receivedEvent: MockEvent? = null

        val listener = object : MockEventListener {
            override fun onRespond(mockEvent: MockEvent) {
                receivedEvent = mockEvent
            }
        }

        MockHooks.registerListener(listener)

        val mockEvent = MockEvent(
            name = "Test Event",
            details = "Request matched example",
            request = HttpRequest(method = "GET", path = "/test"),
            requestTime = 1234567890,
            response = HttpResponse(status = 200, body = StringValue("OK")),
            responseTime = 1234567990,
            scenario = Scenario(
                name = "Test Scenario",
                httpRequestPattern = HttpRequestPattern(method = "GET"),
                httpResponsePattern = HttpResponsePattern(status = 200),
                specification = "test.yaml"
            ),
            result = TestResult.Success
        )

        MockHooks.onMockEvent(mockEvent)

        assertThat(receivedEvent).isNotNull()
        assertThat(receivedEvent?.name).isEqualTo("Test Event")
        assertThat(receivedEvent?.request?.method).isEqualTo("GET")
    }

    @Test
    fun `should notify multiple listeners`() {
        val receivedEvents = mutableListOf<MockEvent>()

        val listener1 = object : MockEventListener {
            override fun onRespond(data: MockEvent) {
                receivedEvents.add(data)
            }
        }

        val listener2 = object : MockEventListener {
            override fun onRespond(data: MockEvent) {
                receivedEvents.add(data)
            }
        }

        MockHooks.registerListener(listener1)
        MockHooks.registerListener(listener2)

        val mockEvent = MockEvent(
            name = "Multi Test",
            details = "Testing multiple listeners",
            request = HttpRequest(method = "POST", path = "/multi"),
            requestTime = 1234567890,
            response = HttpResponse(status = 201, body = StringValue("Created")),
            responseTime = 1234567990,
            scenario = Scenario(
                name = "Multi Scenario",
                httpRequestPattern = HttpRequestPattern(method = "POST"),
                httpResponsePattern = HttpResponsePattern(status = 201),
                specification = "multi.yaml"
            ),
            result = TestResult.Success
        )

        MockHooks.onMockEvent(mockEvent)

        assertThat(receivedEvents).hasSize(2)
        assertThat(receivedEvents.all { it.name == "Multi Test" }).isTrue()
    }

    @Test
    fun `should remove listeners`() {
        var eventCount = 0

        val listener = object : MockEventListener {
            override fun onRespond(data: MockEvent) {
                eventCount++
            }
        }

        MockHooks.registerListener(listener)

        val mockEvent = MockEvent(
            name = "Remove Test",
            details = "Testing listener removal",
            request = HttpRequest(method = "DELETE", path = "/remove"),
            requestTime = 1234567890,
            response = HttpResponse(status = 204),
            responseTime = 1234567990,
            scenario = Scenario(
                name = "Remove Scenario",
                httpRequestPattern = HttpRequestPattern(method = "DELETE"),
                httpResponsePattern = HttpResponsePattern(status = 204),
                specification = "remove.yaml"
            ),
            result = TestResult.Success
        )

        MockHooks.onMockEvent(mockEvent)
        assertThat(eventCount).isEqualTo(1)

        MockHooks.removeListener(listener)
        MockHooks.onMockEvent(mockEvent)
        assertThat(eventCount).isEqualTo(1) // Should not increment after removal
    }
}
