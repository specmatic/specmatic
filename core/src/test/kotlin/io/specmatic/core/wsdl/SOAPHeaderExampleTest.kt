package io.specmatic.core.wsdl

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.mock.NoMatchingScenario
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.HttpStub
import io.specmatic.test.TestExecutor
import io.specmatic.testPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SOAPHeaderExampleTest {
    @Test
    fun `explicit empty header examples retain the wrapper and its attributes through contract tests and mocking`() {
        val fixture = loadWsdlExampleFixture(
            "src/test/resources/wsdl/soap_header_presence/service.wsdl",
            "src/test/resources/wsdl/soap_header_presence/examples",
        )
        val seenRequests = mutableListOf<String>()
        val seenResponses = mutableListOf<String>()
        val results = HttpStub(fixture.feature, fixture.scenarioStubs, port = testPort()).use { stub ->
            fixture.feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    seenRequests.add(request.body.toStringLiteral())
                    return stub.client.execute(request).also { seenResponses.add(it.body.toStringLiteral()) }
                }
            })
        }
        assertThat(results.success()).withFailMessage(results.report()).isTrue()
        assertThat(seenRequests).isNotEmpty()
        (seenRequests + seenResponses).forEach { message ->
            assertThat(soapElementCount(message, "http://schemas.xmlsoap.org/soap/envelope/", "Header")).isEqualTo(1)
            assertThat(message).contains("urn:application:example")
            assertThat(soapElementCount(message, "urn:header-presence", "Session")).isZero()
            assertThat(soapElementCount(message, "urn:header-presence", "Receipt")).isZero()
        }
    }

    @Test
    fun `a supplied example with invalid declared header content is rejected`() {
        val invalid = ScenarioStub(
            request = headerRequest(envelope("<s:Header><t:Session><t:id>wrong</t:id></t:Session></s:Header>")),
            response = soapResponse("")
        )
        val error = assertThrows<NoMatchingScenario> {
            HttpStub(headerFeature(), listOf(invalid), port = testPort()).use { }
        }
        assertThat(error.message).contains("Session", "id", "wrong")
    }
}
