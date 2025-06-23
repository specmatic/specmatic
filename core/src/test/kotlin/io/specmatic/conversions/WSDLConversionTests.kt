package io.specmatic.conversions

import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.WSDL
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.stream.Stream

class WSDLConversionTests {
    @Test
    fun `simple types in request body`() {
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
        """

        val wsdl = WSDL(toXMLNode(wsdlContent), "/path/to/wsdl.xml")

        val expectedGherkin = """
        Feature: simpleService

            Scenario: SimpleOperation
                When POST /SOAPService/SimpleSOAP
                And enum SoapAction (string) values "http://specmatic.io/SOAPService/SimpleOperation",http://specmatic.io/SOAPService/SimpleOperation
                And request-header SOAPAction (SoapAction)
                And request-body
                ""${'"'}
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header specmatic_occurs="optional"/><soapenv:Body><SimpleRequest>(string)</SimpleRequest></soapenv:Body></soapenv:Envelope>
                ""${'"'}
                Then status 200
                And response-body
                ""${'"'}
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header specmatic_occurs="optional"/><soapenv:Body><SimpleResponse>(string)</SimpleResponse></soapenv:Body></soapenv:Envelope>
                ""${'"'}
        """.trimIndent().trim()

        val actualGherkin = wsdl.convertToGherkin()

        assertThat(actualGherkin).isEqualToIgnoringWhitespace(expectedGherkin)
    }

    @Test
    fun `simpleTypes nodes in request body`() {
        val wsdlContent = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:qr="http://specmatic.io/SOAPService/"
                              targetNamespace="http://specmatic.io/SOAPService/">
                <wsdl:types>
                    <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" targetNamespace="http://specmatic.io/SOAPService/">
                        <xsd:element name="SimpleRequest" type="qr:name"/>
                        <xsd:element name="SimpleResponse" type="xsd:string"/>
                        <xsd:simpleType name="name">
                            <xsd:restriction base="xsd:string">
                                <xsd:maxLength value="1"/>
                            </xsd:restriction>
                        </xsd:simpleType>
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
        """

        val wsdl = WSDL(toXMLNode(wsdlContent), "/path/to/wsdl.xml")

        val expectedGherkin = """
        Feature: simpleService

            Scenario: SimpleOperation
                When POST /SOAPService/SimpleSOAP
                And enum SoapAction (string) values "http://specmatic.io/SOAPService/SimpleOperation",http://specmatic.io/SOAPService/SimpleOperation
                And request-header SOAPAction (SoapAction)
                And request-body
                ""${'"'}
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header specmatic_occurs="optional"/><soapenv:Body><SimpleRequest>(string)</SimpleRequest></soapenv:Body></soapenv:Envelope>
                ""${'"'}
                Then status 200
                And response-body
                ""${'"'}
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header specmatic_occurs="optional"/><soapenv:Body><SimpleResponse>(string)</SimpleResponse></soapenv:Body></soapenv:Envelope>
                ""${'"'}
        """.trimIndent().trim()

        val actualGherkin = wsdl.convertToGherkin()

        assertThat(actualGherkin).isEqualToIgnoringWhitespace(expectedGherkin)
    }

    @Test
    fun `complex type inline in request body`() {
        val wsdlContent = """
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
        """

        val wsdl = WSDL(toXMLNode(wsdlContent), "/path/to/wsdl.xml")

        val expectedGherkin = """
            Feature: simpleService
            
                Scenario: SimpleOperation
                    Given type SimpleOperation_SOAPPayload_Input
                    ""${'"'}
                    <SPECMATIC_TYPE>
                      <Id>(number)</Id>
                      <Name>(string)</Name>
                    </SPECMATIC_TYPE>
                    ""${'"'}
                    When POST /SOAPService/SimpleSOAP
                    And enum SoapAction (string) values "http://specmatic.io/SOAPService/SimpleOperation",http://specmatic.io/SOAPService/SimpleOperation
                    And request-header SOAPAction (SoapAction)
                    And request-body
                    ""${'"'}
                    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
                      <soapenv:Header specmatic_occurs="optional"/>
                      <soapenv:Body>
                        <Person specmatic_type="SimpleOperation_SOAPPayload_Input"/>
                      </soapenv:Body>
                    </soapenv:Envelope>
                    ""${'"'}
                    Then status 200
                    And response-body
                    ""${'"'}
                    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header specmatic_occurs="optional"/><soapenv:Body><SimpleResponse>(string)</SimpleResponse></soapenv:Body></soapenv:Envelope>
                    ""${'"'}
        """.trimIndent().trim()

        val actualGherkin = wsdl.convertToGherkin()

        assertThat(actualGherkin).isEqualToIgnoringWhitespace(expectedGherkin)
    }

    @Test
    fun `point to complex type in request body`() {
        val wsdlContent = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:qr="http://specmatic.io/SOAPService/"
                              targetNamespace="http://specmatic.io/SOAPService/">
                <wsdl:types>
                    <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" targetNamespace="http://specmatic.io/SOAPService/">
                        <xsd:element name="SimpleRequest" type="qr:PersonType"/>
                        <xsd:element name="SimpleResponse" type="xsd:string"/>
                        <xsd:complexType name="PersonType">
                            <xsd:sequence>
                                <xsd:element name="Id" type="xsd:integer" />
                                <xsd:element name="Name" type="xsd:string" />
                            </xsd:sequence>
                        </xsd:complexType>
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
        """

        val wsdl = WSDL(toXMLNode(wsdlContent), "/path/to/wsdl.xml")

        val expectedGherkin = """
            Feature: simpleService
            
                Scenario: SimpleOperation
                    Given type SimpleOperation_SOAPPayload_Input
                    ""${'"'}
                    <SPECMATIC_TYPE>
                      <Id>(number)</Id>
                      <Name>(string)</Name>
                    </SPECMATIC_TYPE>
                    ""${'"'}
                    When POST /SOAPService/SimpleSOAP
                    And enum SoapAction (string) values "http://specmatic.io/SOAPService/SimpleOperation",http://specmatic.io/SOAPService/SimpleOperation
                    And request-header SOAPAction (SoapAction)
                    And request-body
                    ""${'"'}
                    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
                      <soapenv:Header specmatic_occurs="optional"/>
                      <soapenv:Body>
                        <SimpleRequest specmatic_type="SimpleOperation_SOAPPayload_Input"/>
                      </soapenv:Body>
                    </soapenv:Envelope>
                    ""${'"'}
                    Then status 200
                    And response-body
                    ""${'"'}
                    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header specmatic_occurs="optional"/><soapenv:Body><SimpleResponse>(string)</SimpleResponse></soapenv:Body></soapenv:Envelope>
                    ""${'"'}
        """.trimIndent().trim()

        val actualGherkin = wsdl.convertToGherkin()

        assertThat(actualGherkin).isEqualToIgnoringWhitespace(expectedGherkin)
    }

    @Test
    fun `type ref in request body`() {
        val wsdlContent = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:qr="http://specmatic.io/SOAPService/"
                              targetNamespace="http://specmatic.io/SOAPService/">
                <wsdl:types>
                    <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" targetNamespace="http://specmatic.io/SOAPService/">
                        <xsd:element name="SimpleRequest2" type="xsd:string"/>
                        <xsd:complexType name="SimpleRequest">
                            <xsd:sequence>
                                <xsd:element name="Name" type="xsd:string" />
                            </xsd:sequence>
                        </xsd:complexType>
                        <xsd:element name="SimpleResponse" type="xsd:string"/>
                    </xsd:schema>
                </wsdl:types>

                <wsdl:message name="simpleInputMessage">
                    <wsdl:part name="simpleInputPart" type="qr:SimpleRequest"/>
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
        """

        val xml = toXMLNode(wsdlContent)
        val wsdl = WSDL(xml, "/path/to/wsdl.xml")

        val expectedGherkin = """
            Feature: simpleService
            
                Scenario: SimpleOperation
                    Given type qr_SimpleRequest
                    ""${'"'}
                    <SPECMATIC_TYPE>
                      <Name>(string)</Name>
                    </SPECMATIC_TYPE>
                    ""${'"'}
                    And type SimpleOperationInput 
                    ""${'"'}
                    <SPECMATIC_TYPE>
                      <simpleInputPart specmatic_type="qr_SimpleRequest"/>
                    </SPECMATIC_TYPE>
                    ""${'"'}
                    When POST /SOAPService/SimpleSOAP
                    And enum SoapAction (string) values "http://specmatic.io/SOAPService/SimpleOperation",http://specmatic.io/SOAPService/SimpleOperation
                    And request-header SOAPAction (SoapAction)
                    And request-body
                    ""${'"'}
                    <soapenv:Envelope xmlns:SOAPService="http://specmatic.io/SOAPService/" xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
                      <soapenv:Header specmatic_occurs="optional"/>
                      <soapenv:Body>
                        <qr:simpleInputMessage specmatic_type="SimpleOperationInput "/>
                      </soapenv:Body>
                    </soapenv:Envelope>
                    ""${'"'}
                    Then status 200
                    And response-body
                    ""${'"'}
                    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header specmatic_occurs="optional"/><soapenv:Body><SimpleResponse>(string)</SimpleResponse></soapenv:Body></soapenv:Envelope>
                    ""${'"'}
        """.trimIndent().trim()

        val actualGherkin = wsdl.convertToGherkin()

        assertThat(actualGherkin).isEqualToIgnoringWhitespace(expectedGherkin)
    }

    @Test
    fun `parse WSDL with optional attribute in complex type`() {
        val optionalAttribute = "age"

        val wsdlContent = """
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
                                <xs:attribute name="$optionalAttribute" type="xs:integer"></xs:attribute>
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
        """

        val wsdl = WSDL(toXMLNode(wsdlContent), "/path/to/wsdl.xml")

        val expectedGherkin = """
            Feature: simpleService

                Scenario: SimpleOperation
                    Given type SimpleOperation_SOAPPayload_Input
                    ""${'"'}
                    <SPECMATIC_TYPE>
                      <Id>(number)</Id>
                      <Name>(string)</Name>
                    </SPECMATIC_TYPE>
                    ""${'"'}
                    When POST /SOAPService/SimpleSOAP
                    And enum SoapAction (string) values "http://specmatic.io/SOAPService/SimpleOperation",http://specmatic.io/SOAPService/SimpleOperation
                    And request-header SOAPAction (SoapAction)
                    And request-body
                    ""${'"'}
                    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
                      <soapenv:Header specmatic_occurs="optional"/>
                      <soapenv:Body>
                        <Person $optionalAttribute.opt="(number)" specmatic_type="SimpleOperation_SOAPPayload_Input"/>
                      </soapenv:Body>
                    </soapenv:Envelope>
                    ""${'"'}
                    Then status 200
                    And response-body
                    ""${'"'}
                    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header specmatic_occurs="optional"/><soapenv:Body><SimpleResponse>(string)</SimpleResponse></soapenv:Body></soapenv:Envelope>
                    ""${'"'}
        """.trimIndent().trim()

        val actualGherkin = wsdl.convertToGherkin()

        assertThat(actualGherkin).isEqualToIgnoringWhitespace(expectedGherkin)
    }

    @Test
    fun `parse WSDL with mandatory attribute in complex type`() {
        val mandatoryAttribute = "age"

        val wsdlContent = """
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
                                <xs:attribute name="$mandatoryAttribute" type="xs:integer" use="required"></xs:attribute>
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
        """

        val wsdl = WSDL(toXMLNode(wsdlContent), "/path/to/wsdl.xml")

        val expectedGherkin = """
            Feature: simpleService

                Scenario: SimpleOperation
                    Given type SimpleOperation_SOAPPayload_Input
                    ""${'"'}
                    <SPECMATIC_TYPE>
                      <Id>(number)</Id>
                      <Name>(string)</Name>
                    </SPECMATIC_TYPE>
                    ""${'"'}
                    When POST /SOAPService/SimpleSOAP
                    And enum SoapAction (string) values "http://specmatic.io/SOAPService/SimpleOperation",http://specmatic.io/SOAPService/SimpleOperation
                    And request-header SOAPAction (SoapAction)
                    And request-body
                    ""${'"'}
                    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
                      <soapenv:Header specmatic_occurs="optional"/>
                      <soapenv:Body>
                        <Person $mandatoryAttribute="(number)" specmatic_type="SimpleOperation_SOAPPayload_Input"/>
                      </soapenv:Body>
                    </soapenv:Envelope>
                    ""${'"'}
                    Then status 200
                    And response-body
                    ""${'"'}
                    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header specmatic_occurs="optional"/><soapenv:Body><SimpleResponse>(string)</SimpleResponse></soapenv:Body></soapenv:Envelope>
                    ""${'"'}
        """.trimIndent().trim()

        val actualGherkin = wsdl.convertToGherkin()

        assertThat(actualGherkin).isEqualToIgnoringWhitespace(expectedGherkin)
    }

    @Nested
    inner class OccurrenceInSimpleElement {
        private fun wsdlContent(occurrence: String) = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:qr="http://specmatic.io/SOAPService/"
                              targetNamespace="http://specmatic.io/SOAPService/">
                <wsdl:types>
                    <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" targetNamespace="http://specmatic.io/SOAPService/">
                        <xsd:element name="SimpleRequest" type="qr:PersonType"/>
                        <xsd:element name="SimpleResponse" type="xsd:string"/>
                        <xsd:complexType name="PersonType">
                            <xsd:sequence>
                                <xsd:element name="Id" type="xsd:integer" />
                                <xsd:element name="Name" $occurrence type="xsd:string" />
                            </xsd:sequence>
                        </xsd:complexType>
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
        """

        @Test
        fun `list of simple xml nodes in request body`() {
            val expectedGherkin = """
            Feature: simpleService
            
                Scenario: SimpleOperation
                    Given type SimpleOperation_SOAPPayload_Input
                    ""${'"'}
                    <SPECMATIC_TYPE>
                      <Id>(number)</Id>
                      <Name specmatic_occurs="multiple">(string)</Name>
                    </SPECMATIC_TYPE>
                    ""${'"'}
                    When POST /SOAPService/SimpleSOAP
                    And enum SoapAction (string) values "http://specmatic.io/SOAPService/SimpleOperation",http://specmatic.io/SOAPService/SimpleOperation
                    And request-header SOAPAction (SoapAction)
                    And request-body
                    ""${'"'}
                    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
                      <soapenv:Header specmatic_occurs="optional"/>
                      <soapenv:Body>
                        <SimpleRequest specmatic_type="SimpleOperation_SOAPPayload_Input"/>
                      </soapenv:Body>
                    </soapenv:Envelope>
                    ""${'"'}
                    Then status 200
                    And response-body
                    ""${'"'}
                    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header specmatic_occurs="optional"/><soapenv:Body><SimpleResponse>(string)</SimpleResponse></soapenv:Body></soapenv:Envelope>
                    ""${'"'}
                """.trimIndent().trim()

            WSDL(
                toXMLNode(wsdlContent("""minOccurs="0" maxOccurs="unbounded" """)),
                "/path/to/wsdl.xml"
            ).let { wsdl ->
                val actualGherkin = wsdl.convertToGherkin()

                assertThat(actualGherkin).isEqualToIgnoringWhitespace(expectedGherkin)
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["""minOccurs="0" """, """minOccurs="0" maxOccurs="1""""])
        fun `optional simple xml nodes in request body`(occurrence: String) {
            val expectedGherkin = """
                Feature: simpleService
                
                    Scenario: SimpleOperation
                        Given type SimpleOperation_SOAPPayload_Input
                        ""${'"'}
                        <SPECMATIC_TYPE>
                          <Id>(number)</Id>
                          <Name specmatic_occurs="optional">(string)</Name>
                        </SPECMATIC_TYPE>
                        ""${'"'}
                        When POST /SOAPService/SimpleSOAP
                        And enum SoapAction (string) values "http://specmatic.io/SOAPService/SimpleOperation",http://specmatic.io/SOAPService/SimpleOperation
                        And request-header SOAPAction (SoapAction)
                        And request-body
                        ""${'"'}
                        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
                          <soapenv:Header specmatic_occurs="optional"/>
                          <soapenv:Body>
                            <SimpleRequest specmatic_type="SimpleOperation_SOAPPayload_Input"/>
                          </soapenv:Body>
                        </soapenv:Envelope>
                        ""${'"'}
                        Then status 200
                        And response-body
                        ""${'"'}
                        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header specmatic_occurs="optional"/><soapenv:Body><SimpleResponse>(string)</SimpleResponse></soapenv:Body></soapenv:Envelope>
                        ""${'"'}
                """.trimIndent().trim()

            WSDL(
                toXMLNode(wsdlContent(occurrence)),
                "/path/to/wsdl.xml"
            ).let { wsdl ->
                val actualGherkin = wsdl.convertToGherkin().trim()

                assertThat(actualGherkin).isEqualToIgnoringWhitespace(expectedGherkin)
            }
        }
    }

    @Nested
    inner class OccurrenceInComplexElement {
        private val wsdlContent = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:qr="http://specmatic.io/SOAPService/"
                              targetNamespace="http://specmatic.io/SOAPService/">
                <wsdl:types>
                    <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" targetNamespace="http://specmatic.io/SOAPService/">
                        <xsd:element name="SimpleRequest">
                            <xsd:complexType>
                                <xsd:sequence>
                                    <xsd:element name="Person" type="qr:PersonType" #occurrence# />
                                </xsd:sequence>
                            </xsd:complexType>
                        </xsd:element>
                        <xsd:element name="SimpleResponse" type="xsd:string"/>
                        <xsd:complexType name="PersonType">
                            <xsd:sequence>
                                <xsd:element name="Id" type="xsd:integer" />
                                <xsd:element name="Name" type="xsd:string" />
                            </xsd:sequence>
                        </xsd:complexType>
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
        """

        @Test
        fun `list of simple xml nodes in request body`() {
            val expectedGherkin = """
                Feature: simpleService
                
                    Scenario: SimpleOperation
                        Given type qr_PersonType
                        ""${'"'}
                        <SPECMATIC_TYPE>
                          <Id>(number)</Id>
                          <Name>(string)</Name>
                        </SPECMATIC_TYPE>
                        ""${'"'}
                        And type SimpleOperation_SOAPPayload_Input
                        ""${'"'}
                        <SPECMATIC_TYPE>
                          <Person specmatic_type="qr_PersonType" specmatic_occurs="multiple"/>
                        </SPECMATIC_TYPE>
                        ""${'"'}
                        When POST /SOAPService/SimpleSOAP
                        And enum SoapAction (string) values "http://specmatic.io/SOAPService/SimpleOperation",http://specmatic.io/SOAPService/SimpleOperation
                        And request-header SOAPAction (SoapAction)
                        And request-body
                        ""${'"'}
                        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
                          <soapenv:Header specmatic_occurs="optional"/>
                          <soapenv:Body>
                            <SimpleRequest specmatic_type="SimpleOperation_SOAPPayload_Input"/>
                          </soapenv:Body>
                        </soapenv:Envelope>
                        ""${'"'}
                        Then status 200
                        And response-body
                        ""${'"'}
                        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header specmatic_occurs="optional"/><soapenv:Body><SimpleResponse>(string)</SimpleResponse></soapenv:Body></soapenv:Envelope>
                        ""${'"'}
            """.trimIndent().trim()

            WSDL(
                toXMLNode(wsdlContent.replace("#occurrence#", """minOccurs="0" maxOccurs="unbounded" """)),
                "/path/to/wsdl.xml"
            ).let { wsdl ->
                val actualGherkin = wsdl.convertToGherkin().trim()

                assertThat(actualGherkin).isEqualToIgnoringWhitespace(expectedGherkin)
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["""minOccurs="0" """, """minOccurs="0" maxOccurs="1" """])
        fun `optional simple xml nodes in request body`(occurrence: String) {
            val expectedGherkin = """
                Feature: simpleService
                
                    Scenario: SimpleOperation
                        Given type qr_PersonType
                        ""${'"'}
                        <SPECMATIC_TYPE>
                          <Id>(number)</Id>
                          <Name>(string)</Name>
                        </SPECMATIC_TYPE>
                        ""${'"'}
                        And type SimpleOperation_SOAPPayload_Input
                        ""${'"'}
                        <SPECMATIC_TYPE>
                          <Person specmatic_type="qr_PersonType" specmatic_occurs="optional"/>
                        </SPECMATIC_TYPE>
                        ""${'"'}
                        When POST /SOAPService/SimpleSOAP
                        And enum SoapAction (string) values "http://specmatic.io/SOAPService/SimpleOperation",http://specmatic.io/SOAPService/SimpleOperation
                        And request-header SOAPAction (SoapAction)
                        And request-body
                        ""${'"'}
                        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
                          <soapenv:Header specmatic_occurs="optional"/>
                          <soapenv:Body>
                            <SimpleRequest specmatic_type="SimpleOperation_SOAPPayload_Input"/>
                          </soapenv:Body>
                        </soapenv:Envelope>
                        ""${'"'}
                        Then status 200
                        And response-body
                        ""${'"'}
                        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header specmatic_occurs="optional"/><soapenv:Body><SimpleResponse>(string)</SimpleResponse></soapenv:Body></soapenv:Envelope>
                        ""${'"'}
        """.trimIndent().trim()

            WSDL(
                toXMLNode(wsdlContent.replace("#occurrence#", occurrence)),
                "/path/to/wsdl.xml"
            ).let { wsdl ->
                val actualGherkin = wsdl.convertToGherkin().trim()

                assertThat(actualGherkin).isEqualToIgnoringWhitespace(expectedGherkin)
            }
        }
    }

    @Test
    fun `request body is empty when input node does not exist in portType`() {
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
        """

        val wsdl = WSDL(toXMLNode(wsdlContent), "/path/to/wsdl.xml")

        val expectedGherkin = """
            Feature: simpleService
            
                Scenario: SimpleOperation
                    When POST /SOAPService/SimpleSOAP
                    And enum SoapAction (string) values "http://specmatic.io/SOAPService/SimpleOperation",http://specmatic.io/SOAPService/SimpleOperation
                    And request-header SOAPAction (SoapAction)
                    Then status 200
                    And response-body
                    ""${'"'}
                    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header specmatic_occurs="optional"/><soapenv:Body><SimpleResponse>(string)</SimpleResponse></soapenv:Body></soapenv:Envelope>
                    ""${'"'}
        """.trimIndent().trim()

        val actualGherkin = wsdl.convertToGherkin()

        assertThat(actualGherkin).isEqualToIgnoringWhitespace(expectedGherkin)
    }

    @Test
    fun `request body contains empty soap payload when input message node does not contain children`() {
        val wsdlContent = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:qr="http://specmatic.io/SOAPService/"
                              targetNamespace="http://specmatic.io/SOAPService/">
                <wsdl:types>
                    <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" targetNamespace="http://specmatic.io/SOAPService/">
                        <xsd:element name="SimpleResponse" type="xsd:string"/>
                    </xsd:schema>
                </wsdl:types>

                <wsdl:message name="simpleInputMessage" />
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
        """

        val wsdl = WSDL(toXMLNode(wsdlContent), "/path/to/wsdl.xml")

        val expectedGherkin = """
            Feature: simpleService
            
                Scenario: SimpleOperation
                    When POST /SOAPService/SimpleSOAP
                    And enum SoapAction (string) values "http://specmatic.io/SOAPService/SimpleOperation",http://specmatic.io/SOAPService/SimpleOperation
                    And request-header SOAPAction (SoapAction)
                    And request-body
                    ""${'"'}
                    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header specmatic_occurs="optional"/><soapenv:Body/></soapenv:Envelope>
                    ""${'"'}
                    Then status 200
                    And response-body
                    ""${'"'}
                    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header specmatic_occurs="optional"/><soapenv:Body><SimpleResponse>(string)</SimpleResponse></soapenv:Body></soapenv:Envelope>
                    ""${'"'}
        """.trimIndent().trim()

        val actualGherkin = wsdl.convertToGherkin()
        assertThat(actualGherkin).isEqualToIgnoringWhitespace(expectedGherkin)
    }

    @Test
    fun `ref to simple element in request body`() {
        val wsdlContent = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:qr="http://specmatic.io/SOAPService/"
                              targetNamespace="http://specmatic.io/SOAPService/">
                <wsdl:types>
                    <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" targetNamespace="http://specmatic.io/SOAPService/">
                        <xsd:element name="SimpleRequest">
                            <xsd:complexType>
                                <xsd:sequence>
                                    <xsd:element name="Id" type="xsd:integer" />
                                    <xsd:element name="Name" ref="qr:Name" />
                                </xsd:sequence>
                            </xsd:complexType>
                        </xsd:element>
                        <xsd:element name="SimpleResponse" type="xsd:string"/>
                        <xsd:element name="Name" type="xsd:string" />
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
        """

        val wsdl = WSDL(toXMLNode(wsdlContent), "/path/to/wsdl.xml")

        val expectedGherkin = """
            Feature: simpleService
            
                Scenario: SimpleOperation
                    Given type SimpleOperation_SOAPPayload_Input
                    ""${'"'}
                    <SPECMATIC_TYPE>
                      <Id>(number)</Id>
                      <Name>(string)</Name>
                    </SPECMATIC_TYPE>
                    ""${'"'}
                    When POST /SOAPService/SimpleSOAP
                    And enum SoapAction (string) values "http://specmatic.io/SOAPService/SimpleOperation",http://specmatic.io/SOAPService/SimpleOperation
                    And request-header SOAPAction (SoapAction)
                    And request-body
                    ""${'"'}
                    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
                      <soapenv:Header specmatic_occurs="optional"/>
                      <soapenv:Body>
                        <SimpleRequest specmatic_type="SimpleOperation_SOAPPayload_Input"/>
                      </soapenv:Body>
                    </soapenv:Envelope>
                    ""${'"'}
                    Then status 200
                    And response-body
                    ""${'"'}
                    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header specmatic_occurs="optional"/><soapenv:Body><SimpleResponse>(string)</SimpleResponse></soapenv:Body></soapenv:Envelope>
                    ""${'"'}
        """.trimIndent().trim()

        val actualGherkin = wsdl.convertToGherkin()

        assertThat(actualGherkin).isEqualToIgnoringWhitespace(expectedGherkin)
    }

    @Test
    fun `ref to complex type in request body`() {
        val wsdlContent = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:qr="http://specmatic.io/SOAPService/"
                              targetNamespace="http://specmatic.io/SOAPService/">
                <wsdl:types>
                    <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" targetNamespace="http://specmatic.io/SOAPService/">
                        <xsd:element name="SimpleRequest">
                            <xsd:complexType>
                                <xsd:sequence>
                                    <xsd:element name="Id" type="xsd:integer" />
                                    <xsd:element name="Name" ref="qr:Name" />
                                </xsd:sequence>
                            </xsd:complexType>
                        </xsd:element>
                        <xsd:element name="SimpleResponse" type="xsd:string"/>
                        <xsd:element name="Name">
                            <xsd:complexType>
                                <xsd:sequence>
                                    <xsd:element name="first" type="xsd:string" />
                                    <xsd:element name="last" type="xsd:string" />
                                </xsd:sequence>
                            </xsd:complexType>
                        </xsd:element>
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
        """

        val wsdl = WSDL(toXMLNode(wsdlContent), "/path/to/wsdl.xml")

        val expectedGherkin = """
            Feature: simpleService
            
                Scenario: SimpleOperation
                    Given type qr_Name
                    ""${'"'}
                    <SPECMATIC_TYPE>
                      <first>(string)</first>
                      <last>(string)</last>
                    </SPECMATIC_TYPE>
                    ""${'"'}
                    And type SimpleOperation_SOAPPayload_Input
                    ""${'"'}
                    <SPECMATIC_TYPE>
                      <Id>(number)</Id>
                      <Name specmatic_type="qr_Name"/>
                    </SPECMATIC_TYPE>
                    ""${'"'}
                    When POST /SOAPService/SimpleSOAP
                    And enum SoapAction (string) values "http://specmatic.io/SOAPService/SimpleOperation",http://specmatic.io/SOAPService/SimpleOperation
                    And request-header SOAPAction (SoapAction)
                    And request-body
                    ""${'"'}
                    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
                      <soapenv:Header specmatic_occurs="optional"/>
                      <soapenv:Body>
                        <SimpleRequest specmatic_type="SimpleOperation_SOAPPayload_Input"/>
                      </soapenv:Body>
                    </soapenv:Envelope>
                    ""${'"'}
                    Then status 200
                    And response-body
                    ""${'"'}
                    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header specmatic_occurs="optional"/><soapenv:Body><SimpleResponse>(string)</SimpleResponse></soapenv:Body></soapenv:Envelope>
                    ""${'"'}
                """.trimIndent().trim()

        val actualGherkin = wsdl.convertToGherkin()

        assertThat(actualGherkin).isEqualToIgnoringWhitespace(expectedGherkin)
    }

    companion object {
        @JvmStatic
        fun simpleQualifiedElement(): Stream<Arguments> {
            return Stream.of(
                arguments("""elementFormDefault="qualified" """, """"""),
                arguments("""elementFormDefault="qualified" """, """form="qualified" """),
                arguments("""elementFormDefault="unqualified" """, """form="qualified" """),
                arguments("""""", """form="qualified" """),
            )
        }

        @JvmStatic
        fun simpleUnqualifiedElement(): Stream<Arguments> {
            return Stream.of(
                arguments("""elementFormDefault="unqualified" """, """"""),
                arguments("""elementFormDefault="unqualified" """, """form="unqualified" """),
                arguments("""elementFormDefault="qualified" """, """form="unqualified" """),
                arguments("""""", """form="unqualified" """),
            )
        }
    }

    @ParameterizedTest
    @MethodSource("simpleQualifiedElement")
    fun `qualified simple element in request body`(elementFormDefault: String, form: String) {
        val wsdlContent = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:qr="http://specmatic.io/SOAPService/"
                              targetNamespace="http://specmatic.io/SOAPService/">
                <wsdl:types>
                    <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" $elementFormDefault targetNamespace="http://specmatic.io/SOAPService/">
                        <xsd:element name="SimpleRequest" $form type="xsd:string"/>
                        <xsd:element name="SimpleResponse" $form type="xsd:string"/>
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
        """

        val wsdl = WSDL(toXMLNode(wsdlContent), "/path/to/wsdl.xml")

        val expectedGherkin = """
            Feature: simpleService
            
                Scenario: SimpleOperation
                    When POST /SOAPService/SimpleSOAP
                    And enum SoapAction (string) values "http://specmatic.io/SOAPService/SimpleOperation",http://specmatic.io/SOAPService/SimpleOperation
                    And request-header SOAPAction (SoapAction)
                    And request-body
                    ""${'"'}
                    <soapenv:Envelope xmlns:SOAPService="http://specmatic.io/SOAPService/" xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header specmatic_occurs="optional"/><soapenv:Body><SOAPService:SimpleRequest>(string)</SOAPService:SimpleRequest></soapenv:Body></soapenv:Envelope>
                    ""${'"'}
                    Then status 200
                    And response-body
                    ""${'"'}
                    <soapenv:Envelope xmlns:SOAPService="http://specmatic.io/SOAPService/" xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header specmatic_occurs="optional"/><soapenv:Body><SOAPService:SimpleResponse>(string)</SOAPService:SimpleResponse></soapenv:Body></soapenv:Envelope>
                    ""${'"'}
        """.trimIndent().trim()

        val actualGherkin = wsdl.convertToGherkin()
        assertThat(actualGherkin).isEqualToIgnoringWhitespace(expectedGherkin)
    }

    @ParameterizedTest
    @MethodSource("simpleUnqualifiedElement")
    fun `unqualified simple element in request body`(elementFormDefault: String, form: String) {
        val wsdlContent = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:qr="http://specmatic.io/SOAPService/"
                              targetNamespace="http://specmatic.io/SOAPService/">
                <wsdl:types>
                    <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" $elementFormDefault targetNamespace="http://specmatic.io/SOAPService/">
                        <xsd:element name="SimpleRequest" $form type="xsd:string"/>
                        <xsd:element name="SimpleResponse" $form type="xsd:string"/>
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
        """

        val wsdl = WSDL(toXMLNode(wsdlContent), "/path/to/wsdl.xml")

        val expectedGherkin = """
            Feature: simpleService
            
                Scenario: SimpleOperation
                    When POST /SOAPService/SimpleSOAP
                    And enum SoapAction (string) values "http://specmatic.io/SOAPService/SimpleOperation",http://specmatic.io/SOAPService/SimpleOperation
                    And request-header SOAPAction (SoapAction)
                    And request-body
                    ""${'"'}
                    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header specmatic_occurs="optional"/><soapenv:Body><SimpleRequest>(string)</SimpleRequest></soapenv:Body></soapenv:Envelope>
                    ""${'"'}
                    Then status 200
                    And response-body
                    ""${'"'}
                    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header specmatic_occurs="optional"/><soapenv:Body><SimpleResponse>(string)</SimpleResponse></soapenv:Body></soapenv:Envelope>
                    ""${'"'}
        """.trimIndent().trim()

        val actualGherkin = wsdl.convertToGherkin()

        assertThat(actualGherkin).isEqualToIgnoringWhitespace(expectedGherkin)
    }
}
