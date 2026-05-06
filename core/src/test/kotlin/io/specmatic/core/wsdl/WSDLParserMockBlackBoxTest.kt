package io.specmatic.core.wsdl

import io.ktor.http.ContentType
import io.specmatic.conversions.wsdlContentToFeature
import io.specmatic.core.CONTENT_TYPE
import io.specmatic.core.HttpRequest
import io.specmatic.core.Source
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.config.v2.SpecExecutionConfig
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.value.StringValue
import io.specmatic.core.wsdl.payload.emptySoapMessage
import io.specmatic.stub.HttpStub
import io.specmatic.stub.SpecmaticConfigSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.net.ServerSocket

class WSDLParserMockBlackBoxTest {
    @Test
    fun `mock for wsdl without soap address serves root endpoint when configured baseUrl ends with slash`() {
        val wsdlContent = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:qr="http://specmatic.io/SOAPService/"
                              targetNamespace="http://specmatic.io/SOAPService/">
                <wsdl:types>
                    <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" targetNamespace="http://specmatic.io/SOAPService/">
                        <xsd:element name="SimpleRequest" type="xsd:string"/>
                        <xsd:element name="SimpleResponse" type="xsd:string"/>
                    </xsd:schema>
                </wsdl:types>

                <wsdl:message name="simpleInputMessage">
                    <wsdl:part name="simpleInputPart" element="qr:SimpleRequest"/>
                </wsdl:message>
                <wsdl:message name="simpleOutputMessage">
                    <wsdl:part name="simpleOutputPart" element="qr:SimpleResponse"/>
                </wsdl:message>

                <wsdl:portType name="simplePortType">
                    <wsdl:operation name="SimpleOperation">
                        <wsdl:input name="simpleInput" message="qr:simpleInputMessage"/>
                        <wsdl:output name="simpleOutput" message="qr:simpleOutputMessage"/>
                    </wsdl:operation>
                </wsdl:portType>

                <wsdl:binding name="simpleBinding" type="qr:simplePortType">
                    <soap:binding transport="http://schemas.xmlsoap.org/soap/http"/>
                    <wsdl:operation name="SimpleOperation">
                        <soap:operation soapAction="http://specmatic.io/SOAPService/SimpleOperation"/>
                        <wsdl:input name="simpleInput">
                            <soap:body use="literal"/>
                        </wsdl:input>
                        <wsdl:output name="simpleOutput">
                            <soap:body use="literal"/>
                        </wsdl:output>
                    </wsdl:operation>
                </wsdl:binding>

                <wsdl:service name="simpleService">
                    <wsdl:port name="simplePort" binding="qr:simpleBinding"/>
                </wsdl:service>
            </wsdl:definitions>
        """
        val feature = wsdlContentToFeature(wsdlContent, "root.wsdl")
        val port = ServerSocket(0).use { it.localPort }
        val baseUrl = "http://localhost:$port/"
        val specmaticConfig = SpecmaticConfigV1V2Common(
            sources = listOf(
                Source(
                    stub = listOf(
                        SpecExecutionConfig.ObjectValue.FullUrl(
                            baseUrl = baseUrl,
                            specs = listOf(feature.path),
                        )
                    )
                )
            )
        )

        val response = HttpStub(
            features = listOf(feature),
            host = "localhost",
            port = port,
            specmaticConfigSource = SpecmaticConfigSource.fromConfigObject(specmaticConfig),
            specToStubBaseUrlMap = mapOf(feature.path to baseUrl)
        ).use { stub ->
            stub.client.execute(feature.scenarios.single().generateHttpRequest())
        }

        assertThat(response.status).isEqualTo(200)
    }

    @Test
    fun `mock for scalar choice wsdl without examples returns generated response values`() {
        val feature = parseContractFileToFeature(File("src/test/resources/wsdl/state_machine/scalar_choice.wsdl"))

        val response = HttpStub(feature).use { stub ->
            stub.client.execute(
                HttpRequest(
                    method = "POST",
                    path = "/choice-scalar",
                    headers = mapOf("SOAPAction" to "\"/choice-scalar/scalarChoice\"", CONTENT_TYPE to ContentType.Text.Xml.toString()),
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
                    headers = mapOf("SOAPAction" to "\"/choice-scalar/scalarChoice\"", CONTENT_TYPE to ContentType.Text.Xml.toString()),
                    body = StringValue("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:Choicescalar=\"http://choice-scalar\"><soapenv:Body><Choicescalar:ScalarChoiceRequest><PrimaryName>PrimaryName</PrimaryName><CustomerNumber>C-123</CustomerNumber></Choicescalar:ScalarChoiceRequest></soapenv:Body></soapenv:Envelope>")
                )
            )

            val loginIdResponse = stub.client.execute(
                HttpRequest(
                    method = "POST",
                    path = "/choice-scalar",
                    headers = mapOf("SOAPAction" to "\"/choice-scalar/scalarChoice\"", CONTENT_TYPE to ContentType.Text.Xml.toString()),
                    body = StringValue("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:Choicescalar=\"http://choice-scalar\"><soapenv:Body><Choicescalar:ScalarChoiceRequest><PrimaryName>PrimaryName</PrimaryName><LoginId>login-123</LoginId></Choicescalar:ScalarChoiceRequest></soapenv:Body></soapenv:Envelope>")
                )
            )

            assertThat(customerNumberResponse.status).isEqualTo(200)
            assertThat(loginIdResponse.status).isEqualTo(200)
        }
    }

    @Test
    fun `mock for scalar choice wsdl rejects payload containing both branches in one choice occurrence`() {
        val feature = parseContractFileToFeature(File("src/test/resources/wsdl/state_machine/scalar_choice.wsdl"))

        val response = HttpStub(feature).use { stub ->
            stub.client.execute(
                HttpRequest(
                    method = "POST",
                    path = "/choice-scalar",
                    headers = mapOf("SOAPAction" to "\"/choice-scalar/scalarChoice\"", CONTENT_TYPE to ContentType.Text.Xml.toString()),
                    body = StringValue("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:Choicescalar=\"http://choice-scalar\"><soapenv:Body><Choicescalar:ScalarChoiceRequest><PrimaryName>PrimaryName</PrimaryName><CustomerNumber>C-123</CustomerNumber><LoginId>login-123</LoginId></Choicescalar:ScalarChoiceRequest></soapenv:Body></soapenv:Envelope>")
                )
            )
        }

        assertThat(response.status).isEqualTo(400)
    }

    @Test
    fun `mock for complex choice wsdl without examples returns generated response values`() {
        val feature = parseContractFileToFeature(File("src/test/resources/wsdl/state_machine/complex_choice.wsdl"))

        val response = HttpStub(feature).use { stub ->
            stub.client.execute(
                HttpRequest(
                    method = "POST",
                    path = "/choice-complex",
                    headers = mapOf("SOAPAction" to "\"/choice-complex/complexChoice\"", CONTENT_TYPE to ContentType.Text.Xml.toString()),
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
                    headers = mapOf("SOAPAction" to "\"/choice-complex/complexChoice\"", CONTENT_TYPE to ContentType.Text.Xml.toString()),
                    body = StringValue("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:Choicecomplex=\"http://choice-complex\"><soapenv:Body><Choicecomplex:ComplexChoiceRequest><PrimaryName>PrimaryName</PrimaryName><Choicecomplex:CustomerByPermId><PermId>CP-123</PermId></Choicecomplex:CustomerByPermId></Choicecomplex:ComplexChoiceRequest></soapenv:Body></soapenv:Envelope>")
                )
            )

            val customerByLoginResponse = stub.client.execute(
                HttpRequest(
                    method = "POST",
                    path = "/choice-complex",
                    headers = mapOf("SOAPAction" to "\"/choice-complex/complexChoice\"", CONTENT_TYPE to ContentType.Text.Xml.toString()),
                    body = StringValue("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:Choicecomplex=\"http://choice-complex\"><soapenv:Body><Choicecomplex:ComplexChoiceRequest><PrimaryName>PrimaryName</PrimaryName><Choicecomplex:CustomerByLogin><Domain>Retail</Domain><LoginId>login-123</LoginId></Choicecomplex:CustomerByLogin></Choicecomplex:ComplexChoiceRequest></soapenv:Body></soapenv:Envelope>")
                )
            )

            assertThat(customerByPermIdResponse.status).isEqualTo(200)
            assertThat(customerByLoginResponse.status).isEqualTo(200)
        }
    }

    @Test
    fun `mock for complex choice wsdl rejects payload containing both branches in one choice occurrence`() {
        val feature = parseContractFileToFeature(File("src/test/resources/wsdl/state_machine/complex_choice.wsdl"))

        val response = HttpStub(feature).use { stub ->
            stub.client.execute(
                HttpRequest(
                    method = "POST",
                    path = "/choice-complex",
                    headers = mapOf("SOAPAction" to "\"/choice-complex/complexChoice\"", CONTENT_TYPE to ContentType.Text.Xml.toString()),
                    body = StringValue("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:Choicecomplex=\"http://choice-complex\"><soapenv:Body><Choicecomplex:ComplexChoiceRequest><PrimaryName>PrimaryName</PrimaryName><Choicecomplex:CustomerByPermId><PermId>CP-123</PermId></Choicecomplex:CustomerByPermId><Choicecomplex:CustomerByLogin><Domain>Retail</Domain><LoginId>login-123</LoginId></Choicecomplex:CustomerByLogin></Choicecomplex:ComplexChoiceRequest></soapenv:Body></soapenv:Envelope>")
                )
            )
        }

        assertThat(response.status).isEqualTo(400)
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
            headers = mapOf("SOAPAction" to "\"/choice-optional/optionalChoice\"", CONTENT_TYPE to ContentType.Text.Xml.toString()),
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
    fun `mock for repeating scalar choice wsdl accepts both choice occurrences in one request`() {
        val feature = parseContractFileToFeature(File("src/test/resources/wsdl/state_machine/scalar_choice_repeating.wsdl"))
        val request = HttpRequest(
            method = "POST",
            path = "/choice-scalar-repeating",
            headers = mapOf("SOAPAction" to "\"/choice-scalar-repeating/repeatingScalarChoice\"", CONTENT_TYPE to ContentType.Text.Xml.toString()),
            body = StringValue("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:Repeatingscalar=\"http://choice-scalar-repeating\"><soapenv:Body><Repeatingscalar:RepeatingScalarChoiceRequest><PrimaryName>PrimaryName</PrimaryName><CustomerNumber>C-123</CustomerNumber><LoginId>login-123</LoginId></Repeatingscalar:RepeatingScalarChoiceRequest></soapenv:Body></soapenv:Envelope>")
        )

        val response = HttpStub(feature).use { stub ->
            stub.client.execute(request)
        }

        assertThat(response.status).isEqualTo(200)
        assertThat(response.body.toStringLiteral()).contains("RepeatingScalarChoiceResponse")
    }

    @Test
    fun `mock for repeating complex choice wsdl accepts both choice occurrences in one request`() {
        val feature = parseContractFileToFeature(File("src/test/resources/wsdl/state_machine/complex_choice_repeating.wsdl"))
        val request = HttpRequest(
            method = "POST",
            path = "/choice-complex-repeating",
            headers = mapOf("SOAPAction" to "\"/choice-complex-repeating/repeatingComplexChoice\"", CONTENT_TYPE to ContentType.Text.Xml.toString()),
            body = StringValue("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:Repeatingcomplex=\"http://choice-complex-repeating\"><soapenv:Body><Repeatingcomplex:RepeatingComplexChoiceRequest><PrimaryName>PrimaryName</PrimaryName><Repeatingcomplex:CustomerByPermId><PermId>CP-123</PermId></Repeatingcomplex:CustomerByPermId><Repeatingcomplex:CustomerByLogin><Domain>Retail</Domain><LoginId>login-123</LoginId></Repeatingcomplex:CustomerByLogin></Repeatingcomplex:RepeatingComplexChoiceRequest></soapenv:Body></soapenv:Envelope>")
        )

        val response = HttpStub(feature).use { stub ->
            stub.client.execute(request)
        }

        assertThat(response.status).isEqualTo(200)
        assertThat(response.body.toStringLiteral()).contains("RepeatingComplexChoiceResponse")
    }

    @Test
    fun `mock for unbounded scalar choice wsdl accepts more than one choice occurrence in one request`() {
        val feature = parseContractFileToFeature(File("src/test/resources/wsdl/state_machine/scalar_choice_repeating_unbounded.wsdl"))
        val request = HttpRequest(
            method = "POST",
            path = "/choice-scalar-repeating-unbounded",
            headers = mapOf("SOAPAction" to "\"/choice-scalar-repeating-unbounded/repeatingScalarChoiceUnbounded\"", CONTENT_TYPE to ContentType.Text.Xml.toString()),
            body = StringValue("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:Repeatingscalar=\"http://choice-scalar-repeating-unbounded\"><soapenv:Body><Repeatingscalar:RepeatingScalarChoiceUnboundedRequest><PrimaryName>PrimaryName</PrimaryName><CustomerNumber>C-123</CustomerNumber><LoginId>login-123</LoginId><CustomerNumber>C-456</CustomerNumber></Repeatingscalar:RepeatingScalarChoiceUnboundedRequest></soapenv:Body></soapenv:Envelope>")
        )

        val response = HttpStub(feature).use { stub ->
            stub.client.execute(request)
        }

        assertThat(response.status).isEqualTo(200)
        assertThat(response.body.toStringLiteral()).contains("RepeatingScalarChoiceUnboundedResponse")
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
    fun `mock for soap version_1_2 wsdl returns the example soap response with appropriate content-type header`() {
        val wsdlSpecPath = "src/test/resources/wsdl/cdata_test_soap12/data_api.wsdl"
        val wsdlExamplesPath = "src/test/resources/wsdl/cdata_test_soap12/data_api_examples"
        val fixture = loadWsdlExampleFixture(wsdlSpecPath, wsdlExamplesPath)
        val response = HttpStub(fixture.feature, fixture.scenarioStubs).use { stub ->
            stub.client.execute(fixture.scenarioStub.request)
        }

        assertThat(fixture.scenarioStub.request.headers[CONTENT_TYPE]).startsWith("application/soap+xml")
        assertThat(response.status).isEqualTo(fixture.scenarioStub.response.status)
        assertThat(response.headers["Content-Type"]).isEqualTo("application/soap+xml")
        assertThat(response.body).isEqualTo(fixture.scenarioStub.response.body)
    }

    @Test
    fun `mock for soap version_1_2 wsdl generates response with appropriate content-type header`() {
        val wsdlSpecPath = "src/test/resources/wsdl/cdata_test_soap12/data_api.wsdl"
        val wsdlExamplesPath = "src/test/resources/wsdl/cdata_test_soap12/data_api_examples"
        val fixture = loadWsdlExampleFixture(wsdlSpecPath, wsdlExamplesPath)
        val response = HttpStub(fixture.feature, emptyList()).use { stub ->
            stub.client.execute(fixture.scenarioStub.request)
        }

        assertThat(response.status).isEqualTo(fixture.scenarioStub.response.status)
        assertThat(response.headers["Content-Type"]).isEqualTo("application/soap+xml")
    }

    @Test
    fun `mock for wsdl should rejects request with wrong content-type`() {
        val wsdlSpecPath = "src/test/resources/wsdl/cdata_test_soap12/data_api.wsdl"
        val wsdlExamplesPath = "src/test/resources/wsdl/cdata_test_soap12/data_api_examples"
        val fixture = loadWsdlExampleFixture(wsdlSpecPath, wsdlExamplesPath)
        val invalidRequest = fixture.scenarioStub.request.copy(headers = fixture.scenarioStub.request.headers + (CONTENT_TYPE to ContentType.Text.Xml.toString()))
        val response = HttpStub(fixture.feature, fixture.scenarioStubs).use { stub ->
            stub.client.execute(invalidRequest)
        }

        assertThat(response.status).isEqualTo(400)
    }

    @Test
    fun `mock for wsdl with empty http body should not enforce content-type to be present`() {
        val wsdlSpecPath = "src/test/resources/wsdl/state_machine/no_input.wsdl"
        val feature = parseContractFileToFeature(File(wsdlSpecPath))
        val request = feature.scenarios.single().generateHttpRequest()
        val response = HttpStub(feature).use { stub -> stub.client.execute(request) }
        assertThat(response.status).isEqualTo(200)
    }

    @Test
    fun `mock for empty message part wsdl returns an empty soap response`() {
        val feature = parseContractFileToFeature(File("src/test/resources/wsdl/state_machine/empty_part.wsdl"))
        val request = HttpRequest(
            method = "POST",
            path = "/empty-part",
            headers = mapOf("SOAPAction" to "\"/empty-part/ping\"", CONTENT_TYPE to ContentType.Text.Xml.toString()),
            body = emptySoapMessage(),
        )

        val response = HttpStub(feature).use { stub ->
            stub.client.execute(request)
        }

        assertThat(response.status).isEqualTo(200)
        assertThat(response.body.toStringLiteral()).isEqualTo(emptySoapMessage().toStringLiteral())
    }

}
