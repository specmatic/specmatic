package io.specmatic.core.wsdl

import io.specmatic.core.HttpRequest
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.value.StringValue
import io.specmatic.core.wsdl.payload.emptySoapMessage
import io.specmatic.stub.HttpStub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class WSDLParserMockBlackBoxTest {
    @Test
    fun `mock for scalar choice wsdl without examples returns generated response values`() {
        val feature = parseContractFileToFeature(File("src/test/resources/wsdl/state_machine/scalar_choice.wsdl"))

        val response = HttpStub(feature).use { stub ->
            stub.client.execute(
                HttpRequest(
                    method = "POST",
                    path = "/choice-scalar",
                    headers = mapOf("SOAPAction" to "\"/choice-scalar/scalarChoice\""),
                    body = StringValue("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:Choicescalar=\"http://choice-scalar\"><soapenv:Body><Choicescalar:ScalarChoiceRequest><PrimaryName>PrimaryName</PrimaryName><CustomerNumber>C-123</CustomerNumber></Choicescalar:ScalarChoiceRequest></soapenv:Body></soapenv:Envelope>")
                )
            )
        }

        assertThat(response.status).isEqualTo(200)
        assertThat(response.body.toStringLiteral())
            .contains("ScalarChoiceResponse")
            .doesNotContain("(string)")
        assertThat(response.body.toStringLiteral())
            .matches { it.contains("StatusCode") || it.contains("StatusMessage") }
    }

    @Test
    fun `mock for scalar choice wsdl accepts each valid request branch`() {
        val feature = parseContractFileToFeature(File("src/test/resources/wsdl/state_machine/scalar_choice.wsdl"))

        HttpStub(feature).use { stub ->
            val customerNumberResponse = stub.client.execute(
                HttpRequest(
                    method = "POST",
                    path = "/choice-scalar",
                    headers = mapOf("SOAPAction" to "\"/choice-scalar/scalarChoice\""),
                    body = StringValue("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:Choicescalar=\"http://choice-scalar\"><soapenv:Body><Choicescalar:ScalarChoiceRequest><PrimaryName>PrimaryName</PrimaryName><CustomerNumber>C-123</CustomerNumber></Choicescalar:ScalarChoiceRequest></soapenv:Body></soapenv:Envelope>")
                )
            )

            val loginIdResponse = stub.client.execute(
                HttpRequest(
                    method = "POST",
                    path = "/choice-scalar",
                    headers = mapOf("SOAPAction" to "\"/choice-scalar/scalarChoice\""),
                    body = StringValue("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:Choicescalar=\"http://choice-scalar\"><soapenv:Body><Choicescalar:ScalarChoiceRequest><PrimaryName>PrimaryName</PrimaryName><LoginId>login-123</LoginId></Choicescalar:ScalarChoiceRequest></soapenv:Body></soapenv:Envelope>")
                )
            )

            assertThat(customerNumberResponse.status).isEqualTo(200)
            assertThat(loginIdResponse.status).isEqualTo(200)
        }
    }

    @Test
    fun `mock for complex choice wsdl without examples returns generated response values`() {
        val feature = parseContractFileToFeature(File("src/test/resources/wsdl/state_machine/complex_choice.wsdl"))

        val response = HttpStub(feature).use { stub ->
            stub.client.execute(
                HttpRequest(
                    method = "POST",
                    path = "/choice-complex",
                    headers = mapOf("SOAPAction" to "\"/choice-complex/complexChoice\""),
                    body = StringValue("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:Choicecomplex=\"http://choice-complex\"><soapenv:Body><Choicecomplex:ComplexChoiceRequest><PrimaryName>PrimaryName</PrimaryName><Choicecomplex:CustomerByPermId><PermId>CP-123</PermId></Choicecomplex:CustomerByPermId></Choicecomplex:ComplexChoiceRequest></soapenv:Body></soapenv:Envelope>")
                )
            )
        }

        assertThat(response.status).isEqualTo(200)
        assertThat(response.body.toStringLiteral())
            .contains("ComplexChoiceResponse")
            .doesNotContain("(string)")
        assertThat(response.body.toStringLiteral())
            .matches { it.contains("CustomerFound") || it.contains("CustomerPending") }
    }

    @Test
    fun `mock for complex choice wsdl accepts each valid request branch`() {
        val feature = parseContractFileToFeature(File("src/test/resources/wsdl/state_machine/complex_choice.wsdl"))

        HttpStub(feature).use { stub ->
            val customerByPermIdResponse = stub.client.execute(
                HttpRequest(
                    method = "POST",
                    path = "/choice-complex",
                    headers = mapOf("SOAPAction" to "\"/choice-complex/complexChoice\""),
                    body = StringValue("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:Choicecomplex=\"http://choice-complex\"><soapenv:Body><Choicecomplex:ComplexChoiceRequest><PrimaryName>PrimaryName</PrimaryName><Choicecomplex:CustomerByPermId><PermId>CP-123</PermId></Choicecomplex:CustomerByPermId></Choicecomplex:ComplexChoiceRequest></soapenv:Body></soapenv:Envelope>")
                )
            )

            val customerByLoginResponse = stub.client.execute(
                HttpRequest(
                    method = "POST",
                    path = "/choice-complex",
                    headers = mapOf("SOAPAction" to "\"/choice-complex/complexChoice\""),
                    body = StringValue("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:Choicecomplex=\"http://choice-complex\"><soapenv:Body><Choicecomplex:ComplexChoiceRequest><PrimaryName>PrimaryName</PrimaryName><Choicecomplex:CustomerByLogin><Domain>Retail</Domain><LoginId>login-123</LoginId></Choicecomplex:CustomerByLogin></Choicecomplex:ComplexChoiceRequest></soapenv:Body></soapenv:Envelope>")
                )
            )

            assertThat(customerByPermIdResponse.status).isEqualTo(200)
            assertThat(customerByLoginResponse.status).isEqualTo(200)
        }
    }

    @Test
    fun `mock for choice wsdl returns the example soap response for both variants`() {
        val fixture = loadWsdlExampleFixture(
            "src/test/resources/wsdl/state_machine/choice_ref.wsdl",
            "src/test/resources/wsdl/state_machine/choice_ref_examples",
        )

        assertThat(fixture.feature.scenarios).hasSize(1)

        HttpStub(fixture.feature, fixture.scenarioStubs).use { stub ->
            fixture.scenarioStubs.forEach { scenarioStub ->
                val response = stub.client.execute(scenarioStub.request)

                assertThat(response.status).isEqualTo(scenarioStub.response.status)
                assertThat(response.headers["Content-Type"]).isEqualTo(scenarioStub.response.headers["Content-Type"])
                assertThat(response.body).isEqualTo(scenarioStub.response.body)
            }
        }
    }

    @Test
    fun `mock for optional choice wsdl accepts omitted choice group`() {
        val feature = parseContractFileToFeature(File("src/test/resources/wsdl/state_machine/choice_optional.wsdl"))
        val request = HttpRequest(
            method = "POST",
            path = "/choice-optional",
            headers = mapOf("SOAPAction" to "\"/choice-optional/optionalChoice\""),
            body = StringValue("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:Opt=\"http://choice-optional\"><soapenv:Body><Opt:OptionalChoiceRequest><Opt:SPName>PrimaryName</Opt:SPName></Opt:OptionalChoiceRequest></soapenv:Body></soapenv:Envelope>")
        )

        val response = HttpStub(feature).use { stub ->
            stub.client.execute(request)
        }

        assertThat(response.status).isEqualTo(200)
        assertThat(response.body.toStringLiteral())
            .contains("OptionalChoiceResponse")
            .contains("status>")
    }

    @Test
    fun `mock for element ref wsdl returns the example soap response`() {
        val fixture = loadWsdlExampleFixture(
            "src/test/resources/wsdl/state_machine/element_ref.wsdl",
            "src/test/resources/wsdl/state_machine/element_ref_examples",
        )

        val response = HttpStub(fixture.feature, fixture.scenarioStubs).use { stub ->
            stub.client.execute(fixture.scenarioStub.request)
        }

        assertThat(response.status).isEqualTo(fixture.scenarioStub.response.status)
        assertThat(response.headers["Content-Type"]).isEqualTo(fixture.scenarioStub.response.headers["Content-Type"])
        assertThat(response.body).isEqualTo(fixture.scenarioStub.response.body)
    }

    @Test
    fun `mock for type ref wsdl returns the example soap response`() {
        val fixture = loadWsdlExampleFixture(
            "src/test/resources/wsdl/state_machine/type_ref.wsdl",
            "src/test/resources/wsdl/state_machine/type_ref_examples",
        )

        val response = HttpStub(fixture.feature, fixture.scenarioStubs).use { stub ->
            stub.client.execute(fixture.scenarioStub.request)
        }

        assertThat(response.status).isEqualTo(fixture.scenarioStub.response.status)
        assertThat(response.headers["Content-Type"]).isEqualTo(fixture.scenarioStub.response.headers["Content-Type"])
        assertThat(response.body).isEqualTo(fixture.scenarioStub.response.body)
    }

    @Test
    fun `mock for empty message part wsdl returns an empty soap response`() {
        val feature = parseContractFileToFeature(File("src/test/resources/wsdl/state_machine/empty_part.wsdl"))
        val request = HttpRequest(
            method = "POST",
            path = "/empty-part",
            headers = mapOf("SOAPAction" to "\"/empty-part/ping\""),
            body = emptySoapMessage(),
        )

        val response = HttpStub(feature).use { stub ->
            stub.client.execute(request)
        }

        assertThat(response.status).isEqualTo(200)
        assertThat(response.body.toStringLiteral()).isEqualTo(emptySoapMessage().toStringLiteral())
    }

}
