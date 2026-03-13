package io.specmatic.core.wsdl

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.wsdl.payload.emptySoapMessage
import io.specmatic.stub.HttpStub
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class WSDLParserContractBlackBoxTest {
    @Test
    fun `contract test for choice wsdl sends both example soap payload variants`() {
        val fixture = loadWsdlExampleFixture(
            "src/test/resources/wsdl/state_machine/choice_ref.wsdl",
            "src/test/resources/wsdl/state_machine/choice_ref_examples",
        )

        val seenRequestBodies = mutableListOf<String>()

        val result = HttpStub(fixture.feature, fixture.scenarioStubs).use { stub ->
            fixture.feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(request.path).isEqualTo("/choice-ref")
                    assertThat(request.headers["SOAPAction"]).isIn("\"/choice-ref/signonCustId\"", "/choice-ref/signonCustId")
                    seenRequestBodies += request.body.toStringLiteral()
                    return stub.client.execute(request)
                }
            })
        }

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(result.successCount).isEqualTo(2)
        assertThat(seenRequestBodies).hasSize(2)
        assertThat(seenRequestBodies).anySatisfy {
            assertThat(it).contains("SignonCustId").contains("PrimaryName").contains("CustId").contains("CP-123")
            assertThat(it).doesNotContain("CustLoginId>login-123<")
        }
        assertThat(seenRequestBodies).anySatisfy {
            assertThat(it).contains("SignonCustId").contains("PrimaryName").contains("CustLoginId>login-123<")
            assertThat(it).doesNotContain("CustPermId")
        }
    }

    @Test
    fun `contract test for no input wsdl sends no request body`() {
        val feature = parseContractFileToFeature(File("src/test/resources/wsdl/state_machine/no_input.wsdl"))

        val result = HttpStub(feature).use { stub ->
            feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(request.path).isEqualTo("/no-input")
                    assertThat(request.headers["SOAPAction"]).isIn("\"/no-input/ping\"", "/no-input/ping")
                    assertThat(request.body.toStringLiteral()).isEmpty()
                    return stub.client.execute(request)
                }
            })
        }

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(result.successCount).isEqualTo(1)
    }

    @Test
    fun `contract test for empty message part sends empty soap payload`() {
        val feature = parseContractFileToFeature(File("src/test/resources/wsdl/state_machine/empty_part.wsdl"))

        val result = HttpStub(feature).use { stub ->
            feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(request.path).isEqualTo("/empty-part")
                    assertThat(request.headers["SOAPAction"]).isIn("\"/empty-part/ping\"", "/empty-part/ping")
                    assertThat(request.body).isEqualTo(emptySoapMessage())
                    return stub.client.execute(request)
                }
            })
        }

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(result.successCount).isEqualTo(1)
    }

    @Test
    fun `contract test for element ref wsdl sends the example soap payload`() {
        val fixture = loadWsdlExampleFixture(
            "src/test/resources/wsdl/state_machine/element_ref.wsdl",
            "src/test/resources/wsdl/state_machine/element_ref_examples",
        )

        val result = HttpStub(fixture.feature, fixture.scenarioStubs).use { stub ->
            fixture.feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertHttpRequestMatches(request, fixture.scenarioStub.request)
                    assertThat(request.headers["Content-Type"]).isNull()
                    return stub.client.execute(request)
                }
            })
        }

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(result.successCount).isEqualTo(1)
    }

    @Test
    fun `contract test for type ref wsdl sends the example soap payload`() {
        val fixture = loadWsdlExampleFixture(
            "src/test/resources/wsdl/state_machine/type_ref.wsdl",
            "src/test/resources/wsdl/state_machine/type_ref_examples",
        )

        val result = HttpStub(fixture.feature, fixture.scenarioStubs).use { stub ->
            fixture.feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertHttpRequestMatches(request, fixture.scenarioStub.request)
                    assertThat(request.headers["Content-Type"]).isNull()
                    return stub.client.execute(request)
                }
            })
        }

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(result.successCount).isEqualTo(1)
    }
}
