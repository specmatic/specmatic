package io.specmatic.core.config.v3.components.runOptions

import io.specmatic.core.TemplatableValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RunOptionsSpecificationsTest {
    @Test
    fun `openapi getBaseUrl should return baseUrl when present`() {
        val runOptions = OpenApiRunOptionsSpecifications(OpenApiRunOptionsSpecifications.Value(baseUrl = TemplatableValue("http://example.com")))
        assertEquals("http://example.com", runOptions.getBaseUrl("localhost"))
    }

    @Test
    fun `openapi getBaseUrl should return host and port when baseUrl is absent`() {
        val runOptions = OpenApiRunOptionsSpecifications(OpenApiRunOptionsSpecifications.Value(host = TemplatableValue("localhost"), port = TemplatableValue(8080)))
        assertEquals("http://localhost:8080", runOptions.getBaseUrl("localhost"))

        val portOnly = OpenApiRunOptionsSpecifications(OpenApiRunOptionsSpecifications.Value(port = TemplatableValue(9000)))
        assertEquals("http://0.0.0.0:9000", portOnly.getBaseUrl("0.0.0.0"))
    }

    @Test
    fun `wsdl getBaseUrl should return baseUrl when present`() {
        val runOptions = WsdlRunOptionsSpecifications(WsdlRunOptionsSpecifications.Value(baseUrl = TemplatableValue("http://example.com")))
        assertEquals("http://example.com", runOptions.getBaseUrl("localhost"))
    }

    @Test
    fun `wsdl getBaseUrl should return host and port when baseUrl is absent`() {
        val runOptions = WsdlRunOptionsSpecifications(WsdlRunOptionsSpecifications.Value(host = TemplatableValue("localhost"), port = TemplatableValue(9000)))
        assertEquals("http://localhost:9000", runOptions.getBaseUrl("localhost"))

        val portOnly = WsdlRunOptionsSpecifications(WsdlRunOptionsSpecifications.Value(port = TemplatableValue(9000)))
        assertEquals("http://0.0.0.0:9000", portOnly.getBaseUrl("0.0.0.0"))
    }

    @Test
    fun `for other specs getBaseUrl should return host and port and for async use inMemoryBroker`() {
        val otherSpec = RunOptionsSpecifications(RunOptionsSpecifications.Value(host = TemplatableValue("localhost"), port = TemplatableValue(9090)))
        assertEquals("localhost:9090", otherSpec.getBaseUrl("localhost"))

        val otherPortOnly = RunOptionsSpecifications(RunOptionsSpecifications.Value(port = TemplatableValue(9090)))
        assertEquals("0.0.0.0:9090", otherPortOnly.getBaseUrl("0.0.0.0"))

        val asyncSpec = RunOptionsSpecifications(RunOptionsSpecifications.Value().withConfig(mapOf("inMemoryBroker" to mapOf("host" to "localhost", "port" to 5672))))
        assertEquals("localhost:5672", asyncSpec.getBaseUrl("localhost"))
    }
}
