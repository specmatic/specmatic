package io.specmatic.conversions

import io.specmatic.Utils.readTextResource
import io.specmatic.core.*
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.parsedValue
import io.specmatic.core.utilities.Flags
import io.specmatic.core.value.*
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.HttpStub
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.web.client.RestTemplate
import java.io.File
import java.net.URI
import java.util.function.Consumer

class WsdlKtTest {

    @BeforeEach
    fun setup() {
        val wsdlContent = """
            <?xml version="1.0"?>
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:qr="http://specmatic.io/SOAPService/"
                              targetNamespace="http://specmatic.io/SOAPService/">
                <wsdl:types>
                    <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                                targetNamespace="http://specmatic.io/SOAPService/">
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
                        <wsdl:input name="simpleInput"
                                    message="qr:simpleInputMessage"/>
                        <wsdl:output name="simpleOutput"
                                     message="qr:simpleOutputMessage"/>
                    </wsdl:operation>
                </wsdl:portType>

                <wsdl:binding name="simpleBinding" type="qr:simplePortType">
                    <soap:binding transport="http://schemas.xmlsoap.org/soap/http"/>
                    <wsdl:operation name="SimpleOperation">
                        <soap:operation
                                soapAction="http://specmatic.io/SOAPService/SimpleOperation"/>
                        <wsdl:input name="simpleInput">
                            <soap:body use="literal"/>
                        </wsdl:input>
                        <wsdl:output name="simpleOutput">
                            <soap:body use="literal"/>
                        </wsdl:output>
                    </wsdl:operation>
                </wsdl:binding>

                <wsdl:service name="simpleService">
                    <wsdl:port name="simplePort" binding="qr:simpleBinding">
                        <soap:address
                                location="http://specmatic.io/SOAPService/SimpleSOAP"/>
                    </wsdl:port>
                </wsdl:service>

            </wsdl:definitions>
        """.trimIndent()

        val wsdlFile = File("test.wsdl")
        wsdlFile.createNewFile()
        wsdlFile.writeText(wsdlContent)

        val wsdlContentWithOptionalAttributes = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:qr="http://specmatic.io/SOAPService/"
                              targetNamespace="http://specmatic.io/SOAPService/">
                <wsdl:types>
                    <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" targetNamespace="http://specmatic.io/SOAPService/">
                        <xsd:element name="SimpleResponse" type="xsd:string"/>
                        <xsd:element name="Person">
                            <xsd:complexType>
                                <xsd:sequence>
                                    <xsd:element name="Id" type="xsd:integer" />
                                    <xsd:element name="Name" type="xsd:string" />
                                </xsd:sequence>
                                <xs:attribute name="age" type="xs:integer"></xs:attribute>
                            </xsd:complexType>
                        </xsd:element>
                    </xsd:schema>
                </wsdl:types>

                <wsdl:message name="simpleInputMessage">
                    <wsdl:part name="simpleInputPart" element="qr:Person"/>
                </wsdl:message>
                <wsdl:message name="simpleOutputMessage">
                    <wsdl:part name="simpleOutputPart" element="qr:SimpleResponse"/>
                </wsdl:message>

                <wsdl:portType name="simplePortType">
                    <wsdl:operation name="SimpleOperation">
                        <wsdl:input name="simpleInput"
                                    message="qr:simpleInputMessage"/>
                        <wsdl:output name="simpleOutput"
                                     message="qr:simpleOutputMessage"/>
                    </wsdl:operation>
                </wsdl:portType>

                <wsdl:binding name="simpleBinding" type="qr:simplePortType">
                    <soap:binding transport="http://schemas.xmlsoap.org/soap/http"/>
                    <wsdl:operation name="SimpleOperation">
                        <soap:operation
                                soapAction="http://specmatic.io/SOAPService/SimpleOperation"/>
                        <wsdl:input name="simpleInput">
                            <soap:body use="literal"/>
                        </wsdl:input>
                        <wsdl:output name="simpleOutput">
                            <soap:body use="literal"/>
                        </wsdl:output>
                    </wsdl:operation>
                </wsdl:binding>

                <wsdl:service name="simpleService">
                    <wsdl:port name="simplePort" binding="qr:simpleBinding">
                        <soap:address
                                location="http://specmatic.io/SOAPService/SimpleSOAP"/>
                    </wsdl:port>
                </wsdl:service>

            </wsdl:definitions>
        """.trimIndent()
        val wsdlWithOptionalAttributesFile = File("test_with_optional_attributes.wsdl")
        wsdlWithOptionalAttributesFile.createNewFile()
        wsdlWithOptionalAttributesFile.writeText(wsdlContentWithOptionalAttributes)

        val wsdlContentWithMandatoryAttributes = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:qr="http://specmatic.io/SOAPService/"
                              targetNamespace="http://specmatic.io/SOAPService/">
                <wsdl:types>
                    <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" targetNamespace="http://specmatic.io/SOAPService/">
                        <xsd:element name="SimpleResponse" type="xsd:string"/>
                        <xsd:element name="Person">
                            <xsd:complexType>
                                <xsd:sequence>
                                    <xsd:element name="Id" type="xsd:integer" />
                                    <xsd:element name="Name" type="xsd:string" />
                                </xsd:sequence>
                                <xs:attribute name="age" type="xs:integer" use="required"></xs:attribute>
                            </xsd:complexType>
                        </xsd:element>
                    </xsd:schema>
                </wsdl:types>

                <wsdl:message name="simpleInputMessage">
                    <wsdl:part name="simpleInputPart" element="qr:Person"/>
                </wsdl:message>
                <wsdl:message name="simpleOutputMessage">
                    <wsdl:part name="simpleOutputPart" element="qr:SimpleResponse"/>
                </wsdl:message>

                <wsdl:portType name="simplePortType">
                    <wsdl:operation name="SimpleOperation">
                        <wsdl:input name="simpleInput"
                                    message="qr:simpleInputMessage"/>
                        <wsdl:output name="simpleOutput"
                                     message="qr:simpleOutputMessage"/>
                    </wsdl:operation>
                </wsdl:portType>

                <wsdl:binding name="simpleBinding" type="qr:simplePortType">
                    <soap:binding transport="http://schemas.xmlsoap.org/soap/http"/>
                    <wsdl:operation name="SimpleOperation">
                        <soap:operation
                                soapAction="http://specmatic.io/SOAPService/SimpleOperation"/>
                        <wsdl:input name="simpleInput">
                            <soap:body use="literal"/>
                        </wsdl:input>
                        <wsdl:output name="simpleOutput">
                            <soap:body use="literal"/>
                        </wsdl:output>
                    </wsdl:operation>
                </wsdl:binding>

                <wsdl:service name="simpleService">
                    <wsdl:port name="simplePort" binding="qr:simpleBinding">
                        <soap:address
                                location="http://specmatic.io/SOAPService/SimpleSOAP"/>
                    </wsdl:port>
                </wsdl:service>

            </wsdl:definitions>
        """.trimIndent()
        val wsdlWithMandatoryAttributesFile = File("test_with_mandatory_attributes.wsdl")
        wsdlWithMandatoryAttributesFile.createNewFile()
        wsdlWithMandatoryAttributesFile.writeText(wsdlContentWithMandatoryAttributes)
    }

    @Disabled
    fun `should create stub from gherkin that includes wsdl`() {
        val wsdlSpec = """
Feature: Hello world

Background:
  Given wsdl test.wsdl           
  
Scenario: test request returns test response
  When POST /SOAPService/SimpleSOAP
  And request-header SOAPAction "http://specmatic.io/SOAPService/SimpleOperation"
  And request-body <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><SimpleRequest>test request</SimpleRequest></soapenv:Body></soapenv:Envelope>
  Then status 200
  And response-body <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><SimpleResponse>test response</SimpleResponse></soapenv:Body></soapenv:Envelope>
        """.trimIndent()

        val wsdlFeature = parseGherkinStringToFeature(wsdlSpec)
        HttpStub(wsdlFeature).use {
            val soapRequest =
                """<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><SimpleRequest>WKTGM</SimpleRequest></soapenv:Body></soapenv:Envelope>"""

            val headers = HttpHeaders()
            headers.add("SOAPAction", """"http://specmatic.io/SOAPService/SimpleOperation"""")
            val response = RestTemplate().exchange(
                URI.create("http://localhost:9000/SOAPService/SimpleSOAP"),
                HttpMethod.POST,
                HttpEntity(soapRequest, headers),
                String::class.java
            )
            assertThat(response.statusCodeValue).isEqualTo(200)
            assertThat(response.body)
                .matches("""<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><SimpleResponse>[A-Z]*</SimpleResponse></soapenv:Body></soapenv:Envelope>""")

            val testRequest =
                """<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><SimpleRequest>test request</SimpleRequest></soapenv:Body></soapenv:Envelope>"""
            val testResponse = RestTemplate().exchange(
                URI.create("http://localhost:9000/SOAPService/SimpleSOAP"),
                HttpMethod.POST,
                HttpEntity(testRequest, headers),
                String::class.java
            )
            assertThat(testResponse.body)
                .matches("""<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><SimpleResponse>test response</SimpleResponse></soapenv:Body></soapenv:Envelope>""")
        }
    }

    @Test
    fun `should be able to stub a fault`() {
        val wsdlSpec = """
Feature: Hello world

Background:
  Given wsdl test.wsdl           
  
        """.trimIndent()

        val wsdlFeature = parseGherkinStringToFeature(wsdlSpec)

        HttpStub(wsdlFeature).use { stub ->
            val request = HttpRequest(
                "POST",
                "/SOAPService/SimpleSOAP",
                headers = mapOf(
                    "Content-Type" to "text/xml",
                    "SOAPAction" to """http://specmatic.io/SOAPService/SimpleOperation"""
                ),
                body = StringValue("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"><soapenv:Header/><soapenv:Body><SimpleRequest>test request</SimpleRequest></soapenv:Body></soapenv:Envelope>")
            )

            val response = HttpResponse(
                status = 200,
                body = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"><soapenv:Header/><soapenv:Body><soapenv:Fault></soapenv:Fault></soapenv:Body></soapenv:Envelope>"
            )

            stub.setExpectation(ScenarioStub(request, response))
        }

    }

    @Test
    fun `should create test from gherkin that includes wsdl`() {
        val wsdlSpec = """
Feature: Hello world

Background:
  Given wsdl test.wsdl           
  
Scenario: test request returns test response
  When POST /SOAPService/SimpleSOAP
  And request-header SOAPAction "http://specmatic.io/SOAPService/SimpleOperation"
  And request-body <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><SimpleRequest>test request</SimpleRequest></soapenv:Body></soapenv:Envelope>
  Then status 200
  And response-body <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><SimpleResponse>test response</SimpleResponse></soapenv:Body></soapenv:Envelope>
        """.trimIndent()

        val wsdlFeature = parseGherkinStringToFeature(wsdlSpec)
        val results = wsdlFeature.executeTests(
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(request.path).matches("""/SOAPService/SimpleSOAP""")
                    assertThat(listOf(
                        """http://specmatic.io/SOAPService/SimpleOperation""",
                        """"http://specmatic.io/SOAPService/SimpleOperation""""))
                        .contains(request.headers["SOAPAction"])
                    val responseBody = when {
                        request.bodyString.contains("test request") ->
                            """<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><SimpleResponse>test response</SimpleResponse></soapenv:Body></soapenv:Envelope>"""
                        else ->
                            """<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><SimpleResponse>WSDL</SimpleResponse></soapenv:Body></soapenv:Envelope>"""
                    }
                    return HttpResponse(200, responseBody, mapOf())
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            }
        )

        assertTrue(results.success(), results.report())
    }

    @Test
    fun `should create tests with the example value provided in examples for the an attribute in a complex element that is mandatory in the wsdl`() {
        val age = 33
        val wsdlSpec = """
Feature: WSDL Attribute Test

Background:
  Given wsdl test_with_mandatory_attributes.wsdl           
  
Scenario: test spec with mandatory attributes with examples
  When POST /SOAPService/SimpleSOAP
  And request-header SOAPAction "http://specmatic.io/SOAPService/SimpleOperation"
  And request-body <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><qr:Person age="11"><qr:Id>1</qr:Id><qr:Name>James Smith</qr:Name></qr:Person></soapenv:Body></soapenv:Envelope>
  Then status 200
  And response-body <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><SimpleResponse>test response</SimpleResponse></soapenv:Body></soapenv:Envelope>
  Examples:
      | (REQUEST-BODY) |
      | <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><qr:Person age="$age"><qr:Id>3</qr:Id><qr:Name>John Doe</qr:Name></qr:Person></soapenv:Body></soapenv:Envelope> |
        """.trimIndent()
        val wsdlFeature = parseGherkinStringToFeature(wsdlSpec)
        var countOfTestsWithAgeAttributeSetFromExamples = 0
        var requestCount = 0
        val results = wsdlFeature.executeTests(
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    logRequestCharacteristics(request, ++requestCount)
                    if (requestContainsPersonNodeWithAge(request, age)) countOfTestsWithAgeAttributeSetFromExamples++
                    val responseBody = """<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><SimpleResponse>WSDL</SimpleResponse></soapenv:Body></soapenv:Envelope>"""
                    return HttpResponse(200, responseBody, mapOf())
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            }
        )
        assertTrue(results.success(), results.report())
        assertThat(countOfTestsWithAgeAttributeSetFromExamples).isEqualTo(1)
    }

    @Test
    fun `should create tests with random values for an attribute in a complex element that is mandatory in the wsdl given no examples`() {
        val wsdlSpec = """
Feature: WSDL Attribute Test

Background:
  Given wsdl test_with_mandatory_attributes.wsdl           
  
Scenario: test spec with mandatory attributes without examples
  When POST /SOAPService/SimpleSOAP
  And request-header SOAPAction "http://specmatic.io/SOAPService/SimpleOperation"
  And request-body <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><qr:Person age="11"><qr:Id>1</qr:Id><qr:Name>James Smith</qr:Name></qr:Person></soapenv:Body></soapenv:Envelope>
  Then status 200
  And response-body <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><SimpleResponse>test response</SimpleResponse></soapenv:Body></soapenv:Envelope>
        """.trimIndent()

        val wsdlFeature = parseGherkinStringToFeature(wsdlSpec)
        var countOfTestsWithAgeAttributeSetToRandomValue = 0
        var requestCount = 0
        val results = wsdlFeature.executeTests(
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    logRequestCharacteristics(request, ++requestCount)
                    val responseBody = """<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><SimpleResponse>WSDL</SimpleResponse></soapenv:Body></soapenv:Envelope>"""
                    if (requestContainsPersonNodeWithRandomAge(request)) countOfTestsWithAgeAttributeSetToRandomValue++
                    return HttpResponse(200, responseBody, mapOf())
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            }
        )
        assertTrue(results.success(), results.report())
        assertThat(countOfTestsWithAgeAttributeSetToRandomValue).isEqualTo(2)
    }

    @Test
    fun `should create tests with the example value an attribute in a complex element that is mandatory in the wsdl given an RESPONSE-BODY example that has the attribute`() {
        val age = 44
        val wsdlSpec = """
Feature: WSDL Attribute Test

Background:
  Given wsdl test_with_optional_attributes.wsdl           
  
Scenario: test spec with optional attributes without examples
  When POST /SOAPService/SimpleSOAP
  And request-header SOAPAction "http://specmatic.io/SOAPService/SimpleOperation"
  And request-body <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><qr:Person age="11"><qr:Id>1</qr:Id><qr:Name>James Smith</qr:Name></qr:Person></soapenv:Body></soapenv:Envelope>
  Then status 200
  And response-body <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><SimpleResponse>test response</SimpleResponse></soapenv:Body></soapenv:Envelope>
  Examples:
      | (REQUEST-BODY) |
      | <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><qr:Person age="$age"><qr:Id>4</qr:Id><qr:Name>John Doe</qr:Name></qr:Person></soapenv:Body></soapenv:Envelope> |
        """.trimIndent()

        val wsdlFeature = parseGherkinStringToFeature(wsdlSpec)
        var countOfTestsWithAgeAttributeSetFromExamples = 0
        var requestCount = 0
        val results = wsdlFeature.executeTests(
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    logRequestCharacteristics(request, ++requestCount)
                    val responseBody = """<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><SimpleResponse>WSDL</SimpleResponse></soapenv:Body></soapenv:Envelope>"""
                    if (requestContainsPersonNodeWithAge(request, age)) countOfTestsWithAgeAttributeSetFromExamples++
                    return HttpResponse(200, responseBody, mapOf())
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            }
        )
        assertTrue(results.success(), results.report())
        assertThat(countOfTestsWithAgeAttributeSetFromExamples).isEqualTo(1)
    }

    @Test
    fun `should create tests with the example value an attribute in a complex element that is mandatory in the wsdl given an RESPONSE-BODY example that does not have the attribute`() {
        val wsdlSpec = """
Feature: WSDL Attribute Test

Background:
  Given wsdl test_with_optional_attributes.wsdl           
  
Scenario: test spec with optional attributes without examples
  When POST /SOAPService/SimpleSOAP
  And request-header SOAPAction "http://specmatic.io/SOAPService/SimpleOperation"
  And request-body <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><qr:Person age="11"><qr:Id>1</qr:Id><qr:Name>James Smith</qr:Name></qr:Person></soapenv:Body></soapenv:Envelope>
  Then status 200
  And response-body <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><SimpleResponse>test response</SimpleResponse></soapenv:Body></soapenv:Envelope>
  Examples:
      | (REQUEST-BODY) |
      | <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><qr:Person><qr:Id>5</qr:Id><qr:Name>Jane Doe</qr:Name></qr:Person></soapenv:Body></soapenv:Envelope> |
        """.trimIndent()

        val wsdlFeature = parseGherkinStringToFeature(wsdlSpec)
        var countOfTestsWithoutTheAgeAttribute = 0
        var requestCount = 0
        val results = wsdlFeature.executeTests(
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    logRequestCharacteristics(request, ++requestCount)
                    val responseBody = """<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><SimpleResponse>WSDL</SimpleResponse></soapenv:Body></soapenv:Envelope>"""
                    if (requestContainsPersonNodeWithoutAgeAttribute(request)) countOfTestsWithoutTheAgeAttribute++
                    return HttpResponse(200, responseBody, mapOf())
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            }
        )
        assertTrue(results.success(), results.report())
        assertThat(countOfTestsWithoutTheAgeAttribute).isEqualTo(1)
    }

    @Test
    fun `should create tests with random values for an attribute in a complex element that is optional in the wsdl given no examples`() {
        val wsdlSpec = """
Feature: Hello world

Background:
  Given wsdl test_with_optional_attributes.wsdl           
  
Scenario: test request returns test response
  When POST /SOAPService/SimpleSOAP
  And request-header SOAPAction "http://specmatic.io/SOAPService/SimpleOperation"
  And request-body <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><qr:Person age="11"><qr:Id>1</qr:Id><qr:Name>James Smith</qr:Name></qr:Person></soapenv:Body></soapenv:Envelope>
  Then status 200
  And response-body <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><SimpleResponse>test response</SimpleResponse></soapenv:Body></soapenv:Envelope>
        """.trimIndent()

        val wsdlFeature = parseGherkinStringToFeature(wsdlSpec)
        var countOfTestsWithAgeAttributeSetToRandomValue = 0
        var requestCount = 0
        val results = wsdlFeature.executeTests(
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    logRequestCharacteristics(request, ++requestCount)
                    val responseBody = """<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><SimpleResponse>WSDL</SimpleResponse></soapenv:Body></soapenv:Envelope>"""
                    if (requestContainsPersonNodeWithRandomAge(request)) countOfTestsWithAgeAttributeSetToRandomValue++
                    return HttpResponse(200, responseBody, mapOf())
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            }
        )
        assertTrue(results.success(), results.report())
        assertThat(countOfTestsWithAgeAttributeSetToRandomValue).isGreaterThanOrEqualTo(2)
    }


    @Test
    fun `should match soap request with attribute which is mandatory in the wsdl`() {
        val wsdlFeature = parseContractFileToFeature(File("test_with_mandatory_attributes.wsdl"))
        val soapRequest = HttpRequest(
            "POST",
            "/SOAPService/SimpleSOAP",
            mapOf(
                "SOAPAction" to "http://specmatic.io/SOAPService/SimpleOperation",
                "Content-Type" to "application/xml"
            ),
            body = parsedValue("""<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><qr:Person age="22"><qr:Id>2</qr:Id><qr:Name>James Taylor</qr:Name></qr:Person></soapenv:Body></soapenv:Envelope>""")
        )
        val wsdlScenario = wsdlFeature.scenarios.single()
        val result = wsdlScenario.httpRequestPattern.matches(soapRequest, wsdlScenario.resolver)
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should not match soap request which does not have an attribute which is mandatory in the wsdl`() {
        val wsdlFeature = parseContractFileToFeature(File("test_with_mandatory_attributes.wsdl"))
        val soapRequest = HttpRequest(
            "POST",
            "/SOAPService/SimpleSOAP",
            mapOf(
                "SOAPAction" to "http://specmatic.io/SOAPService/SimpleOperation",
                "Content-Type" to "application/xml"
            ),
            body = parsedValue("""<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><qr:Person><qr:Id>2</qr:Id><qr:Name>James Taylor</qr:Name></qr:Person></soapenv:Body></soapenv:Envelope>""")
        )
        val wsdlScenario = wsdlFeature.scenarios.single()
        val result = wsdlScenario.httpRequestPattern.matches(soapRequest, wsdlScenario.resolver)
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).contains("Expected attribute named \"age\" was missing")
    }

    @Test
    fun `should match soap request with attribute which is optional in the wsdl`() {
        val wsdlFeature = parseContractFileToFeature(File("test_with_optional_attributes.wsdl"))
        val soapRequest = HttpRequest(
            "POST",
            "/SOAPService/SimpleSOAP",
            mapOf(
                "SOAPAction" to "http://specmatic.io/SOAPService/SimpleOperation",
                "Content-Type" to "application/xml"
            ),
            body = parsedValue("""<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><qr:Person age="22"><qr:Id>2</qr:Id><qr:Name>James Taylor</qr:Name></qr:Person></soapenv:Body></soapenv:Envelope>""")
        )
        val wsdlScenario = wsdlFeature.scenarios.single()
        val result = wsdlScenario.httpRequestPattern.matches(soapRequest, wsdlScenario.resolver)
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should match soap request which does not have an attribute which is optional in the wsdl`() {
        val wsdlFeature = parseContractFileToFeature(File("test_with_optional_attributes.wsdl"))
        val soapRequest = HttpRequest(
            "POST",
            "/SOAPService/SimpleSOAP",
            mapOf(
                "SOAPAction" to "http://specmatic.io/SOAPService/SimpleOperation",
                "Content-Type" to "application/xml"
            ),
            body = parsedValue("""<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><qr:Person><qr:Id>2</qr:Id><qr:Name>James Taylor</qr:Name></qr:Person></soapenv:Body></soapenv:Envelope>""")
        )
        val wsdlScenario = wsdlFeature.scenarios.single()
        val result = wsdlScenario.httpRequestPattern.matches(soapRequest, wsdlScenario.resolver)
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should throw exception when mandatory attribute is not set`() {
        val wsdlSpec = """
Feature: Hello world

Background:
  Given wsdl test_with_mandatory_attributes.wsdl           
  
Scenario: test request returns test response
  When POST /SOAPService/SimpleSOAP
  And request-header SOAPAction "http://specmatic.io/SOAPService/SimpleOperation"
  And request-body <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><qr:Person><qr:Id>1</qr:Id><qr:Name>John Doe</qr:Name></qr:Person></soapenv:Body></soapenv:Envelope>
  Then status 200
  And response-body <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><SimpleResponse>test response</SimpleResponse></soapenv:Body></soapenv:Envelope>
        """.trimIndent()
        val exception = assertThrows<ContractException> {
            parseGherkinStringToFeature(wsdlSpec)
        }
        assertThat(exception.message == "test request returns test response\" request is not as per included wsdl / OpenApi spec")
    }

    @Disabled
    fun `should report error in test with both OpenAPI and Gherkin scenario names`() {
        val wsdlSpec = """
Feature: Hello world

Background:
  Given wsdl test.wsdl           
  
Scenario: test request returns test response
  When POST /SOAPService/SimpleSOAP
  And request-header SOAPAction "http://specmatic.io/SOAPService/SimpleOperation"
  And request-body <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><SimpleRequest>test request</SimpleRequest></soapenv:Body></soapenv:Envelope>
  Then status 200
  And response-body <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><SimpleResponse>test response</SimpleResponse></soapenv:Body></soapenv:Envelope>
        """.trimIndent()

        val wsdlFeature = parseGherkinStringToFeature(wsdlSpec)
        val results = wsdlFeature.executeTests(
            object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    return HttpResponse(200, "", mapOf())
                }

                override fun setServerState(serverState: Map<String, Value>) {
                }
            }
        )

        assertFalse(results.success(), results.report())
        assertThat(results.report()).isEqualTo("""
            In scenario "test request returns test response"
            >> RESPONSE.BODY.Envelope

            Expected xml, got string

            In scenario "SimpleOperation"
            >> RESPONSE.BODY.Envelope

            Expected xml, got string

            In scenario "SimpleOperation"
            >> RESPONSE.BODY.Envelope

            Expected xml, got string
        """.trimIndent())
    }

    @Test
    fun `should throw error when request in Gherkin scenario does not match included wsdl spec`() {
        val wsdlSpec = """
Feature: Hello world

Background:
  Given wsdl test.wsdl           
  
Scenario: request not matching wsdl
  When POST /SOAPService/SimpleSOAP2
  And request-header SOAPAction "http://specmatic.io/SOAPService/AnotherOperation"
  And request-body <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><SimpleRequest>test request</SimpleRequest></soapenv:Body></soapenv:Envelope>
  Then status 200
        """.trimIndent()

        assertThatThrownBy {
            parseGherkinStringToFeature(wsdlSpec)
        }.satisfies(Consumer {
            assertThat(it.message).isEqualTo("""Scenario: "request not matching wsdl" request is not as per included wsdl / OpenApi spec""")
        })
    }

    @Test
    fun `should load child wsdl and schema imports`() {
        val wsdlXML = readTextResource("wsdl/parent.wsdl").toXML()
        val wsdl = WSDL(wsdlXML, "src/test/resources/wsdl/parent.wsdl")

        println(wsdl)
    }

    @Test
    fun `should use escaped soap-action if specified by system property`() {
        val wsdlSpec = """
Feature: WSDL Attribute Test

Background:
  Given wsdl test_with_mandatory_attributes.wsdl           
  
Scenario: test spec with mandatory attributes without examples
  When POST /SOAPService/SimpleSOAP
  And request-header SOAPAction "http://specmatic.io/SOAPService/SimpleOperation"
  And request-body <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><qr:Person age="11"><qr:Id>1</qr:Id><qr:Name>James Smith</qr:Name></qr:Person></soapenv:Body></soapenv:Envelope>
  Then status 200
  And response-body <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><SimpleResponse>test response</SimpleResponse></soapenv:Body></soapenv:Envelope>
        """.trimIndent()
        val wsdlFeature = parseGherkinStringToFeature(wsdlSpec)
        var countWithEscapedAction = 0

        val results = Flags.using(Flags.SPECMATIC_ESCAPE_SOAP_ACTION to "true") {
            wsdlFeature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    logRequestCharacteristics(request, countWithEscapedAction)
                    val responseBody = """<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><SimpleResponse>WSDL</SimpleResponse></soapenv:Body></soapenv:Envelope>"""
                    if (request.headers["SOAPAction"]?.startsWith("\"") == true) countWithEscapedAction++
                    return HttpResponse(200, responseBody)
                }
            })
        }

        assertTrue(results.success(), results.report())
        assertThat(countWithEscapedAction).isEqualTo(2)
    }

    @AfterEach
    fun teardown() {
        File("test.wsdl").delete()
        File("test_with_optional_attributes.wsdl").delete()
        File("test_with_mandatory_attributes.wsdl").delete()
    }


    fun requestContainsPersonNodeWithAge(request:HttpRequest, age:Int): Boolean {
        val personAge = getPersonAge(request) ?: return false
        return age == personAge
    }

    fun requestContainsPersonNodeWithRandomAge(request:HttpRequest): Boolean {
        val personAge = getPersonAge(request) ?: return false
        return personAge > 0
    }

    fun requestContainsPersonNodeWithoutAgeAttribute(request:HttpRequest): Boolean {
        val personNode = getPersonNode(request)
        return personNode?.attributes?.keys?.none { it == "age" } ?: true
    }

    private fun getPersonAge(request: HttpRequest): Int? {
        val personNode = getPersonNode(request)
        return personNode?.attributes?.get("age")?.toStringLiteral()?.toInt()
    }

    private fun getPersonNode(request: HttpRequest): XMLNode? {
        return (request.body as XMLNode).findFirstChildByPath("Body.Person")
    }

    private fun logRequestCharacteristics(request: HttpRequest, count:Int) {
        println("Soap Request {$count}:")
        println("Headers:")
        val soapActionHeader = request.headers["SOAPAction"]
        soapActionHeader?.let {
            when (soapActionHeader.startsWith("\"") && soapActionHeader.endsWith("\"")) {
                true -> println("Contains SOAPAction header with double quotes")
                else -> println("Contains SOAPAction header without double quotes")
            }
        } ?: println("SOAPAction header is null")
        println("Body:")
        when((request.body as XMLNode).findFirstChildByPath("Header")) {
            null -> println("SOAP Env Header is missing in body")
            else -> println("SOAP Env Header is present in body")
        }
        when(requestContainsPersonNodeWithoutAgeAttribute(request)) {
            true -> println("Age attribute is not present in Person node")
            else -> println("Age attribute is present in Person node")
        }

        println()
    }
}