package io.specmatic.core.wsdl

import io.specmatic.Utils.readTextResource
import io.specmatic.conversions.wsdlContentToFeature
import io.specmatic.core.*
import io.specmatic.core.utilities.contractStubPaths
import io.specmatic.core.utilities.contractTestPathsFrom
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.FeatureStubsResult
import io.specmatic.stub.HttpStub
import io.specmatic.stub.loadContractStubsFromFilesAsResults
import io.specmatic.test.TestExecutor
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WSDLTest {
    @Test
    fun `conversion with bare types`() {
        val (wsdlContent, expectedGherkin) = readContracts("stockquote")

        val wsdl = WSDL(toXMLNode(wsdlContent), "")
        val gherkinFromWSDL: String = wsdl.convertToGherkin().trim()
        val featureFromWSDL = parseGherkinStringToFeature(gherkinFromWSDL)

        val featureFromExpectedGherkin = parseGherkinStringToFeature(expectedGherkin)

        assertThat(featureFromWSDL).isEqualTo(featureFromExpectedGherkin)
    }

    @Test
    fun `conversion with simple type bodies`() {
        val (wsdlContent, expectedGherkin) = readContracts("hello")

        val wsdl = WSDL(toXMLNode(wsdlContent), "")
        val generatedGherkin: String = wsdl.convertToGherkin().trim()

        assertThat(parseGherkinStringToFeature(generatedGherkin)).isEqualTo(parseGherkinStringToFeature(expectedGherkin))
    }

    @Test
    fun `when a WSDL containing references is run as stub and then as contract tests against itself the tests should pass`() {
        val wsdlFile = File("src/test/resources/wsdl/order_api.wsdl")
        val feature = wsdlContentToFeature(checkExists(wsdlFile).readText(), wsdlFile.canonicalPath)

        val result = HttpStub(feature).use { stub ->
            feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    return stub.client.execute(request)
                }
            })
        }

        println(result.report())

        assertThat(result.report()).doesNotContain("Expected xml, got string")
        assertThat(result.report()).doesNotContain("Didn't get enough values")
        assertThat(result.success()).isTrue()
        assertThat(result.successCount).isGreaterThan(0)
    }

    @Test
    fun `when a WSDL with an example is run as a test against a stub of itself the tests should pass`() {
        val wsdlFile = File("src/test/resources/wsdl/with_examples/order_api.wsdl")
        val examplesFolder = wsdlFile.resolveSibling("order_api_examples")

        val feature = wsdlContentToFeature(checkExists(wsdlFile).readText(), wsdlFile.canonicalPath).loadExternalisedExamples()
        val scenarioStubs = examplesFolder.listFiles()?.map(ScenarioStub::readFromFile).orEmpty()
        assertDoesNotThrow { feature.validateExamplesOrException() }

        val result = HttpStub(feature, scenarioStubs).use { stub ->
            feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    val response = stub.client.execute(request)
                    assertThat(response.headers["X-Specmatic-Type"]).isNotEqualTo("random")
                    return response
                }
            })
        }

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(result.successCount).isEqualTo(1)
    }

    @Test
    fun `wsdl loop should load v3 configured non underscore examples for sut and mock and traffic should contain example values`(@TempDir tempDir: File) {
        val wsdlSource = File("src/test/resources/wsdl/with_examples/order_api.wsdl")
        val wsdlFile = tempDir.resolve("order_api.wsdl").apply { writeText(wsdlSource.readText()) }

        val exampleSource = File("src/test/resources/wsdl/with_examples/order_api_examples/create_product.json")
        val sutExamplesDir = tempDir.resolve("sut-example-data").apply { mkdirs() }
        val mockExamplesDir = tempDir.resolve("mock-example-data").apply { mkdirs() }
        exampleSource.copyTo(sutExamplesDir.resolve("create_product.json"))
        exampleSource.copyTo(mockExamplesDir.resolve("create_product.json"))

        val configFile = tempDir.resolve("specmatic.yaml")
        configFile.writeText(
            """
            version: 3
            systemUnderTest:
              service:
                definitions:
                  - definition:
                      source:
                        filesystem:
                          directory: ${tempDir.canonicalPath}
                      specs:
                        - ${wsdlFile.name}
                data:
                  examples:
                    - directories:
                        - ${sutExamplesDir.canonicalPath}
            dependencies:
              services:
                - service:
                    definitions:
                      - definition:
                          source:
                            filesystem:
                              directory: ${tempDir.canonicalPath}
                          specs:
                            - ${wsdlFile.name}
                    data:
                      examples:
                        - directories:
                            - ${mockExamplesDir.canonicalPath}
            """.trimIndent()
        )

        val config = loadSpecmaticConfig(configFile.canonicalPath)
        val stubContractPathData = contractStubPaths(configFile.canonicalPath)
        val testContractPathData = contractTestPathsFrom(configFile.canonicalPath, ".")

        assertThat(stubContractPathData.single().exampleDirPaths).containsExactly(mockExamplesDir.canonicalPath)
        assertThat(testContractPathData.single().exampleDirPaths).containsExactly(sutExamplesDir.canonicalPath)

        val stubLoadResults = loadContractStubsFromFilesAsResults(
            contractPathDataList = stubContractPathData,
            dataDirPaths = emptyList(),
            specmaticConfig = config,
            withImplicitStubs = false
        )
        val stubConfig = stubLoadResults.filterIsInstance<FeatureStubsResult.Success>().single()

        val testPathData = testContractPathData.single()
        val testFeature = parseContractFileToFeature(
            contractPath = testPathData.path,
            specmaticConfig = config,
            exampleDirPaths = testPathData.exampleDirPaths.orEmpty()
        ).loadExternalisedExamples()

        val requestBodies = mutableListOf<String>()
        val responseBodies = mutableListOf<String>()
        val result = HttpStub(stubConfig.feature, stubConfig.scenarioStubs).use { stub ->
            testFeature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    val response = stub.client.execute(request)
                    requestBodies.add(request.body.toStringLiteral())
                    responseBodies.add(response.body.toStringLiteral())
                    return response
                }
            })
        }

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(requestBodies.joinToString("\n"))
            .contains("<name>Phone</name>")
            .contains("<type>Gadget</type>")
        assertThat(responseBodies.joinToString("\n"))
            .contains("<id>123</id>")
    }

    @Test
    fun `simple complex type wsdl loop passes without examples`() {
        val feature = wsdlContentToFeature(simpleComplexTypeWsdl(), "simple-complex.wsdl")

        val result = HttpStub(feature).use { stub ->
            feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse = stub.client.execute(request)
            })
        }

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(result.successCount).isGreaterThan(0)
    }

    @Test
    fun `simple complex type wsdl loop passes with examples`(@TempDir tempDir: File) {
        val wsdlFile = tempDir.resolve("simple-complex.wsdl").apply { writeText(simpleComplexTypeWsdl()) }
        val examplesDir = tempDir.resolve("examples").apply { mkdirs() }
        examplesDir.resolve("create_product.json").writeText(simpleComplexTypeExample())

        val result = executeWsdlLoopWithExamples(wsdlFile, examplesDir)

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(result.successCount).isEqualTo(1)
    }

    @Test
    fun `wsdl examples run once against mock without generating optional request data`(@TempDir tempDir: File) {
        val wsdlFile = tempDir.resolve("optional-request.wsdl").apply { writeText(optionalRequestElementWsdl()) }
        val examplesDir = tempDir.resolve("optional-request_examples").apply { mkdirs() }
        examplesDir.resolve("submit_ticket.json").writeText(optionalRequestElementExample())

        val feature = parseContractFileToFeature(wsdlFile, exampleDirPaths = listOf(examplesDir.canonicalPath))
            .loadExternalisedExamples()
        val scenarioStubs = examplesDir.listFiles()?.sortedBy(File::getName)?.map(ScenarioStub::readFromFile).orEmpty()

        assertDoesNotThrow { feature.validateExamplesOrException() }

        val requestBodies = mutableListOf<String>()
        val result = HttpStub(feature, scenarioStubs).use { stub ->
            feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    requestBodies.add(request.body.toStringLiteral())
                    return stub.client.execute(request)
                }
            })
        }

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(result.successCount).isEqualTo(1)
        assertThat(requestBodies).hasSize(1)
        assertThat(requestBodies.single())
            .contains("<ticketId>TCK-123</ticketId>")
            .doesNotContain("comment")
    }

    @Test
    fun `abstract complex type wsdl loop passes without examples`() {
        val feature = wsdlContentToFeature(abstractDerivedTypeWsdl(), "abstract-derived.wsdl")

        val result = HttpStub(feature).use { stub ->
            feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse = stub.client.execute(request)
            })
        }

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(result.successCount).isGreaterThan(0)
    }

    @Test
    fun `abstract complex type wsdl loop passes with examples`(@TempDir tempDir: File) {
        val wsdlFile = tempDir.resolve("abstract-derived.wsdl").apply { writeText(abstractDerivedTypeWsdl()) }
        val examplesDir = tempDir.resolve("examples").apply { mkdirs() }
        examplesDir.resolve("retrieve_order.json").writeText(abstractDerivedTypeExample())

        val result = executeWsdlLoopWithExamples(wsdlFile, examplesDir)

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(result.successCount).isEqualTo(1)
    }

    @Test
    fun `derived type element name is rejected without substitution group`(@TempDir tempDir: File) {
        val wsdlFile = tempDir.resolve("abstract-derived.wsdl").apply { writeText(abstractDerivedTypeWsdl()) }
        val examplesDir = tempDir.resolve("examples").apply { mkdirs() }
        examplesDir.resolve("retrieve_order_wrong_element.json").writeText(abstractDerivedTypeWrongElementNameExample())

        val feature = parseContractFileToFeature(wsdlFile, exampleDirPaths = listOf(examplesDir.canonicalPath))
            .loadExternalisedExamples()

        assertThatThrownBy { feature.validateExamplesOrException() }
            .hasMessageContaining("OrderDetails")
    }

    @Test
    fun `substitution group wsdl loop passes without examples`() {
        val feature = wsdlContentToFeature(substitutionGroupWsdl(), "substitution-group.wsdl")

        val result = HttpStub(feature).use { stub ->
            feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse = stub.client.execute(request)
            })
        }

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(result.successCount).isGreaterThan(0)
    }

    @Test
    fun `substitution group wsdl loop passes with examples`(@TempDir tempDir: File) {
        val wsdlFile = tempDir.resolve("substitution-group.wsdl").apply { writeText(substitutionGroupWsdl()) }
        val examplesDir = tempDir.resolve("examples").apply { mkdirs() }
        examplesDir.resolve("register_animal.json").writeText(substitutionGroupExample())

        val result = executeWsdlLoopWithExamples(wsdlFile, examplesDir)

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        assertThat(result.successCount).isEqualTo(1)
    }

    private fun readContracts(filename: String): Pair<String, String> {
        val wsdlContent = readTextResource("wsdl/$filename.wsdl")
        val expectedGherkin = readTextResource("wsdl/$filename.$CONTRACT_EXTENSION").trimIndent().trim()
        return Pair(wsdlContent, expectedGherkin)
    }
}

private fun executeWsdlLoopWithExamples(wsdlFile: File, examplesDir: File): Results {
    val feature = parseContractFileToFeature(wsdlFile, exampleDirPaths = listOf(examplesDir.canonicalPath))
        .loadExternalisedExamples()
    val scenarioStubs = examplesDir.listFiles()?.sortedBy(File::getName)?.map(ScenarioStub::readFromFile).orEmpty()

    assertDoesNotThrow { feature.validateExamplesOrException() }

    return HttpStub(feature, scenarioStubs).use { stub ->
        feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse = stub.client.execute(request)
        })
    }
}

private fun simpleComplexTypeWsdl(): String =
    """
    <wsdl:definitions xmlns:tns="http://example.com/simple-service"
                      xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                      xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                      xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                      targetNamespace="http://example.com/simple-service">
      <wsdl:binding name="ProductServiceBinding" type="tns:ProductService">
        <soap:binding transport="http://schemas.xmlsoap.org/soap/http"/>
        <wsdl:operation name="CreateProduct">
          <soap:operation soapAction="http://example.com/simple-service/CreateProduct" style="document"/>
          <wsdl:input><soap:body use="literal"/></wsdl:input>
          <wsdl:output><soap:body use="literal"/></wsdl:output>
        </wsdl:operation>
      </wsdl:binding>
      <wsdl:message name="CreateProductSoapIn">
        <wsdl:part name="parameters" element="tns:CreateProduct"/>
      </wsdl:message>
      <wsdl:message name="CreateProductSoapOut">
        <wsdl:part name="parameters" element="tns:CreateProductResponse"/>
      </wsdl:message>
      <wsdl:portType name="ProductService">
        <wsdl:operation name="CreateProduct">
          <wsdl:input message="tns:CreateProductSoapIn"/>
          <wsdl:output message="tns:CreateProductSoapOut"/>
        </wsdl:operation>
      </wsdl:portType>
      <wsdl:service name="ProductService">
        <wsdl:port name="ProductServicePort" binding="tns:ProductServiceBinding">
          <soap:address location="/CreateProduct"/>
        </wsdl:port>
      </wsdl:service>
      <wsdl:types>
        <xsd:schema targetNamespace="http://example.com/simple-service" elementFormDefault="qualified">
          <xsd:complexType name="Product">
            <xsd:sequence>
              <xsd:element name="name" type="xsd:string"/>
              <xsd:element name="type" type="xsd:string"/>
            </xsd:sequence>
          </xsd:complexType>
          <xsd:element name="CreateProduct">
            <xsd:complexType>
              <xsd:sequence>
                <xsd:element name="product" type="tns:Product"/>
              </xsd:sequence>
            </xsd:complexType>
          </xsd:element>
          <xsd:element name="CreateProductResponse">
            <xsd:complexType>
              <xsd:sequence>
                <xsd:element name="product" type="tns:Product"/>
              </xsd:sequence>
            </xsd:complexType>
          </xsd:element>
        </xsd:schema>
      </wsdl:types>
    </wsdl:definitions>
    """.trimIndent()

private fun simpleComplexTypeExample(): String =
    """
    {
      "http-request": {
        "path": "/CreateProduct",
        "method": "POST",
        "headers": {
          "Content-Type": "text/xml; charset=utf-8",
          "SOAPAction": "\"http://example.com/simple-service/CreateProduct\""
        },
        "body": "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><CreateProduct xmlns=\"http://example.com/simple-service\"><product><name>Phone</name><type>Gadget</type></product></CreateProduct></s:Body></s:Envelope>"
      },
      "http-response": {
        "status": 200,
        "headers": {
          "Content-Type": "text/xml"
        },
        "body": "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><CreateProductResponse xmlns=\"http://example.com/simple-service\"><product><name>Phone</name><type>Gadget</type></product></CreateProductResponse></s:Body></s:Envelope>"
      }
    }
    """.trimIndent()

private fun optionalRequestElementWsdl(): String =
    """
    <wsdl:definitions xmlns:tns="http://example.com/ticket-service"
                      xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                      xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                      xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                      targetNamespace="http://example.com/ticket-service">
      <wsdl:binding name="TicketServiceBinding" type="tns:TicketService">
        <soap:binding transport="http://schemas.xmlsoap.org/soap/http"/>
        <wsdl:operation name="SubmitTicket">
          <soap:operation soapAction="http://example.com/ticket-service/SubmitTicket" style="document"/>
          <wsdl:input><soap:body use="literal"/></wsdl:input>
          <wsdl:output><soap:body use="literal"/></wsdl:output>
        </wsdl:operation>
      </wsdl:binding>
      <wsdl:message name="SubmitTicketSoapIn">
        <wsdl:part name="parameters" element="tns:SubmitTicket"/>
      </wsdl:message>
      <wsdl:message name="SubmitTicketSoapOut">
        <wsdl:part name="parameters" element="tns:SubmitTicketResponse"/>
      </wsdl:message>
      <wsdl:portType name="TicketService">
        <wsdl:operation name="SubmitTicket">
          <wsdl:input message="tns:SubmitTicketSoapIn"/>
          <wsdl:output message="tns:SubmitTicketSoapOut"/>
        </wsdl:operation>
      </wsdl:portType>
      <wsdl:service name="TicketService">
        <wsdl:port name="TicketServicePort" binding="tns:TicketServiceBinding">
          <soap:address location="/SubmitTicket"/>
        </wsdl:port>
      </wsdl:service>
      <wsdl:types>
        <xsd:schema targetNamespace="http://example.com/ticket-service" elementFormDefault="qualified">
          <xsd:element name="SubmitTicket">
            <xsd:complexType>
              <xsd:sequence>
                <xsd:element name="ticketId" type="xsd:string"/>
                <xsd:element name="comment" type="xsd:string" minOccurs="0"/>
              </xsd:sequence>
            </xsd:complexType>
          </xsd:element>
          <xsd:element name="SubmitTicketResponse">
            <xsd:complexType>
              <xsd:sequence>
                <xsd:element name="confirmation" type="xsd:string"/>
              </xsd:sequence>
            </xsd:complexType>
          </xsd:element>
        </xsd:schema>
      </wsdl:types>
    </wsdl:definitions>
    """.trimIndent()

private fun optionalRequestElementExample(): String =
    """
    {
      "http-request": {
        "path": "/SubmitTicket",
        "method": "POST",
        "headers": {
          "Content-Type": "text/xml; charset=utf-8",
          "SOAPAction": "\"http://example.com/ticket-service/SubmitTicket\""
        },
        "body": "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><SubmitTicket xmlns=\"http://example.com/ticket-service\"><ticketId>TCK-123</ticketId></SubmitTicket></s:Body></s:Envelope>"
      },
      "http-response": {
        "status": 200,
        "headers": {
          "Content-Type": "text/xml"
        },
        "body": "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><SubmitTicketResponse xmlns=\"http://example.com/ticket-service\"><confirmation>accepted</confirmation></SubmitTicketResponse></s:Body></s:Envelope>"
      }
    }
    """.trimIndent()

private fun abstractDerivedTypeWsdl(): String =
    """
    <wsdl:definitions xmlns:tns="http://example.com/order-service"
                      xmlns:ord="http://example.com/order-model"
                      xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                      xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                      xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                      targetNamespace="http://example.com/order-service">
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
        <xsd:schema targetNamespace="http://example.com/order-service" elementFormDefault="qualified">
          <xsd:element name="RetrieveOrderDetails">
            <xsd:complexType>
              <xsd:sequence>
                <xsd:element ref="ord:Message"/>
              </xsd:sequence>
            </xsd:complexType>
          </xsd:element>
          <xsd:element name="RetrieveOrderDetailsResponse">
            <xsd:complexType>
              <xsd:sequence>
                <xsd:element ref="ord:Message"/>
              </xsd:sequence>
            </xsd:complexType>
          </xsd:element>
          <xsd:import namespace="http://example.com/order-model"/>
        </xsd:schema>
        <xsd:schema targetNamespace="http://example.com/order-model" elementFormDefault="qualified">
          <xsd:complexType name="Message">
            <xsd:sequence>
              <xsd:element name="command" type="ord:Command"/>
            </xsd:sequence>
          </xsd:complexType>
          <xsd:complexType name="Command">
            <xsd:choice minOccurs="1" maxOccurs="1">
              <xsd:element name="retrieveOrderDetailsRequest">
                <xsd:complexType>
                  <xsd:sequence>
                    <xsd:element name="order" type="ord:Order"/>
                  </xsd:sequence>
                </xsd:complexType>
              </xsd:element>
              <xsd:element name="retrieveOrderDetailsResponse">
                <xsd:complexType>
                  <xsd:sequence>
                    <xsd:element name="order" type="ord:Order"/>
                  </xsd:sequence>
                </xsd:complexType>
              </xsd:element>
            </xsd:choice>
          </xsd:complexType>
          <xsd:complexType name="Order" abstract="true">
            <xsd:sequence>
              <xsd:element name="orderNumber" type="xsd:string"/>
            </xsd:sequence>
          </xsd:complexType>
          <xsd:complexType name="OrderDetails">
            <xsd:complexContent>
              <xsd:extension base="ord:Order">
                <xsd:sequence>
                  <xsd:element name="productName" type="xsd:string" minOccurs="0"/>
                </xsd:sequence>
              </xsd:extension>
            </xsd:complexContent>
          </xsd:complexType>
          <xsd:complexType name="OrderSummary">
            <xsd:complexContent>
              <xsd:extension base="ord:Order">
                <xsd:sequence>
                  <xsd:element name="status" type="xsd:string" minOccurs="0"/>
                </xsd:sequence>
              </xsd:extension>
            </xsd:complexContent>
          </xsd:complexType>
          <xsd:element name="Message" type="ord:Message"/>
        </xsd:schema>
      </wsdl:types>
    </wsdl:definitions>
    """.trimIndent()

private fun abstractDerivedTypeExample(): String =
    """
    {
      "http-request": {
        "path": "/RetrieveOrderDetails",
        "method": "POST",
        "headers": {
          "Content-Type": "text/xml; charset=utf-8",
          "SOAPAction": "\"http://example.com/order-service/RetrieveOrderDetails\""
        },
        "body": "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><RetrieveOrderDetails xmlns=\"http://example.com/order-service\"><Message xmlns=\"http://example.com/order-model\" xmlns:ord=\"http://example.com/order-model\"><command><retrieveOrderDetailsRequest><order xsi:type=\"ord:OrderDetails\"><orderNumber>100234569</orderNumber><productName>Phone</productName></order></retrieveOrderDetailsRequest></command></Message></RetrieveOrderDetails></s:Body></s:Envelope>"
      },
      "http-response": {
        "status": 200,
        "headers": {
          "Content-Type": "text/xml"
        },
        "body": "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><RetrieveOrderDetailsResponse xmlns=\"http://example.com/order-service\"><Message xmlns=\"http://example.com/order-model\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:ord=\"http://example.com/order-model\"><command><retrieveOrderDetailsResponse><order xsi:type=\"ord:OrderDetails\"><orderNumber>100234569</orderNumber><productName>Phone</productName></order></retrieveOrderDetailsResponse></command></Message></RetrieveOrderDetailsResponse></s:Body></s:Envelope>"
      }
    }
    """.trimIndent()

private fun abstractDerivedTypeWrongElementNameExample(): String =
    """
    {
      "http-request": {
        "path": "/RetrieveOrderDetails",
        "method": "POST",
        "headers": {
          "Content-Type": "text/xml; charset=utf-8",
          "SOAPAction": "\"http://example.com/order-service/RetrieveOrderDetails\""
        },
        "body": "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><RetrieveOrderDetails xmlns=\"http://example.com/order-service\"><Message xmlns=\"http://example.com/order-model\"><command><retrieveOrderDetailsRequest><OrderDetails><orderNumber>100234569</orderNumber><productName>Phone</productName></OrderDetails></retrieveOrderDetailsRequest></command></Message></RetrieveOrderDetails></s:Body></s:Envelope>"
      },
      "http-response": {
        "status": 200,
        "headers": {
          "Content-Type": "text/xml"
        },
        "body": "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><RetrieveOrderDetailsResponse xmlns=\"http://example.com/order-service\"><Message xmlns=\"http://example.com/order-model\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:ord=\"http://example.com/order-model\"><command><retrieveOrderDetailsResponse><order xsi:type=\"ord:OrderDetails\"><orderNumber>100234569</orderNumber><productName>Phone</productName></order></retrieveOrderDetailsResponse></command></Message></RetrieveOrderDetailsResponse></s:Body></s:Envelope>"
      }
    }
    """.trimIndent()

private fun substitutionGroupWsdl(): String =
    """
    <wsdl:definitions xmlns:tns="http://example.com/animal-service"
                      xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                      xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                      xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                      targetNamespace="http://example.com/animal-service">
      <wsdl:binding name="AnimalServiceBinding" type="tns:AnimalService">
        <soap:binding transport="http://schemas.xmlsoap.org/soap/http"/>
        <wsdl:operation name="RegisterAnimal">
          <soap:operation soapAction="http://example.com/animal-service/RegisterAnimal" style="document"/>
          <wsdl:input><soap:body use="literal"/></wsdl:input>
          <wsdl:output><soap:body use="literal"/></wsdl:output>
        </wsdl:operation>
      </wsdl:binding>
      <wsdl:message name="RegisterAnimalSoapIn">
        <wsdl:part name="parameters" element="tns:RegisterAnimal"/>
      </wsdl:message>
      <wsdl:message name="RegisterAnimalSoapOut">
        <wsdl:part name="parameters" element="tns:RegisterAnimalResponse"/>
      </wsdl:message>
      <wsdl:portType name="AnimalService">
        <wsdl:operation name="RegisterAnimal">
          <wsdl:input message="tns:RegisterAnimalSoapIn"/>
          <wsdl:output message="tns:RegisterAnimalSoapOut"/>
        </wsdl:operation>
      </wsdl:portType>
      <wsdl:service name="AnimalService">
        <wsdl:port name="AnimalServicePort" binding="tns:AnimalServiceBinding">
          <soap:address location="/RegisterAnimal"/>
        </wsdl:port>
      </wsdl:service>
      <wsdl:types>
        <xsd:schema targetNamespace="http://example.com/animal-service" elementFormDefault="qualified">
          <xsd:complexType name="Animal" abstract="true">
            <xsd:sequence>
              <xsd:element name="name" type="xsd:string"/>
            </xsd:sequence>
          </xsd:complexType>
          <xsd:complexType name="Dog">
            <xsd:complexContent>
              <xsd:extension base="tns:Animal">
                <xsd:sequence>
                  <xsd:element name="breed" type="xsd:string" minOccurs="0"/>
                </xsd:sequence>
              </xsd:extension>
            </xsd:complexContent>
          </xsd:complexType>
          <xsd:complexType name="Cat">
            <xsd:complexContent>
              <xsd:extension base="tns:Animal">
                <xsd:sequence>
                  <xsd:element name="indoor" type="xsd:string" minOccurs="0"/>
                </xsd:sequence>
              </xsd:extension>
            </xsd:complexContent>
          </xsd:complexType>
          <xsd:element name="Animal" type="tns:Animal" abstract="true"/>
          <xsd:element name="DomesticDog" substitutionGroup="tns:Animal" type="tns:Dog"/>
          <xsd:element name="Cat" substitutionGroup="tns:Animal" type="tns:Cat"/>
          <xsd:element name="RegisterAnimal">
            <xsd:complexType>
              <xsd:sequence>
                <xsd:element ref="tns:Animal"/>
              </xsd:sequence>
            </xsd:complexType>
          </xsd:element>
          <xsd:element name="RegisterAnimalResponse">
            <xsd:complexType>
              <xsd:sequence>
                <xsd:element ref="tns:Animal"/>
              </xsd:sequence>
            </xsd:complexType>
          </xsd:element>
        </xsd:schema>
      </wsdl:types>
    </wsdl:definitions>
    """.trimIndent()

private fun substitutionGroupExample(): String =
    """
    {
      "http-request": {
        "path": "/RegisterAnimal",
        "method": "POST",
        "headers": {
          "Content-Type": "text/xml; charset=utf-8",
          "SOAPAction": "\"http://example.com/animal-service/RegisterAnimal\""
        },
        "body": "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><RegisterAnimal xmlns=\"http://example.com/animal-service\"><DomesticDog><name>Pepper</name><breed>Beagle</breed></DomesticDog></RegisterAnimal></s:Body></s:Envelope>"
      },
      "http-response": {
        "status": 200,
        "headers": {
          "Content-Type": "text/xml"
        },
        "body": "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><RegisterAnimalResponse xmlns=\"http://example.com/animal-service\"><DomesticDog><name>Pepper</name><breed>Beagle</breed></DomesticDog></RegisterAnimalResponse></s:Body></s:Envelope>"
      }
    }
    """.trimIndent()
