package io.specmatic.conversions

import io.specmatic.core.HttpRequest
import io.specmatic.core.Result
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

internal class WsdlSpecificationTest {
    @Test
    fun `support for nillable element`() {
        val wsdlPath = "src/test/resources/wsdl/stockquote.wsdl"

        val wsdlContract = wsdlContentToFeature(File(wsdlPath).readText(), wsdlPath)

        val httpRequest = HttpRequest(
            method = "POST",
            path = "/stockquote",
            headers = mapOf("SOAPAction" to "\"http://example.com/GetLastTradePrice\""),
            body = StringValue("""<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header/><soapenv:Body><TradePriceRequest><tickerSymbol nil="true"/></TradePriceRequest></soapenv:Body></soapenv:Envelope>""")
        )

        val scenario = wsdlContract.scenarios.first()
        val result = scenario.httpRequestPattern.matches(httpRequest, scenario.resolver)

        println(result.toReport().toText())
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `request and response content type should be parsed as text-xml for soap version_1_1`() {
        val wsdlPath = "src/test/resources/wsdl/stockquote.wsdl"
        val wsdlContract = wsdlContentToFeature(File(wsdlPath).readText(), wsdlPath)
        assertThat(wsdlContract.scenarios).allSatisfy { scenario ->
            val request = scenario.generateHttpRequest()
            assertThat(request.contentType()).isEqualTo("text/xml")

            val response = scenario.generateHttpResponse(emptyMap())
            assertThat(response.contentType()).isEqualTo("text/xml")
        }
    }

    @Test
    fun `request and response content type should be parsed as application-soap-xml for soap version_1_2`() {
        val wsdlPath = "src/test/resources/wsdl/cdata_test_soap12/data_api.wsdl"
        val wsdlContract = wsdlContentToFeature(File(wsdlPath).readText(), wsdlPath)
        assertThat(wsdlContract.scenarios).allSatisfy { scenario ->
            val request = scenario.generateHttpRequest()
            assertThat(request.contentType()).isEqualTo("application/soap+xml")

            val response = scenario.generateHttpResponse(emptyMap())
            assertThat(response.contentType()).isEqualTo("application/soap+xml")
        }
    }

    @Test
    fun `request and response content type should default to application-soap-xml for unknown soap version`() {
        val wsdlPath = "src/test/resources/wsdl/stockquote.wsdl"
        val wsdlContentWithUnknownSoapNamespace = File(wsdlPath).readText()
            .replace("http://schemas.xmlsoap.org/wsdl/soap/", "http://example.com/unknown-soap/")

        val wsdlContract = wsdlContentToFeature(wsdlContentWithUnknownSoapNamespace, wsdlPath)
        assertThat(wsdlContract.scenarios).allSatisfy { scenario ->
            val request = scenario.generateHttpRequest()
            assertThat(request.contentType()).isEqualTo("application/soap+xml")

            val response = scenario.generateHttpResponse(emptyMap())
            assertThat(response.contentType()).isEqualTo("application/soap+xml")
        }
    }

    @Test
    fun `request matching uses compatible xsi type from wsdl complex type extension`() {
        val wsdlContract = wsdlContentToFeature(animalWsdl(), "animal.wsdl")
        val scenario = wsdlContract.scenarios.single()

        assertThat(scenario.resolver.newPatterns.keys).contains("(tns_Dog)", "(tns_Vehicle)")

        val dogRequest = animalRequest(
            """
            <tns:Animal xmlns:tns="http://example.com/animals"
                        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                        xsi:type="tns:Dog">
              <tns:name>Fido</tns:name>
              <tns:breed>Beagle</tns:breed>
            </tns:Animal>
            """.trimIndent()
        )
        val dogRequestWithAlternateSchemaInstancePrefix = animalRequest(
            """
            <tns:Animal xmlns:tns="http://example.com/animals"
                        xmlns:typeNs="http://www.w3.org/2001/XMLSchema-instance"
                        typeNs:type="tns:Dog">
              <tns:name>Fido</tns:name>
              <tns:breed>Beagle</tns:breed>
            </tns:Animal>
            """.trimIndent()
        )
        val vehicleRequest = animalRequest(
            """
            <tns:Animal xmlns:tns="http://example.com/animals"
                        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                        xsi:type="tns:Vehicle">
              <tns:registration>KA01AB1234</tns:registration>
            </tns:Animal>
            """.trimIndent()
        )

        assertThat(scenario.httpRequestPattern.matches(dogRequest, scenario.resolver)).isInstanceOf(Result.Success::class.java)
        assertThat(scenario.httpRequestPattern.matches(dogRequestWithAlternateSchemaInstancePrefix, scenario.resolver)).isInstanceOf(Result.Success::class.java)
        assertThat(scenario.httpRequestPattern.matches(vehicleRequest, scenario.resolver)).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `request matching uses compatible xsi type from wsdl simple type restriction`() {
        val wsdlContract = wsdlContentToFeature(codeWsdl(), "code.wsdl")
        val scenario = wsdlContract.scenarios.single()

        val validRequest = codeRequest(
            """
            <tns:Code xmlns:tns="http://example.com/codes"
                      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                      xsi:type="tns:ConstrainedCode">ABC123</tns:Code>
            """.trimIndent()
        )

        assertThat(scenario.httpRequestPattern.matches(validRequest, scenario.resolver)).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `request matching uses compatible xsi type from wsdl simple content extension`() {
        val wsdlContract = wsdlContentToFeature(taggedCodeWsdl(), "tagged-code.wsdl")
        val scenario = wsdlContract.scenarios.single()

        val validRequest = taggedCodeRequest(
            """
            <tns:Code xmlns:tns="http://example.com/tagged-codes"
                      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                      xsi:type="tns:TaggedCode"
                      tag="primary">ABC123</tns:Code>
            """.trimIndent()
        )

        assertThat(scenario.httpRequestPattern.matches(validRequest, scenario.resolver)).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `request matching allows valid repeated sibling nodes from wsdl sequence`() {
        val wsdlContract = wsdlContentToFeature(peopleWsdl(), "people.wsdl")
        val scenario = wsdlContract.scenarios.single()

        val payloads = listOf(
            "<tns:employee><tns:name>Ada</tns:name></tns:employee><tns:employee><tns:name>Grace</tns:name></tns:employee>",
            "<tns:manager><tns:name>Linus</tns:name></tns:manager><tns:manager><tns:name>Margaret</tns:name></tns:manager>",
            "<tns:employee><tns:name>Ada</tns:name></tns:employee><tns:manager><tns:name>Linus</tns:name></tns:manager>",
            "<tns:employee><tns:name>Ada</tns:name></tns:employee><tns:employee><tns:name>Grace</tns:name></tns:employee><tns:manager><tns:name>Linus</tns:name></tns:manager>",
            "<tns:employee><tns:name>Ada</tns:name></tns:employee><tns:manager><tns:name>Linus</tns:name></tns:manager><tns:manager><tns:name>Margaret</tns:name></tns:manager>",
            "<tns:employee><tns:name>Ada</tns:name></tns:employee><tns:employee><tns:name>Grace</tns:name></tns:employee><tns:manager><tns:name>Linus</tns:name></tns:manager><tns:manager><tns:name>Margaret</tns:name></tns:manager>",
        )

        assertThat(payloads).allSatisfy { payload ->
            assertThat(scenario.httpRequestPattern.matches(peopleRequest(payload), scenario.resolver))
                .isInstanceOf(Result.Success::class.java)
        }
    }

    @Test
    fun `request matching uses compatible xsi type inside wsdl choice group`() {
        val wsdlContract = wsdlContentToFeature(petChoiceWsdl(), "pet-choice.wsdl")
        val scenario = wsdlContract.scenarios.single()

        val result = scenario.httpRequestPattern.matches(
            soapRequest(
                path = "/pet-choice",
                soapAction = "http://example.com/pet-choice/RegisterChoice",
                bodyNode = """
                    <tns:RegisterChoice xmlns:tns="http://example.com/pet-choice"
                                        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                      <tns:pet xsi:type="tns:Dog">
                        <tns:name>Rover</tns:name>
                        <tns:breed>Labrador</tns:breed>
                      </tns:pet>
                    </tns:RegisterChoice>
                """.trimIndent()
            ),
            scenario.resolver
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `request matching rejects incompatible known xsi type inside wsdl choice group`() {
        val wsdlContract = wsdlContentToFeature(petChoiceWsdl(), "pet-choice.wsdl")
        val scenario = wsdlContract.scenarios.single()

        val result = scenario.httpRequestPattern.matches(
            soapRequest(
                path = "/pet-choice",
                soapAction = "http://example.com/pet-choice/RegisterChoice",
                bodyNode = """
                    <tns:RegisterChoice xmlns:tns="http://example.com/pet-choice"
                                        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                      <tns:pet xsi:type="tns:Crocodile">
                        <tns:toothCount>64</tns:toothCount>
                      </tns:pet>
                    </tns:RegisterChoice>
                """.trimIndent()
            ),
            scenario.resolver
        )

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).contains("Invalid xsi:type")
        assertThat(result.reportString()).contains("Crocodile")
        assertThat(result.reportString()).contains("Pet")
    }

    @Test
    fun `request matching uses compatible xsi type inside nested wsdl sequence`() {
        val wsdlContract = wsdlContentToFeature(petNestedSequenceWsdl(), "pet-nested-sequence.wsdl")
        val scenario = wsdlContract.scenarios.single()

        val result = scenario.httpRequestPattern.matches(
            soapRequest(
                path = "/pet-nested-sequence",
                soapAction = "http://example.com/pet-nested-sequence/RegisterNestedSequence",
                bodyNode = """
                    <tns:RegisterNestedSequence xmlns:tns="http://example.com/pet-nested-sequence"
                                                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                      <tns:payload>
                        <tns:pet xsi:type="tns:Dog">
                          <tns:name>Rover</tns:name>
                          <tns:breed>Labrador</tns:breed>
                        </tns:pet>
                      </tns:payload>
                    </tns:RegisterNestedSequence>
                """.trimIndent()
            ),
            scenario.resolver
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `request matching rejects incompatible known xsi type inside nested wsdl sequence`() {
        val wsdlContract = wsdlContentToFeature(petNestedSequenceWsdl(), "pet-nested-sequence.wsdl")
        val scenario = wsdlContract.scenarios.single()

        val result = scenario.httpRequestPattern.matches(
            soapRequest(
                path = "/pet-nested-sequence",
                soapAction = "http://example.com/pet-nested-sequence/RegisterNestedSequence",
                bodyNode = """
                    <tns:RegisterNestedSequence xmlns:tns="http://example.com/pet-nested-sequence"
                                                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                      <tns:payload>
                        <tns:pet xsi:type="tns:Crocodile">
                          <tns:toothCount>64</tns:toothCount>
                        </tns:pet>
                      </tns:payload>
                    </tns:RegisterNestedSequence>
                """.trimIndent()
            ),
            scenario.resolver
        )

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).contains("Invalid xsi:type")
        assertThat(result.reportString()).contains("Crocodile")
        assertThat(result.reportString()).contains("Pet")
    }

    @Test
    fun `request matching keeps wsdl wildcard behavior independent of xsi type inheritance`() {
        val wsdlContract = wsdlContentToFeature(petWildcardWsdl(), "pet-wildcard.wsdl")
        val scenario = wsdlContract.scenarios.single()

        val result = scenario.httpRequestPattern.matches(
            soapRequest(
                path = "/pet-wildcard",
                soapAction = "http://example.com/pet-wildcard/RegisterWildcard",
                bodyNode = """
                    <tns:RegisterWildcard xmlns:tns="http://example.com/pet-wildcard"
                                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                          xmlns:other="http://example.com/other">
                      <other:Anything xsi:type="tns:Crocodile">
                        <other:unexpected>value</other:unexpected>
                      </other:Anything>
                    </tns:RegisterWildcard>
                """.trimIndent()
            ),
            scenario.resolver
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    private fun animalRequest(bodyNode: String): HttpRequest {
        return HttpRequest(
            method = "POST",
            path = "/animals",
            headers = mapOf("SOAPAction" to "\"http://example.com/animals/SubmitAnimal\""),
            body = StringValue(
                """
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                                  xmlns:xsd="http://www.w3.org/2001/XMLSchema">
                  <soapenv:Header/>
                  <soapenv:Body>
                    $bodyNode
                  </soapenv:Body>
                </soapenv:Envelope>
                """.trimIndent()
            )
        )
    }

    private fun codeRequest(bodyNode: String): HttpRequest {
        return soapRequest(
            path = "/codes",
            soapAction = "http://example.com/codes/SubmitCode",
            bodyNode = bodyNode
        )
    }

    private fun taggedCodeRequest(bodyNode: String): HttpRequest {
        return soapRequest(
            path = "/tagged-codes",
            soapAction = "http://example.com/tagged-codes/SubmitCode",
            bodyNode = bodyNode
        )
    }

    private fun peopleRequest(bodyNodes: String): HttpRequest {
        return soapRequest(
            path = "/people",
            soapAction = "http://example.com/people/SubmitPeople",
            bodyNode = """
                <tns:people xmlns:tns="http://example.com/people">
                  $bodyNodes
                </tns:people>
            """.trimIndent()
        )
    }

    private fun soapRequest(path: String, soapAction: String, bodyNode: String): HttpRequest {
        return HttpRequest(
            method = "POST",
            path = path,
            headers = mapOf("SOAPAction" to "\"$soapAction\""),
            body = StringValue(
                """
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                                  xmlns:xsd="http://www.w3.org/2001/XMLSchema">
                  <soapenv:Header/>
                  <soapenv:Body>
                    $bodyNode
                  </soapenv:Body>
                </soapenv:Envelope>
                """.trimIndent()
            )
        )
    }

    private fun animalWsdl(): String {
        return """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:tns="http://example.com/animals"
                              targetNamespace="http://example.com/animals">
              <wsdl:types>
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                           xmlns:tns="http://example.com/animals"
                           targetNamespace="http://example.com/animals"
                           elementFormDefault="qualified">
                  <xs:element name="Animal" type="tns:Animal"/>
                  <xs:element name="AnimalResponse" type="xs:string"/>

                  <xs:complexType name="Animal">
                    <xs:sequence>
                      <xs:element name="name" type="xs:string"/>
                    </xs:sequence>
                  </xs:complexType>

                  <xs:complexType name="Dog">
                    <xs:complexContent>
                      <xs:extension base="tns:Animal">
                        <xs:sequence>
                          <xs:element name="breed" type="xs:string"/>
                        </xs:sequence>
                      </xs:extension>
                    </xs:complexContent>
                  </xs:complexType>

                  <xs:complexType name="Vehicle">
                    <xs:sequence>
                      <xs:element name="registration" type="xs:string"/>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
              </wsdl:types>

              <wsdl:message name="submitAnimalInput">
                <wsdl:part name="animal" element="tns:Animal"/>
              </wsdl:message>
              <wsdl:message name="submitAnimalOutput">
                <wsdl:part name="animalResponse" element="tns:AnimalResponse"/>
              </wsdl:message>

              <wsdl:portType name="AnimalPortType">
                <wsdl:operation name="SubmitAnimal">
                  <wsdl:input message="tns:submitAnimalInput"/>
                  <wsdl:output message="tns:submitAnimalOutput"/>
                </wsdl:operation>
              </wsdl:portType>

              <wsdl:binding name="AnimalBinding" type="tns:AnimalPortType">
                <soap:binding transport="http://schemas.xmlsoap.org/soap/http"/>
                <wsdl:operation name="SubmitAnimal">
                  <soap:operation soapAction="http://example.com/animals/SubmitAnimal"/>
                  <wsdl:input><soap:body use="literal"/></wsdl:input>
                  <wsdl:output><soap:body use="literal"/></wsdl:output>
                </wsdl:operation>
              </wsdl:binding>

              <wsdl:service name="AnimalService">
                <wsdl:port name="AnimalPort" binding="tns:AnimalBinding">
                  <soap:address location="http://localhost/animals"/>
                </wsdl:port>
              </wsdl:service>
            </wsdl:definitions>
        """.trimIndent()
    }

    private fun petChoiceWsdl(): String {
        return petWsdl(
            namespace = "http://example.com/pet-choice",
            path = "/pet-choice",
            operation = "RegisterChoice",
            requestType = """
                <xs:complexType name="RegisterChoiceRequest">
                  <xs:choice>
                    <xs:element name="pet" type="tns:Pet"/>
                    <xs:element name="comment" type="xs:string"/>
                  </xs:choice>
                </xs:complexType>
            """.trimIndent()
        )
    }

    private fun petNestedSequenceWsdl(): String {
        return petWsdl(
            namespace = "http://example.com/pet-nested-sequence",
            path = "/pet-nested-sequence",
            operation = "RegisterNestedSequence",
            requestType = """
                <xs:complexType name="RegisterNestedSequenceRequest">
                  <xs:sequence>
                    <xs:element name="payload" type="tns:PetPayload"/>
                  </xs:sequence>
                </xs:complexType>

                <xs:complexType name="PetPayload">
                  <xs:sequence>
                    <xs:element name="pet" type="tns:Pet"/>
                  </xs:sequence>
                </xs:complexType>
            """.trimIndent()
        )
    }

    private fun petWildcardWsdl(): String {
        return petWsdl(
            namespace = "http://example.com/pet-wildcard",
            path = "/pet-wildcard",
            operation = "RegisterWildcard",
            requestType = """
                <xs:complexType name="RegisterWildcardRequest">
                  <xs:sequence>
                    <xs:any namespace="##any" processContents="skip"/>
                  </xs:sequence>
                </xs:complexType>
            """.trimIndent()
        )
    }

    private fun petWsdl(namespace: String, path: String, operation: String, requestType: String): String {
        return """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:tns="$namespace"
                              targetNamespace="$namespace">
              <wsdl:types>
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                           xmlns:tns="$namespace"
                           targetNamespace="$namespace"
                           elementFormDefault="qualified">
                  <xs:element name="$operation" type="tns:${operation}Request"/>
                  <xs:element name="${operation}Response" type="xs:string"/>

                  <xs:complexType name="Pet">
                    <xs:sequence>
                      <xs:element name="name" type="xs:string"/>
                    </xs:sequence>
                  </xs:complexType>

                  <xs:complexType name="Dog">
                    <xs:complexContent>
                      <xs:extension base="tns:Pet">
                        <xs:sequence>
                          <xs:element name="breed" type="xs:string"/>
                        </xs:sequence>
                      </xs:extension>
                    </xs:complexContent>
                  </xs:complexType>

                  <xs:complexType name="Crocodile">
                    <xs:sequence>
                      <xs:element name="toothCount" type="xs:integer"/>
                    </xs:sequence>
                  </xs:complexType>

                  $requestType
                </xs:schema>
              </wsdl:types>

              <wsdl:message name="${operation}Input">
                <wsdl:part name="request" element="tns:$operation"/>
              </wsdl:message>
              <wsdl:message name="${operation}Output">
                <wsdl:part name="response" element="tns:${operation}Response"/>
              </wsdl:message>

              <wsdl:portType name="${operation}PortType">
                <wsdl:operation name="$operation">
                  <wsdl:input message="tns:${operation}Input"/>
                  <wsdl:output message="tns:${operation}Output"/>
                </wsdl:operation>
              </wsdl:portType>

              <wsdl:binding name="${operation}Binding" type="tns:${operation}PortType">
                <soap:binding transport="http://schemas.xmlsoap.org/soap/http"/>
                <wsdl:operation name="$operation">
                  <soap:operation soapAction="$namespace/$operation"/>
                  <wsdl:input><soap:body use="literal"/></wsdl:input>
                  <wsdl:output><soap:body use="literal"/></wsdl:output>
                </wsdl:operation>
              </wsdl:binding>

              <wsdl:service name="${operation}Service">
                <wsdl:port name="${operation}Port" binding="tns:${operation}Binding">
                  <soap:address location="http://localhost$path"/>
                </wsdl:port>
              </wsdl:service>
            </wsdl:definitions>
        """.trimIndent()
    }

    private fun codeWsdl(): String {
        return """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:tns="http://example.com/codes"
                              targetNamespace="http://example.com/codes">
              <wsdl:types>
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                           xmlns:tns="http://example.com/codes"
                           targetNamespace="http://example.com/codes"
                           elementFormDefault="qualified">
                  <xs:element name="Code" type="tns:BaseCode"/>
                  <xs:element name="CodeResponse" type="xs:string"/>

                  <xs:simpleType name="BaseCode">
                    <xs:restriction base="xs:string"/>
                  </xs:simpleType>

                  <xs:simpleType name="ConstrainedCode">
                    <xs:restriction base="tns:BaseCode">
                      <xs:minLength value="6"/>
                      <xs:maxLength value="12"/>
                      <xs:pattern value="[A-Z0-9]+"/>
                    </xs:restriction>
                  </xs:simpleType>
                </xs:schema>
              </wsdl:types>

              <wsdl:message name="submitCodeInput">
                <wsdl:part name="code" element="tns:Code"/>
              </wsdl:message>
              <wsdl:message name="submitCodeOutput">
                <wsdl:part name="codeResponse" element="tns:CodeResponse"/>
              </wsdl:message>

              <wsdl:portType name="CodePortType">
                <wsdl:operation name="SubmitCode">
                  <wsdl:input message="tns:submitCodeInput"/>
                  <wsdl:output message="tns:submitCodeOutput"/>
                </wsdl:operation>
              </wsdl:portType>

              <wsdl:binding name="CodeBinding" type="tns:CodePortType">
                <soap:binding transport="http://schemas.xmlsoap.org/soap/http"/>
                <wsdl:operation name="SubmitCode">
                  <soap:operation soapAction="http://example.com/codes/SubmitCode"/>
                  <wsdl:input><soap:body use="literal"/></wsdl:input>
                  <wsdl:output><soap:body use="literal"/></wsdl:output>
                </wsdl:operation>
              </wsdl:binding>

              <wsdl:service name="CodeService">
                <wsdl:port name="CodePort" binding="tns:CodeBinding">
                  <soap:address location="http://localhost/codes"/>
                </wsdl:port>
              </wsdl:service>
            </wsdl:definitions>
        """.trimIndent()
    }

    private fun taggedCodeWsdl(): String {
        return """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:tns="http://example.com/tagged-codes"
                              targetNamespace="http://example.com/tagged-codes">
              <wsdl:types>
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                           xmlns:tns="http://example.com/tagged-codes"
                           targetNamespace="http://example.com/tagged-codes"
                           elementFormDefault="qualified">
                  <xs:element name="Code" type="tns:BaseCode"/>
                  <xs:element name="CodeResponse" type="xs:string"/>

                  <xs:complexType name="BaseCode">
                    <xs:simpleContent>
                      <xs:extension base="xs:string"/>
                    </xs:simpleContent>
                  </xs:complexType>

                  <xs:complexType name="TaggedCode">
                    <xs:simpleContent>
                      <xs:extension base="tns:BaseCode">
                        <xs:attribute name="tag" type="xs:string" use="required"/>
                      </xs:extension>
                    </xs:simpleContent>
                  </xs:complexType>
                </xs:schema>
              </wsdl:types>

              <wsdl:message name="submitCodeInput">
                <wsdl:part name="code" element="tns:Code"/>
              </wsdl:message>
              <wsdl:message name="submitCodeOutput">
                <wsdl:part name="codeResponse" element="tns:CodeResponse"/>
              </wsdl:message>

              <wsdl:portType name="CodePortType">
                <wsdl:operation name="SubmitCode">
                  <wsdl:input message="tns:submitCodeInput"/>
                  <wsdl:output message="tns:submitCodeOutput"/>
                </wsdl:operation>
              </wsdl:portType>

              <wsdl:binding name="CodeBinding" type="tns:CodePortType">
                <soap:binding transport="http://schemas.xmlsoap.org/soap/http"/>
                <wsdl:operation name="SubmitCode">
                  <soap:operation soapAction="http://example.com/tagged-codes/SubmitCode"/>
                  <wsdl:input><soap:body use="literal"/></wsdl:input>
                  <wsdl:output><soap:body use="literal"/></wsdl:output>
                </wsdl:operation>
              </wsdl:binding>

              <wsdl:service name="CodeService">
                <wsdl:port name="CodePort" binding="tns:CodeBinding">
                  <soap:address location="http://localhost/tagged-codes"/>
                </wsdl:port>
              </wsdl:service>
            </wsdl:definitions>
        """.trimIndent()
    }

    private fun peopleWsdl(): String {
        return """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:tns="http://example.com/people"
                              targetNamespace="http://example.com/people">
              <wsdl:types>
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                           xmlns:tns="http://example.com/people"
                           targetNamespace="http://example.com/people"
                           elementFormDefault="qualified">
                  <xs:element name="people" type="tns:People"/>
                  <xs:element name="peopleResponse" type="xs:string"/>

                  <xs:complexType name="Person">
                    <xs:sequence>
                      <xs:element name="name" type="xs:string"/>
                    </xs:sequence>
                  </xs:complexType>

                  <xs:complexType name="People">
                    <xs:sequence>
                      <xs:element name="employee" type="tns:Person" minOccurs="0" maxOccurs="unbounded"/>
                      <xs:element name="manager" type="tns:Person" minOccurs="0" maxOccurs="unbounded"/>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
              </wsdl:types>

              <wsdl:message name="submitPeopleInput">
                <wsdl:part name="people" element="tns:people"/>
              </wsdl:message>
              <wsdl:message name="submitPeopleOutput">
                <wsdl:part name="peopleResponse" element="tns:peopleResponse"/>
              </wsdl:message>

              <wsdl:portType name="PeoplePortType">
                <wsdl:operation name="SubmitPeople">
                  <wsdl:input message="tns:submitPeopleInput"/>
                  <wsdl:output message="tns:submitPeopleOutput"/>
                </wsdl:operation>
              </wsdl:portType>

              <wsdl:binding name="PeopleBinding" type="tns:PeoplePortType">
                <soap:binding transport="http://schemas.xmlsoap.org/soap/http"/>
                <wsdl:operation name="SubmitPeople">
                  <soap:operation soapAction="http://example.com/people/SubmitPeople"/>
                  <wsdl:input><soap:body use="literal"/></wsdl:input>
                  <wsdl:output><soap:body use="literal"/></wsdl:output>
                </wsdl:operation>
              </wsdl:binding>

              <wsdl:service name="PeopleService">
                <wsdl:port name="PeoplePort" binding="tns:PeopleBinding">
                  <soap:address location="http://localhost/people"/>
                </wsdl:port>
              </wsdl:service>
            </wsdl:definitions>
        """.trimIndent()
    }
}
