package io.specmatic.core.wsdl

import io.specmatic.conversions.wsdlContentToFeature
import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.HttpStub
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WSDLFaultExampleAndDictionaryInvestigationTest {
    @Test
    fun `WSDL soap fault bindings do not become 500 contract scenarios`() {
        val feature = wsdlContentToFeature(productWsdlWithFault(), "product.wsdl")

        assertThat(feature.scenarios).hasSize(2)
        assertThat(feature.scenarios.map { it.httpResponsePattern.status }.distinct()).containsExactly(200)
        assertThat(feature.scenarios.map { it.name }).containsExactlyInAnyOrder("GetProduct", "GetInventory")
    }

    @Test
    fun `POST_500 example in underscore examples dir is unused and does not override 200 generation`(@TempDir tempDir: File) {
        val wsdlFile = tempDir.resolve("product.wsdl").apply { writeText(productWsdlWithFault()) }
        val examplesDir = tempDir.resolve("product_examples").apply { mkdirs() }
        examplesDir.resolve("POST_500_1.json").writeText(faultExampleJson())

        val (feature, unused) = parseContractFileToFeature(
            wsdlFile,
            exampleDirPaths = listOf(examplesDir.canonicalPath)
        ).loadExternalisedExamplesAndListUnloadableExamples()

        assertThat(unused.map { File(it).name }).containsExactly("POST_500_1.json")
        assertThat(feature.scenarios.flatMap { it.examples }).isEmpty()
        assertThat(feature.scenarios.map { it.httpResponsePattern.status }.distinct()).containsExactly(200)

        val generatedIdentifiers = generatedProductVariantIdentifiers(feature)
        assertThat(generatedIdentifiers).isNotEmpty
        assertThat(generatedIdentifiers).allMatch { it == "000010" || it == "000123" }
        assertThat(generatedIdentifiers).doesNotContain("999999")
    }

    @Test
    fun `200 example is picked up and supplies the SOAP request body for that one test`(@TempDir tempDir: File) {
        val wsdlFile = tempDir.resolve("product.wsdl").apply { writeText(productWsdlWithFault()) }
        val examplesDir = tempDir.resolve("product_examples").apply { mkdirs() }
        examplesDir.resolve("POST_200_1.json").writeText(successExampleJson(productVariantIdentifier = "000123"))
        examplesDir.resolve("POST_500_1.json").writeText(faultExampleJson())

        val (feature, unused) = parseContractFileToFeature(
            wsdlFile,
            exampleDirPaths = listOf(examplesDir.canonicalPath)
        ).loadExternalisedExamplesAndListUnloadableExamples()

        assertThat(unused.map { File(it).name }).containsExactly("POST_500_1.json")

        val getProduct = feature.scenarios.single { it.name == "GetProduct" }
        val getInventory = feature.scenarios.single { it.name == "GetInventory" }
        assertThat(getProduct.examples.flatMap { it.rows }.map { it.name }).containsExactly("POST_200_1")
        assertThat(getInventory.examples).isEmpty()

        val requestBodies = mutableListOf<String>()
        val result = HttpStub(feature, examplesDir.listFiles().orEmpty().map(ScenarioStub::readFromFile)).use { stub ->
            feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    requestBodies.add(request.body.toStringLiteral())
                    return stub.client.execute(request)
                }
            })
        }

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
        val getProductBodies = requestBodies.filter { it.contains("GetProduct") }
        assertThat(getProductBodies).isNotEmpty
        assertThat(getProductBodies).allMatch { it.contains("<ProductVariantIdentifier>000123</ProductVariantIdentifier>") }
        assertThat(requestBodies.joinToString()).doesNotContain("999999")
    }

    @Test
    fun `field-name dictionary values are not used when generating SOAP request bodies`(@TempDir tempDir: File) {
        val wsdlFile = tempDir.resolve("product.wsdl").apply { writeText(productWsdlWithFault()) }
        val dictionaryFile = tempDir.resolve("dictionary.json").apply {
            writeText("""{"ProductVariantIdentifier": "000123"}""")
        }

        val feature = parseContractFileToFeature(wsdlFile).useDictionary(dictionaryFile.canonicalPath)
        val generatedIdentifiers = generatedProductVariantIdentifiers(feature, samples = 40)

        assertThat(generatedIdentifiers).isNotEmpty
        assertThat(generatedIdentifiers).contains("000010")
    }

    @Test
    fun `widening the XSD pattern lets the fault value appear in generated 200 requests`() {
        val feature = wsdlContentToFeature(
            productWsdlWithFault(pattern = "000010|000123|999999"),
            "product.wsdl"
        )
        val generatedIdentifiers = generatedProductVariantIdentifiers(feature, samples = 80)
        assertThat(generatedIdentifiers).contains("999999")
    }
}

private fun generatedProductVariantIdentifiers(feature: Feature, samples: Int = 20): List<String> {
    val identifier = Regex("<ProductVariantIdentifier>([^<]+)</ProductVariantIdentifier>")
    return (1..samples).flatMap { _ ->
        feature.scenarios.map { scenario ->
            identifier.find(scenario.generateHttpRequest().body.toStringLiteral())?.groupValues?.get(1)
        }
    }.filterNotNull()
}

private fun successExampleJson(productVariantIdentifier: String): String = """
{
  "name": "POST_200_1",
  "http-request": {
    "method": "POST",
    "path": "/",
    "headers": {
      "SOAPAction": "\"/products/GetProduct\"",
      "Content-Type": "text/xml"
    },
    "body": "<S:Envelope xmlns:S=\"http://schemas.xmlsoap.org/soap/envelope/\"><S:Body><ns0:GetProduct xmlns:ns0=\"http://www.example.com/products\"><ProductVariantIdentifier>$productVariantIdentifier</ProductVariantIdentifier></ns0:GetProduct></S:Body></S:Envelope>"
  },
  "http-response": {
    "status": 200,
    "headers": {
      "Content-Type": "text/xml"
    },
    "body": "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\"><SOAP-ENV:Body><ns0:GetProductResponse xmlns:ns0=\"http://www.example.com/products\"><status>ok</status></ns0:GetProductResponse></SOAP-ENV:Body></SOAP-ENV:Envelope>"
  }
}
""".trimIndent()

private fun faultExampleJson(): String = """
{
  "name": "POST_500_1",
  "http-request": {
    "method": "POST",
    "path": "/",
    "headers": {
      "SOAPAction": "\"/products/GetProduct\"",
      "Content-Type": "text/xml"
    },
    "body": "<S:Envelope xmlns:S=\"http://schemas.xmlsoap.org/soap/envelope/\"><S:Body><ns0:GetProduct xmlns:ns0=\"http://www.example.com/products\"><ProductVariantIdentifier>999999</ProductVariantIdentifier></ns0:GetProduct></S:Body></S:Envelope>"
  },
  "http-response": {
    "status": 500,
    "headers": {
      "Content-Type": "text/xml"
    },
    "body": "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\"><SOAP-ENV:Body><SOAP-ENV:Fault><faultcode>SOAP-ENV:Server</faultcode><faultstring>Invalid product</faultstring></SOAP-ENV:Fault></SOAP-ENV:Body></SOAP-ENV:Envelope>"
  }
}
""".trimIndent()

private fun productWsdlWithFault(pattern: String = "000010|000123"): String = """
<definitions name="ProductAPI"
             targetNamespace="http://www.example.com/products"
             xmlns:tns="http://www.example.com/products"
             xmlns:xsd="http://www.w3.org/2001/XMLSchema"
             xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
             xmlns="http://schemas.xmlsoap.org/wsdl/">
    <types>
        <xsd:schema targetNamespace="http://www.example.com/products">
            <xsd:simpleType name="ProductVariantIdentifierType">
                <xsd:restriction base="xsd:string">
                    <xsd:pattern value="$pattern"/>
                </xsd:restriction>
            </xsd:simpleType>
            <xsd:element name="GetProduct">
                <xsd:complexType>
                    <xsd:sequence>
                        <xsd:element name="ProductVariantIdentifier" type="tns:ProductVariantIdentifierType"/>
                    </xsd:sequence>
                </xsd:complexType>
            </xsd:element>
            <xsd:element name="GetProductResponse">
                <xsd:complexType>
                    <xsd:sequence>
                        <xsd:element name="status" type="xsd:string"/>
                    </xsd:sequence>
                </xsd:complexType>
            </xsd:element>
            <xsd:element name="GetInventory">
                <xsd:complexType>
                    <xsd:sequence>
                        <xsd:element name="ProductVariantIdentifier" type="tns:ProductVariantIdentifierType"/>
                    </xsd:sequence>
                </xsd:complexType>
            </xsd:element>
            <xsd:element name="GetInventoryResponse">
                <xsd:complexType>
                    <xsd:sequence>
                        <xsd:element name="stock" type="xsd:int"/>
                    </xsd:sequence>
                </xsd:complexType>
            </xsd:element>
            <xsd:element name="InvalidProductFault">
                <xsd:complexType>
                    <xsd:sequence>
                        <xsd:element name="message" type="xsd:string"/>
                    </xsd:sequence>
                </xsd:complexType>
            </xsd:element>
        </xsd:schema>
    </types>
    <message name="GetProductRequest"><part name="parameters" element="tns:GetProduct"/></message>
    <message name="GetProductResponse"><part name="parameters" element="tns:GetProductResponse"/></message>
    <message name="GetInventoryRequest"><part name="parameters" element="tns:GetInventory"/></message>
    <message name="GetInventoryResponse"><part name="parameters" element="tns:GetInventoryResponse"/></message>
    <message name="InvalidProductFaultMessage"><part name="parameters" element="tns:InvalidProductFault"/></message>
    <portType name="ProductAPIPortType">
        <operation name="GetProduct">
            <input message="tns:GetProductRequest"/>
            <output message="tns:GetProductResponse"/>
            <fault name="InvalidProductFault" message="tns:InvalidProductFaultMessage"/>
        </operation>
        <operation name="GetInventory">
            <input message="tns:GetInventoryRequest"/>
            <output message="tns:GetInventoryResponse"/>
        </operation>
    </portType>
    <binding name="ProductAPIBinding" type="tns:ProductAPIPortType">
        <soap:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>
        <operation name="GetProduct">
            <soap:operation soapAction="/products/GetProduct"/>
            <input><soap:body use="literal"/></input>
            <output><soap:body use="literal"/></output>
            <fault name="InvalidProductFault">
                <soap:fault name="InvalidProductFault" use="literal"/>
            </fault>
        </operation>
        <operation name="GetInventory">
            <soap:operation soapAction="/products/GetInventory"/>
            <input><soap:body use="literal"/></input>
            <output><soap:body use="literal"/></output>
        </operation>
    </binding>
    <service name="ProductAPIService">
        <port name="ProductAPIPort" binding="tns:ProductAPIBinding">
            <soap:address location="http://localhost:8090/"/>
        </port>
    </service>
</definitions>
""".trimIndent()
