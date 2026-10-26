package io.specmatic.core.wsdl

import io.specmatic.core.CONTENT_TYPE
import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.value.StringValue
import io.specmatic.stub.HttpStub
import io.specmatic.test.TestExecutor
import io.specmatic.testPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.w3c.dom.Element
import java.io.File
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import org.xml.sax.InputSource

class WSDLInlineSimpleTypeTest {
    @TempDir
    lateinit var directory: File

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("inlineTypes")
    fun `inline simple types generate schema-valid SOAP messages`(placement: Placement, restriction: Restriction) {
        val schema = schema(
            declaration("Request", placement, restriction.definition),
            declaration("Response", placement, restriction.definition),
        )
        val feature = load(schema)
        val request = feature.scenarios.single().generateHttpRequest()

        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/inline")
        assertSchemaValidPayload(request.body.toStringLiteral(), schema, "Request", placement)

        HttpStub(feature, port = testPort()).use { stub ->
            val response = stub.client.execute(request)
            assertThat(response.status).isEqualTo(200)
            assertSchemaValidPayload(response.body.toStringLiteral(), schema, "Response", placement)
        }
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("inlineTypes")
    fun `mock validates inline simple type request values`(placement: Placement, restriction: Restriction) {
        val feature = load(schema(declaration("Request", placement, restriction.definition), control("Response")))

        HttpStub(feature, port = testPort()).use { stub ->
            restriction.validValues.forEach { value ->
                val response = stub.client.execute(request(soap("Request", placement, value)))
                assertThat(response.status).withFailMessage(response.body.toStringLiteral()).isEqualTo(200)
            }
            restriction.invalidValues.forEach { value ->
                val response = stub.client.execute(request(soap("Request", placement, value)))
                assertThat(response.status).withFailMessage("Accepted invalid value $value").isEqualTo(400)
                assertThat(response.body.toStringLiteral()).contains("Request", value)
            }
        }
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("inlineTypes")
    fun `contract tests validate inline simple type response values`(placement: Placement, restriction: Restriction) {
        val feature = load(schema(control("Request"), declaration("Response", placement, restriction.definition)))

        restriction.validValues.forEach { value ->
            val results = feature.executeTests(provider(soap("Response", placement, value)))
            assertThat(results.success()).withFailMessage(results.report()).isTrue()
        }
        restriction.invalidValues.forEach { value ->
            val results = feature.executeTests(provider(soap("Response", placement, value)))
            assertThat(results.success()).withFailMessage("Accepted invalid response value $value").isFalse()
            assertThat(results.report()).contains("RESPONSE", "Response", value)
        }
    }

    @ParameterizedTest
    @EnumSource(Placement::class)
    fun `unsupported inline list and union types remain rejected`(placement: Placement) {
        listOf("<xs:list itemType=\"xs:string\"/>", "<xs:union memberTypes=\"xs:string xs:int\"/>").forEach { body ->
            assertThrows<ContractException> {
                load(schema(declaration("Request", placement, "<xs:simpleType>$body</xs:simpleType>"), control("Response")))
            }
        }
    }

    private fun load(schema: String): Feature {
        val file = directory.resolve("inline.wsdl").apply { writeText(wsdl(schema)) }
        return parseContractFileToFeature(file)
    }

    private fun provider(body: String): TestExecutor = object : TestExecutor {
        override fun execute(request: HttpRequest): HttpResponse =
            HttpResponse(status = 200, headers = mapOf(CONTENT_TYPE to "text/xml"), body = StringValue(body))
    }

    private fun request(body: String): HttpRequest = HttpRequest(
        method = "POST",
        path = "/inline",
        headers = mapOf(CONTENT_TYPE to "text/xml", "SOAPAction" to "\"/inline/submit\""),
        body = StringValue(body),
    )

    private fun assertSchemaValidPayload(body: String, schema: String, name: String, placement: Placement) {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(body)))
        val soapBody = document.getElementsByTagNameNS(SOAP_NAMESPACE, "Body").item(0)
        val payload = (0 until soapBody.childNodes.length)
            .map { soapBody.childNodes.item(it) }.filterIsInstance<Element>().single()

        assertThat(payload.localName).isEqualTo(name)
        assertThat(payload.namespaceURI).isEqualTo(NAMESPACE)
        if (placement == Placement.NESTED) {
            val code = payload.getElementsByTagNameNS(NAMESPACE, "code")
            assertThat(code.length).isEqualTo(1)
        }
        SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
            .newSchema(StreamSource(StringReader(schema)))
            .newValidator().validate(DOMSource(payload))
    }

    enum class Placement { NESTED, GLOBAL }

    enum class Restriction(val definition: String, val validValues: List<String>, val invalidValues: List<String>) {
        TOKEN(
            """<xs:simpleType><xs:restriction base="xs:token">
                <xs:minLength value="2"/><xs:maxLength value="4"/><xs:pattern value="[A-Z]+"/>
            </xs:restriction></xs:simpleType>""",
            listOf("AB", "ABCD"), listOf("A", "ABCDE", "12"),
        ),
        NAMED_BASE(
            """<xs:simpleType><xs:restriction base="tns:CodeBase">
                <xs:maxLength value="4"/><xs:pattern value="[A-Z]+"/>
            </xs:restriction></xs:simpleType>""",
            listOf("AB", "ABCD"), listOf("A", "ABCDE", "12"),
        ),
        NUMBER(
            """<xs:simpleType><xs:restriction base="xs:int"/></xs:simpleType>""",
            listOf("42"), listOf("not-a-number"),
        ),
    }

    companion object {
        private const val NAMESPACE = "http://example.com/inline-types"
        private const val SOAP_NAMESPACE = "http://schemas.xmlsoap.org/soap/envelope/"

        @JvmStatic
        fun inlineTypes(): List<Arguments> = Placement.entries.flatMap { placement ->
            Restriction.entries.map { restriction -> Arguments.of(placement, restriction) }
        }

        private fun control(name: String): String = """<xs:element name="$name" type="xs:string"/>"""

        private fun declaration(name: String, placement: Placement, definition: String): String = when (placement) {
            Placement.GLOBAL -> """<xs:element name="$name">$definition</xs:element>"""
            Placement.NESTED -> """<xs:element name="$name"><xs:complexType><xs:sequence>
                <xs:element name="code">$definition</xs:element>
            </xs:sequence></xs:complexType></xs:element>"""
        }

        private fun soap(name: String, placement: Placement, value: String): String {
            val content = when (placement) {
                Placement.GLOBAL -> value
                Placement.NESTED -> "<tns:code>$value</tns:code>"
            }
            return """<soap:Envelope xmlns:soap="$SOAP_NAMESPACE" xmlns:tns="$NAMESPACE">
                <soap:Body><tns:$name>$content</tns:$name></soap:Body>
            </soap:Envelope>"""
        }

        private fun schema(request: String, response: String): String = """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="$NAMESPACE"
                       targetNamespace="$NAMESPACE" elementFormDefault="qualified">
                <xs:simpleType name="CodeBase"><xs:restriction base="xs:token">
                    <xs:minLength value="2"/><xs:maxLength value="6"/>
                </xs:restriction></xs:simpleType>
                $request
                $response
            </xs:schema>
        """.trimIndent()

        private fun wsdl(schema: String): String = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:tns="$NAMESPACE" targetNamespace="$NAMESPACE">
                <wsdl:types>$schema</wsdl:types>
                <wsdl:message name="Input"><wsdl:part name="body" element="tns:Request"/></wsdl:message>
                <wsdl:message name="Output"><wsdl:part name="body" element="tns:Response"/></wsdl:message>
                <wsdl:portType name="Port"><wsdl:operation name="submit">
                    <wsdl:input message="tns:Input"/><wsdl:output message="tns:Output"/>
                </wsdl:operation></wsdl:portType>
                <wsdl:binding name="Binding" type="tns:Port">
                    <soap:binding transport="http://schemas.xmlsoap.org/soap/http"/>
                    <wsdl:operation name="submit"><soap:operation soapAction="/inline/submit"/>
                        <wsdl:input><soap:body use="literal"/></wsdl:input>
                        <wsdl:output><soap:body use="literal"/></wsdl:output>
                    </wsdl:operation>
                </wsdl:binding>
                <wsdl:service name="InlineTypes"><wsdl:port name="Port" binding="tns:Binding">
                    <soap:address location="http://localhost:9000/inline"/>
                </wsdl:port></wsdl:service>
            </wsdl:definitions>
        """.trimIndent()
    }
}
