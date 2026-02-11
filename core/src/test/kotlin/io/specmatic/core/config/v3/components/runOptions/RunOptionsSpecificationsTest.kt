package io.specmatic.core.config.v3.components.runOptions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RunOptionsSpecificationsTest {
    @Test
    fun `openapi getBaseUrl should return baseUrl when present`() {
        val runOptions = OpenApiRunOptionsSpecifications(OpenApiRunOptionsSpecifications.Value(baseUrl = "http://example.com"))
        assertEquals("http://example.com", runOptions.getBaseUrl())
    }

    @Test
    fun `openapi getBaseUrl should return host and port when baseUrl is absent`() {
        val runOptions = OpenApiRunOptionsSpecifications(OpenApiRunOptionsSpecifications.Value(host = "localhost", port = 8080))
        assertEquals("http://localhost:8080", runOptions.getBaseUrl())

        val portOnly = OpenApiRunOptionsSpecifications(OpenApiRunOptionsSpecifications.Value(port = 9000))
        assertEquals("http://0.0.0.0:9000", portOnly.getBaseUrl())
    }

    @Test
    fun `wsdl getBaseUrl should return baseUrl when present`() {
        val runOptions = WsdlRunOptionsSpecifications(WsdlRunOptionsSpecifications.Value(baseUrl = "http://example.com"))
        assertEquals("http://example.com", runOptions.getBaseUrl())
    }

    @Test
    fun `wsdl getBaseUrl should return host and port when baseUrl is absent`() {
        val runOptions = WsdlRunOptionsSpecifications(WsdlRunOptionsSpecifications.Value(host = "localhost", port = 9000))
        assertEquals("http://localhost:9000", runOptions.getBaseUrl())

        val portOnly = WsdlRunOptionsSpecifications(WsdlRunOptionsSpecifications.Value(port = 9000))
        assertEquals("http://0.0.0.0:9000", portOnly.getBaseUrl())
    }

    @Test
    fun `for other specs getBaseUrl should return host and port and for async use inMemoryBroker`() {
        val otherSpec = RunOptionsSpecifications(RunOptionsSpecifications.Value(host = "localhost", port = 9090))
        assertEquals("localhost:9090", otherSpec.getBaseUrl())

        val otherPortOnly = RunOptionsSpecifications(RunOptionsSpecifications.Value(port = 9090))
        assertEquals("0.0.0.0:9090", otherPortOnly.getBaseUrl())

        val asyncSpec = RunOptionsSpecifications(RunOptionsSpecifications.Value().withConfig(mapOf("inMemoryBroker" to mapOf("host" to "localhost", "port" to 5672))))
        assertEquals("localhost:5672", asyncSpec.getBaseUrl())
    }
}
