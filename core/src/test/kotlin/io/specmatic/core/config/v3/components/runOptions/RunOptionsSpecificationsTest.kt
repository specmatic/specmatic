package io.specmatic.core.config.v3.components.runOptions

import io.specmatic.core.config.v3.ServerOrigin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RunOptionsSpecificationsTest {
    @Test
    fun `openapi getServerOrigin should return baseUrl when present`() {
        val runOptions = OpenApiRunOptionsSpecifications(OpenApiRunOptionsSpecifications.Value(baseUrl = "http://example.com"))
        assertEquals(ServerOrigin.from("http://example.com"), runOptions.getServerOrigin("localhost"))
    }

    @Test
    fun `openapi getServerOrigin should return http origin when baseUrl is absent`() {
        val runOptions = OpenApiRunOptionsSpecifications(OpenApiRunOptionsSpecifications.Value(host = "localhost", port = 8080))
        assertEquals(ServerOrigin.from("http", "localhost", 8080), runOptions.getServerOrigin("localhost"))

        val portOnly = OpenApiRunOptionsSpecifications(OpenApiRunOptionsSpecifications.Value(port = 9000))
        assertEquals(ServerOrigin.from("http", "0.0.0.0", 9000), portOnly.getServerOrigin("0.0.0.0"))
    }

    @Test
    fun `wsdl getServerOrigin should return baseUrl when present`() {
        val runOptions = WsdlRunOptionsSpecifications(WsdlRunOptionsSpecifications.Value(baseUrl = "http://example.com"))
        assertEquals(ServerOrigin.from("http://example.com"), runOptions.getServerOrigin("localhost"))
    }

    @Test
    fun `wsdl getServerOrigin should return http origin when baseUrl is absent`() {
        val runOptions = WsdlRunOptionsSpecifications(WsdlRunOptionsSpecifications.Value(host = "localhost", port = 9000))
        assertEquals(ServerOrigin.from("http", "localhost", 9000), runOptions.getServerOrigin("localhost"))

        val portOnly = WsdlRunOptionsSpecifications(WsdlRunOptionsSpecifications.Value(port = 9000))
        assertEquals(ServerOrigin.from("http", "0.0.0.0", 9000), portOnly.getServerOrigin("0.0.0.0"))
    }

    @Test
    fun `for other specs getServerOrigin should keep host and port only`() {
        val otherSpec = RunOptionsSpecifications(RunOptionsSpecifications.Value(host = "localhost", port = 9090))
        assertEquals(ServerOrigin.NetworkAddress(host = "localhost", port = 9090), otherSpec.getServerOrigin("localhost"))

        val otherPortOnly = RunOptionsSpecifications(RunOptionsSpecifications.Value(port = 9090))
        assertEquals(ServerOrigin.NetworkAddress(host = "0.0.0.0", port = 9090), otherPortOnly.getServerOrigin("0.0.0.0"))

        val asyncSpec = RunOptionsSpecifications(RunOptionsSpecifications.Value().withConfig(mapOf("inMemoryBroker" to mapOf("host" to "localhost", "port" to 5672))))
        assertEquals(ServerOrigin.NetworkAddress(host = "localhost", port = 5672), asyncSpec.getServerOrigin("localhost"))
    }
}
