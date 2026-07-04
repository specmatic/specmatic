package io.specmatic.core.wsdl

import io.ktor.http.ContentType
import io.specmatic.conversions.wsdlContentToFeature
import io.specmatic.core.CONTENT_TYPE
import io.specmatic.core.HttpResponse
import io.specmatic.core.HttpRequest
import io.specmatic.core.Source
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.config.v2.SpecExecutionConfig
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.value.StringValue
import io.specmatic.mock.ScenarioStub
import io.specmatic.core.wsdl.payload.emptySoapMessage
import io.specmatic.stub.HttpStub
import io.specmatic.stub.SpecmaticConfigSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
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
    fun `mock for sequential choices accepts valid branch combination and rejects extra branch`() {
        val path = "/choice-edge-sequential"
        val operation = "sequentialChoice"
        val feature = choiceWsdlFeature(
            requestName = "SequentialChoiceRequest",
            requestBodySchema = """
                <xsd:choice>
                    <xsd:element name="FirstA" type="xsd:string"/>
                    <xsd:element name="FirstB" type="xsd:string"/>
                </xsd:choice>
                <xsd:choice>
                    <xsd:element name="SecondA" type="xsd:string"/>
                    <xsd:element name="SecondB" type="xsd:string"/>
                </xsd:choice>
            """.trimIndent(),
            path = path,
            operation = operation,
        )

        HttpStub(feature).use { stub ->
            val validResponse = stub.client.execute(
                choiceSoapRequest(
                    requestName = "SequentialChoiceRequest",
                    requestBody = """
                        <t:FirstB>first-b</t:FirstB>
                        <t:SecondA>second-a</t:SecondA>
                    """.trimIndent(),
                    path = path,
                    operation = operation,
                )
            )
            val invalidResponse = stub.client.execute(
                choiceSoapRequest(
                    requestName = "SequentialChoiceRequest",
                    requestBody = """
                        <t:FirstA>first-a</t:FirstA>
                        <t:FirstB>first-b</t:FirstB>
                        <t:SecondA>second-a</t:SecondA>
                    """.trimIndent(),
                    path = path,
                    operation = operation,
                )
            )

            assertThat(validResponse.status).isEqualTo(200)
            assertThat(invalidResponse.status).isEqualTo(400)
        }
    }

    @Test
    fun `mock for shared-prefix choice accepts later branch after partial branch failure`() {
        val path = "/choice-edge-shared-prefix"
        val operation = "sharedPrefixChoice"
        val feature = choiceWsdlFeature(
            requestName = "SharedPrefixChoiceRequest",
            requestBodySchema = """
                <xsd:choice>
                    <xsd:sequence>
                        <xsd:element name="Common" type="xsd:string"/>
                        <xsd:element name="BranchB" type="xsd:string"/>
                    </xsd:sequence>
                    <xsd:sequence>
                        <xsd:element name="Common" type="xsd:string"/>
                        <xsd:element name="BranchC" type="xsd:string"/>
                    </xsd:sequence>
                </xsd:choice>
            """.trimIndent(),
            path = path,
            operation = operation,
        )

        HttpStub(feature).use { stub ->
            val validResponse = stub.client.execute(
                choiceSoapRequest(
                    requestName = "SharedPrefixChoiceRequest",
                    requestBody = """
                        <t:Common>same-prefix</t:Common>
                        <t:BranchC>second-branch</t:BranchC>
                    """.trimIndent(),
                    path = path,
                    operation = operation,
                )
            )
            val invalidResponse = stub.client.execute(
                choiceSoapRequest(
                    requestName = "SharedPrefixChoiceRequest",
                    requestBody = """
                        <t:Common>same-prefix</t:Common>
                        <t:BranchB>first-branch</t:BranchB>
                        <t:BranchC>second-branch</t:BranchC>
                    """.trimIndent(),
                    path = path,
                    operation = operation,
                )
            )

            assertThat(validResponse.status).isEqualTo(200)
            assertThat(invalidResponse.status).isEqualTo(400)
        }
    }

    @Test
    fun `mock for optional choice in middle accepts omitted and present choice`() {
        val path = "/choice-edge-optional-middle"
        val operation = "optionalMiddleChoice"
        val feature = choiceWsdlFeature(
            requestName = "OptionalMiddleChoiceRequest",
            requestBodySchema = """
                <xsd:element name="Before" type="xsd:string"/>
                <xsd:choice minOccurs="0">
                    <xsd:element name="OptionalA" type="xsd:string"/>
                    <xsd:element name="OptionalB" type="xsd:string"/>
                </xsd:choice>
                <xsd:element name="After" type="xsd:string"/>
            """.trimIndent(),
            path = path,
            operation = operation,
        )

        HttpStub(feature).use { stub ->
            val omittedResponse = stub.client.execute(
                choiceSoapRequest(
                    requestName = "OptionalMiddleChoiceRequest",
                    requestBody = """
                        <t:Before>before</t:Before>
                        <t:After>after</t:After>
                    """.trimIndent(),
                    path = path,
                    operation = operation,
                )
            )
            val presentResponse = stub.client.execute(
                choiceSoapRequest(
                    requestName = "OptionalMiddleChoiceRequest",
                    requestBody = """
                        <t:Before>before</t:Before>
                        <t:OptionalB>optional-b</t:OptionalB>
                        <t:After>after</t:After>
                    """.trimIndent(),
                    path = path,
                    operation = operation,
                )
            )

            assertThat(omittedResponse.status).isEqualTo(200)
            assertThat(presentResponse.status).isEqualTo(200)
        }
    }

    @Test
    fun `mock for bounded choice rejects too few and too many occurrences`() {
        val path = "/choice-edge-bounded"
        val operation = "boundedChoice"
        val feature = choiceWsdlFeature(
            requestName = "BoundedChoiceRequest",
            requestBodySchema = """
                <xsd:choice minOccurs="2" maxOccurs="2">
                    <xsd:element name="CustomerNumber" type="xsd:string"/>
                    <xsd:element name="LoginId" type="xsd:string"/>
                </xsd:choice>
            """.trimIndent(),
            path = path,
            operation = operation,
        )

        HttpStub(feature).use { stub ->
            val validResponse = stub.client.execute(
                choiceSoapRequest(
                    requestName = "BoundedChoiceRequest",
                    requestBody = """
                        <t:CustomerNumber>C-123</t:CustomerNumber>
                        <t:LoginId>login-123</t:LoginId>
                    """.trimIndent(),
                    path = path,
                    operation = operation,
                )
            )
            val tooFewResponse = stub.client.execute(
                choiceSoapRequest(
                    requestName = "BoundedChoiceRequest",
                    requestBody = "<t:CustomerNumber>C-123</t:CustomerNumber>",
                    path = path,
                    operation = operation,
                )
            )
            val tooManyResponse = stub.client.execute(
                choiceSoapRequest(
                    requestName = "BoundedChoiceRequest",
                    requestBody = """
                        <t:CustomerNumber>C-123</t:CustomerNumber>
                        <t:LoginId>login-123</t:LoginId>
                        <t:CustomerNumber>C-456</t:CustomerNumber>
                    """.trimIndent(),
                    path = path,
                    operation = operation,
                )
            )

            assertThat(validResponse.status).isEqualTo(200)
            assertThat(tooFewResponse.status).isEqualTo(400)
            assertThat(tooManyResponse.status).isEqualTo(400)
        }
    }

    @Test
    fun `mock for nested choice accepts nested branch and rejects mixed outer branches`() {
        val path = "/choice-edge-nested"
        val operation = "nestedChoice"
        val feature = choiceWsdlFeature(
            requestName = "NestedChoiceRequest",
            requestBodySchema = """
                <xsd:choice>
                    <xsd:element name="DirectId" type="xsd:string"/>
                    <xsd:choice>
                        <xsd:element name="NestedA" type="xsd:string"/>
                        <xsd:element name="NestedB" type="xsd:string"/>
                    </xsd:choice>
                </xsd:choice>
            """.trimIndent(),
            path = path,
            operation = operation,
        )

        HttpStub(feature).use { stub ->
            val validResponse = stub.client.execute(
                choiceSoapRequest(
                    requestName = "NestedChoiceRequest",
                    requestBody = "<t:NestedB>nested-b</t:NestedB>",
                    path = path,
                    operation = operation,
                )
            )
            val invalidResponse = stub.client.execute(
                choiceSoapRequest(
                    requestName = "NestedChoiceRequest",
                    requestBody = """
                        <t:DirectId>D-123</t:DirectId>
                        <t:NestedB>nested-b</t:NestedB>
                    """.trimIndent(),
                    path = path,
                    operation = operation,
                )
            )

            assertThat(validResponse.status).isEqualTo(200)
            assertThat(invalidResponse.status).isEqualTo(400)
        }
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
    fun `mock for wsdl returns example soap response when request contains xsi type`(@TempDir tempDir: File) {
        val wsdlFile = tempDir.resolve("order-service.wsdl")
            .apply { writeText(minimizedOrderServiceWsdl()) }
        val feature = parseContractFileToFeature(wsdlFile)
        listOf(
            "xsi:type=\"OrderDetails\"",
            "xsi:type=\"ord:OrderDetails\"",
        ).forEach { orderTypeAttribute ->
            val scenarioStub = ScenarioStub(
                request = orderDetailsRequest(orderTypeAttribute),
                response = HttpResponse(
                    status = 200,
                    body = orderDetailsResponseBody(),
                    headers = mapOf(CONTENT_TYPE to ContentType.Text.Xml.toString()),
                ),
            )

            val response = HttpStub(feature, listOf(scenarioStub)).use { stub ->
                stub.client.execute(scenarioStub.request)
            }

            assertThat(response.headers["X-Specmatic-Type"]).isNotEqualTo("random")
            assertThat(response.body.toStringLiteral()).contains("matched-from-external-example")
        }
    }

    @Test
    fun `mock for wsdl rejects request when xsi type is unknown`(@TempDir tempDir: File) {
        val wsdlFile = tempDir.resolve("order-service.wsdl")
            .apply { writeText(minimizedOrderServiceWsdl()) }
        val feature = parseContractFileToFeature(wsdlFile)
        val request = orderDetailsRequest("xsi:type=\"ord:MissingOrderDetails\"")

        val response = HttpStub(feature, strictMode = true).use { stub ->
            stub.client.execute(request)
        }

        assertThat(response.status).isEqualTo(400)
        assertThat(response.body.toStringLiteral()).contains("No matching SOAP stub")
    }

    @Test
    fun `mock for wsdl rejects example response when xsi type is unknown`(@TempDir tempDir: File) {
        val wsdlFile = tempDir.resolve("order-service.wsdl")
            .apply { writeText(minimizedOrderServiceWsdl()) }
        val feature = parseContractFileToFeature(wsdlFile)
        val scenarioStub = ScenarioStub(
            request = orderDetailsRequest("xsi:type=\"ord:OrderDetails\""),
            response = HttpResponse(
                status = 200,
                body = orderDetailsResponseBody("ord:MissingOrderDetails"),
                headers = mapOf(CONTENT_TYPE to ContentType.Text.Xml.toString()),
            ),
        )

        val exception = assertThrows<Exception> {
            HttpStub(feature, listOf(scenarioStub)).close()
        }

        assertThat(exception.message).contains("Unknown type")
        assertThat(exception.message).contains("MissingOrderDetails")
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

private fun orderDetailsRequest(orderTypeAttribute: String): HttpRequest =
    HttpRequest(
        method = "POST",
        path = "/RetrieveOrderDetails",
        headers = mapOf(
            "SOAPAction" to "\"http://example.com/order-service/RetrieveOrderDetails\"",
            CONTENT_TYPE to ContentType.Text.Xml.toString(),
        ),
        body = StringValue(
            """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                <RetrieveOrderDetails xmlns="http://example.com/order-service">
                  <Message bodyType="XML" id="request-id" version="1.0" xmlns="http://example.com/order-model" xmlns:ord="http://example.com/order-model">
                    <command>
                      <retrieveOrderDetailsRequest id="command-id">
                        <order $orderTypeAttribute>
                          <orderNumber>100234569</orderNumber>
                        </order>
                      </retrieveOrderDetailsRequest>
                    </command>
                  </Message>
                </RetrieveOrderDetails>
              </s:Body>
            </s:Envelope>
            """.trimIndent()
        )
    )

private fun orderDetailsResponseBody(orderType: String = "ord:OrderDetails"): String =
    """
    <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
      <s:Body>
        <RetrieveOrderDetailsResponse xmlns="http://example.com/order-service">
          <Message bodyType="XML" id="response-id" version="1.0" xmlns="http://example.com/order-model" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:ord="http://example.com/order-model">
            <command>
              <retrieveOrderDetailsResponse id="response-command-id">
                <order xsi:type="$orderType">
                  <orderNumber>matched-from-external-example</orderNumber>
                </order>
              </retrieveOrderDetailsResponse>
            </command>
          </Message>
        </RetrieveOrderDetailsResponse>
      </s:Body>
    </s:Envelope>
    """.trimIndent()

private fun minimizedOrderServiceWsdl(): String =
    """
    <wsdl:definitions xmlns:tns="http://example.com/order-service" xmlns:ord="http://example.com/order-model" xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/" xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/" targetNamespace="http://example.com/order-service">
      <wsdl:binding name="OrderServiceBinding" type="tns:OrderService">
        <soap:binding transport="http://schemas.xmlsoap.org/soap/http"/>
        <wsdl:operation name="RetrieveOrderDetails">
          <soap:operation soapAction="http://example.com/order-service/RetrieveOrderDetails" style="document"/>
          <wsdl:input><soap:body use="literal"/></wsdl:input>
          <wsdl:output><soap:body use="literal"/></wsdl:output>
        </wsdl:operation>
      </wsdl:binding>
      <wsdl:message name="RetrieveOrderDetailsSoapIn">
        <wsdl:part name="parameters" element="tns:RetrieveOrderDetails"/>
      </wsdl:message>
      <wsdl:message name="RetrieveOrderDetailsSoapOut">
        <wsdl:part name="parameters" element="tns:RetrieveOrderDetailsResponse"/>
      </wsdl:message>
      <wsdl:portType name="OrderService">
        <wsdl:operation name="RetrieveOrderDetails">
          <wsdl:input message="tns:RetrieveOrderDetailsSoapIn"/>
          <wsdl:output message="tns:RetrieveOrderDetailsSoapOut"/>
        </wsdl:operation>
      </wsdl:portType>
      <wsdl:service name="OrderService">
        <wsdl:port name="OrderServicePort" binding="tns:OrderServiceBinding">
          <soap:address location="/RetrieveOrderDetails"/>
        </wsdl:port>
      </wsdl:service>
      <wsdl:types>
        <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" targetNamespace="http://example.com/order-service" elementFormDefault="qualified">
          <xsd:element name="RetrieveOrderDetails">
            <xsd:complexType>
              <xsd:sequence>
                <xsd:element minOccurs="0" maxOccurs="1" ref="ord:Message"/>
              </xsd:sequence>
            </xsd:complexType>
          </xsd:element>
          <xsd:element name="RetrieveOrderDetailsResponse">
            <xsd:complexType>
              <xsd:sequence>
                <xsd:element minOccurs="0" maxOccurs="1" ref="ord:Message"/>
              </xsd:sequence>
            </xsd:complexType>
          </xsd:element>
          <xsd:import namespace="http://example.com/order-model"/>
        </xsd:schema>
        <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" targetNamespace="http://example.com/order-model" elementFormDefault="qualified">
          <xsd:complexType name="Message">
            <xsd:sequence>
              <xsd:element minOccurs="0" maxOccurs="1" name="command" type="ord:Command"/>
            </xsd:sequence>
            <xsd:attribute name="bodyType" type="xsd:string" use="required"/>
            <xsd:attribute name="id" type="xsd:string" use="required"/>
            <xsd:attribute name="version" type="xsd:string" use="required"/>
          </xsd:complexType>
          <xsd:complexType name="Command">
            <xsd:choice minOccurs="1" maxOccurs="unbounded">
              <xsd:element minOccurs="0" maxOccurs="1" name="retrieveOrderDetailsRequest">
                <xsd:complexType>
                  <xsd:sequence minOccurs="0" maxOccurs="1">
                    <xsd:element minOccurs="0" maxOccurs="unbounded" name="order" type="ord:Order"/>
                  </xsd:sequence>
                  <xsd:attribute name="id" type="xsd:string" use="required"/>
                </xsd:complexType>
              </xsd:element>
              <xsd:element minOccurs="0" maxOccurs="1" name="retrieveOrderDetailsResponse">
                <xsd:complexType>
                  <xsd:sequence minOccurs="0" maxOccurs="1">
                    <xsd:element minOccurs="0" maxOccurs="unbounded" name="order" type="ord:Order"/>
                  </xsd:sequence>
                  <xsd:attribute name="id" type="xsd:string" use="required"/>
                </xsd:complexType>
              </xsd:element>
            </xsd:choice>
          </xsd:complexType>
          <xsd:complexType name="Order">
            <xsd:sequence minOccurs="0" maxOccurs="1">
              <xsd:element minOccurs="0" maxOccurs="1" name="orderNumber" type="xsd:string"/>
            </xsd:sequence>
          </xsd:complexType>
          <xsd:complexType name="OrderDetails">
            <xsd:complexContent>
              <xsd:extension base="ord:Order"/>
            </xsd:complexContent>
          </xsd:complexType>
          <xsd:element name="Message" type="ord:Message"/>
        </xsd:schema>
      </wsdl:types>
    </wsdl:definitions>
    """.trimIndent()
