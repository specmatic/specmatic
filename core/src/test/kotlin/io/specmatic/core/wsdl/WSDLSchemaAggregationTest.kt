package io.specmatic.core.wsdl

import io.specmatic.core.CONTENT_TYPE
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.test.TestExecutor
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.pattern.ContractException
import org.junit.jupiter.api.assertThrows
import io.specmatic.stub.HttpStub
import io.specmatic.testPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WSDLSchemaAggregationTest {
    @TempDir
    lateinit var directory: File

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun `same namespace documents generate both SOAP payloads`(embedded: Boolean, reversed: Boolean) {
        val documents = listOf(schema("Request", "code"), schema("Response", "status"))
        val feature = parseContractFileToFeature(writeWsdl(documents, embedded, reversed))
        val request = feature.scenarios.single().generateHttpRequest()
        assertThat(request.body.toStringLiteral()).contains("Request", "code")
        HttpStub(feature, port = testPort()).use { stub ->
            val response = stub.client.execute(request)
            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toStringLiteral()).contains("Response", "status")
        }
    }

    @Test
    fun `schema documents retain their own attribute constraints`() {
        val requestSchema = constrainedSchema("Request", "code")
            .replace("</xs:complexType>", "<xs:attribute name=\"trace\" type=\"xs:int\" use=\"required\"/></xs:complexType>")
        val responseSchema = schema("Response", "status")
            .replace("</xs:complexType>", "<xs:attribute name=\"trace\" type=\"xs:boolean\" use=\"required\"/></xs:complexType>")
        val feature = parseContractFileToFeature(writeWsdl(listOf(requestSchema, responseSchema)))
        val validRequest = soap("Request", "code", "AB").replace("<o:Request>", "<o:Request trace=\"42\">")
        validatePayload(validRequest, requestSchema)
        HttpStub(feature, port = testPort()).use { stub ->
            val valid = stub.client.execute(request(validRequest))
            assertThat(valid.status).withFailMessage(valid.body.toStringLiteral()).isEqualTo(200)
            val invalid = stub.client.execute(request(validRequest.replace("trace=\"42\"", "trace=\"not-an-int\"")))
            assertThat(invalid.status).isEqualTo(400)
            assertThat(invalid.body.toStringLiteral()).contains("trace", "not-an-int")
        }
        val validResponse = soap("Response", "status", "OK").replace("<o:Response>", "<o:Response trace=\"true\">")
            .replace("o:status", "status")
        validatePayload(validResponse, responseSchema)
        fun provider(body: String) = object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse = HttpResponse(status = 200,
                headers = mapOf(CONTENT_TYPE to "text/xml"), body = StringValue(body))
        }
        val valid = feature.executeTests(provider(validResponse))
        assertThat(valid.success()).withFailMessage(valid.report()).isTrue()
        val invalid = feature.executeTests(provider(validResponse.replace("trace=\"true\"", "trace=\"not-a-boolean\"")))
        assertThat(invalid.success()).isFalse()
        assertThat(invalid.report()).contains("trace", "not-a-boolean")
    }

    @ParameterizedTest
    @CsvSource("element,element,false", "simpleType,simpleType,false", "simpleType,complexType,false", "attribute,attribute,false", "attributeGroup,attributeGroup,false", "element,element,true")
    fun `conflicting declarations report both sources`(firstKind: String, secondKind: String, embedded: Boolean) {
        val file = writeWsdl(listOf(
            document(declaration(firstKind, "Conflict") + "<xs:element name=\"Request\" type=\"xs:string\"/>"),
            document(declaration(secondKind, "Conflict") + "<xs:element name=\"Response\" type=\"xs:string\"/>")
        ), embedded = embedded)
        val error = assertThrows<ContractException> { parseContractFileToFeature(file) }
        val firstSource = if (embedded) "aggregation.wsdl#schema-1" else "schema-0.xsd"
        val secondSource = if (embedded) "aggregation.wsdl#schema-2" else "schema-1.xsd"
        assertThat(error.message).contains("Conflict", firstSource, secondSource)
    }

    @Test
    fun `repeated imports are one source document`() {
        val file = writeWsdl(listOf(schema("Request", "code"), schema("Response", "status")))
        directory.resolve("nested").mkdir()
        file.writeText(file.readText().replace("</xs:schema>", """<xs:import namespace="$NAMESPACE" schemaLocation="nested/../schema-0.xsd"/></xs:schema>"""))
        val feature = parseContractFileToFeature(file)
        assertThat(feature.scenarios.single().generateHttpRequest().body.toStringLiteral()).contains("Request", "code")
    }

    @Test
    fun `cross document references retain supported constraints`() {
        val requestSchema = document("""<xs:element name="Request" type="o:Data"/>""")
        val responseSchema = schema("Response", "status")
        val declarations = document("""
            <xs:complexType name="Data"><xs:sequence>
                <xs:element ref="o:CodeElement"/>
            </xs:sequence><xs:attributeGroup ref="o:TraceAttributes"/></xs:complexType>
            <xs:element name="CodeElement" type="o:Code"/>
            <xs:simpleType name="Code"><xs:restriction base="xs:string"><xs:minLength value="2"/><xs:maxLength value="4"/></xs:restriction></xs:simpleType>
            <xs:attribute name="trace" type="xs:int"/>
            <xs:attributeGroup name="TraceAttributes"><xs:attribute ref="o:trace" use="required"/></xs:attributeGroup>
        """)
        val feature = parseContractFileToFeature(writeWsdl(listOf(requestSchema, responseSchema, declarations)))
        val request = feature.scenarios.single().generateHttpRequest()
        assertThat(request.body.toStringLiteral()).contains("CodeElement", "trace=")
        val payloadDocument = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(InputSource(StringReader(request.body.toStringLiteral())))
        val payload = payloadDocument.getElementsByTagNameNS("*", "Request").item(0) as Element
        assertThat(payload.getAttribute("trace").toIntOrNull()).isNotNull()
        val code = payloadDocument.getElementsByTagNameNS("*", "CodeElement").item(0).textContent
        assertThat(code.length).isBetween(2, 4)
        HttpStub(feature, port = testPort()).use { stub ->
            assertThat(stub.client.execute(request).status).isEqualTo(200)
            val invalidBody = request.body.toStringLiteral().replace("trace=\"${payload.getAttribute("trace")}\"", "trace=\"not-an-int\"")
            val invalid = stub.client.execute(request.copy(body = StringValue(invalidBody)))
            assertThat(invalid.status).isEqualTo(400)
            assertThat(invalid.body.toStringLiteral()).contains("trace", "not-an-int")
            val invalidCodeBody = request.body.toStringLiteral().replace(">" + code + "</", ">A</")
            val invalidCode = stub.client.execute(request.copy(body = StringValue(invalidCodeBody)))
            assertThat(invalidCode.status).isEqualTo(400)
            assertThat(invalidCode.body.toStringLiteral()).contains("CodeElement")
        }
    }

    @Test
    fun `mock validates constraints from shared namespace documents`() {
        val feature = parseContractFileToFeature(writeWsdl(listOf(constrainedSchema("Request", "code"), constrainedSchema("Response", "status"))))
        HttpStub(feature, port = testPort()).use { stub ->
            val valid = stub.client.execute(request(soap("Request", "code", "AB")))
            assertThat(valid.status).withFailMessage(valid.body.toStringLiteral()).isEqualTo(200)
            val invalid = stub.client.execute(request(soap("Request", "code", "A")))
            assertThat(invalid.status).isEqualTo(400)
            assertThat(invalid.body.toStringLiteral()).contains("code", "A")
        }
    }

    @Test
    fun `contract execution validates shared namespace responses`() {
        val feature = parseContractFileToFeature(writeWsdl(listOf(constrainedSchema("Request", "code"), constrainedSchema("Response", "status"))))
        fun provider(value: String) = object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse = HttpResponse(status = 200,
                headers = mapOf(CONTENT_TYPE to "text/xml"), body = StringValue(soap("Response", "status", value)))
        }
        val valid = feature.executeTests(provider("AB"))
        assertThat(valid.success()).withFailMessage(valid.report()).isTrue()
        val invalid = feature.executeTests(provider("A"))
        assertThat(invalid.success()).isFalse()
        assertThat(invalid.report()).contains("RESPONSE", "status", "A")
    }

    @Test
    fun `legal declaration names remain distinct`() {
        val requestSchema = document("""
            <xs:element name="Request" type="o:Request"/>
            <xs:complexType name="Request"><xs:sequence><xs:element name="code" type="xs:string"/></xs:sequence></xs:complexType>
        """)
        val otherNamespace = document("""<xs:element name="Request" type="xs:int"/>""")
            .replace(NAMESPACE, "http://example.com/other")
        val feature = parseContractFileToFeature(writeWsdl(listOf(requestSchema, schema("Response", "status"), otherNamespace), embedded = true))
        val request = feature.scenarios.single().generateHttpRequest()
        assertThat(request.body.toStringLiteral()).contains("Request", "code")
        HttpStub(feature, port = testPort()).use { stub ->
            val response = stub.client.execute(request)
            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toStringLiteral()).contains("Response", "status")
        }
    }

    @Test
    fun `imported WSDL schemas keep their own source identity`() {
        val root = writeWsdl(listOf(schema("Response", "status")), embedded = true)
        directory.resolve("types.wsdl").writeText("""
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/" xmlns:tns="$NAMESPACE" targetNamespace="http://example.com/types">
                <wsdl:types>${schema("Request", "code")}</wsdl:types>
            </wsdl:definitions>
        """)
        root.writeText(root.readText().replace("<wsdl:types>", """<wsdl:import namespace="http://example.com/types" location="types.wsdl"/><wsdl:types>"""))
        val feature = parseContractFileToFeature(root)
        assertThat(feature.scenarios.single().generateHttpRequest().body.toStringLiteral()).contains("Request", "code")
    }

    @ParameterizedTest
    @CsvSource("false", "true")
    fun `complex children use their declaration document defaults`(reversed: Boolean) {
        val requestSchema = document("""<xs:element name="Request"><xs:complexType><xs:sequence>
            <xs:element name="wrapper" type="o:WrapperType"/>
        </xs:sequence></xs:complexType></xs:element>""")
            .replace("targetNamespace=", "elementFormDefault=\"qualified\" targetNamespace=")
        val typesSchema = document("""
            <xs:element name="Response" type="xs:string"/>
            <xs:complexType name="WrapperType"><xs:sequence><xs:element name="part" type="o:ChildType"/></xs:sequence></xs:complexType>
            <xs:complexType name="ChildType"><xs:sequence><xs:element name="code" type="xs:string"/></xs:sequence></xs:complexType>
        """)
        val feature = parseContractFileToFeature(writeWsdl(listOf(requestSchema, typesSchema), reversed = reversed))
        val body = feature.scenarios.single().generateHttpRequest().body.toStringLiteral()
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(InputSource(StringReader(body)))
        assertThat(document.getElementsByTagNameNS("", "part").length).withFailMessage(body).isEqualTo(1)
        assertThat(document.getElementsByTagNameNS(NAMESPACE, "wrapper").length).withFailMessage(body).isEqualTo(1)
    }

    @Test
    fun `existing cross namespace imports generate valid SOAP messages`() {
        directory.resolve("external.xsd").writeText("""
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="http://example.com/external" elementFormDefault="qualified">
                <xs:complexType name="Person"><xs:sequence><xs:element name="name" type="xs:string"/></xs:sequence></xs:complexType>
            </xs:schema>
        """)
        val rootSchema = document("""
            <xs:import namespace="http://example.com/external" schemaLocation="external.xsd"/>
            <xs:element name="Request" type="ext:Person"/>
            <xs:element name="Response" type="xs:string"/>
        """).replace("targetNamespace=", "xmlns:ext=\"http://example.com/external\" elementFormDefault=\"qualified\" targetNamespace=")
        val feature = parseContractFileToFeature(writeWsdl(listOf(rootSchema), embedded = true))
        val request = feature.scenarios.single().generateHttpRequest()
        validatePayload(request.body.toStringLiteral(), rootSchema)
        HttpStub(feature, port = testPort()).use { stub ->
            val response = stub.client.execute(request)
            assertThat(response.status).isEqualTo(200)
            validatePayload(response.body.toStringLiteral(), rootSchema)
        }
    }

    @ParameterizedTest
    @CsvSource("false", "true")
    fun `prefix bindings remain local to each schema document`(reversed: Boolean) {
        val requestSchema = document("""<xs:element name="Request"><xs:complexType><xs:sequence>
            <xs:element name="code" type="p:Code"/>
        </xs:sequence></xs:complexType></xs:element>""")
            .replace("targetNamespace=", "xmlns:p=\"$NAMESPACE\" elementFormDefault=\"qualified\" targetNamespace=")
        val typeSchema = document("""<xs:simpleType name="Code"><xs:restriction base="p:string">
            <xs:minLength value="2"/><xs:maxLength value="4"/>
        </xs:restriction></xs:simpleType>""")
            .replace("targetNamespace=", "xmlns:p=\"http://www.w3.org/2001/XMLSchema\" targetNamespace=")
        val responseSchema = constrainedSchema("Response", "status")
        val feature = parseContractFileToFeature(writeWsdl(listOf(requestSchema, typeSchema, responseSchema), reversed = reversed))
        val request = feature.scenarios.single().generateHttpRequest()
        validatePayload(request.body.toStringLiteral(), constrainedSchema("Request", "code"))
        HttpStub(feature, port = testPort()).use { stub ->
            val response = stub.client.execute(request)
            assertThat(response.status).isEqualTo(200)
            validatePayload(response.body.toStringLiteral(), responseSchema)
        }
    }

    @Test
    fun `public constructor retains generation from raw schema maps`() {
        val rawSchema = document("""
            <xs:element name="Request"><xs:complexType><xs:sequence><xs:element name="code" type="xs:string"/></xs:sequence></xs:complexType></xs:element>
            <xs:element name="Response" type="xs:string"/>
        """).replace("targetNamespace=", "xmlns:tns=\"$NAMESPACE\" elementFormDefault=\"qualified\" targetNamespace=")
        val parsed = WSDL(toXMLNode(wsdl(rawSchema)), directory.resolve("raw.wsdl").path)
        val raw = parsed.copy(schemas = mapOf(NAMESPACE to toXMLNode(rawSchema)))
        val request = raw.toFeature("raw.wsdl").scenarios.single().generateHttpRequest()
        val payload = validatePayload(request.body.toStringLiteral(), rawSchema)
        assertThat(payload.getElementsByTagNameNS(NAMESPACE, "code").length).isEqualTo(1)
    }

    private fun request(body: String) = HttpRequest(method = "POST", path = "/aggregation",
        headers = mapOf(CONTENT_TYPE to "text/xml", "SOAPAction" to "\"/aggregation/submit\""), body = StringValue(body))

    private fun writeWsdl(documents: List<String>, embedded: Boolean = false, reversed: Boolean = false): File {
        val ordered = if (reversed) documents.reversed() else documents
        val types = if (embedded) ordered.joinToString("\n") else {
            val imports = ordered.mapIndexed { index, document ->
                val filename = "schema-$index.xsd"
                directory.resolve(filename).writeText(document)
                """<xs:import namespace="$NAMESPACE" schemaLocation="$filename"/>"""
            }.joinToString("\n")
            """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="http://example.com/holder">$imports</xs:schema>"""
        }
        return directory.resolve("aggregation.wsdl").apply { writeText(wsdl(types)) }
    }

    private fun validatePayload(body: String, schema: String): Element {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(InputSource(StringReader(body)))
        val soapBody = document.getElementsByTagNameNS("http://schemas.xmlsoap.org/soap/envelope/", "Body").item(0)
        val payload = (0 until soapBody.childNodes.length).map { soapBody.childNodes.item(it) }
            .filterIsInstance<Element>().single()
        SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(StreamSource(StringReader(schema)).apply { systemId = directory.resolve("validation.xsd").toURI().toString() })
            .newValidator().validate(DOMSource(payload))
        return payload
    }

    companion object {
        private const val NAMESPACE = "http://example.com/orders"
        private fun constrainedSchema(name: String, child: String): String = schema(name, child)
            .replace("targetNamespace=", "elementFormDefault=\"qualified\" targetNamespace=")
            .replace("<xs:element name=\"$child\" type=\"xs:string\"/>", """
                <xs:element name="$child"><xs:simpleType><xs:restriction base="xs:string">
                    <xs:minLength value="2"/><xs:maxLength value="4"/>
                </xs:restriction></xs:simpleType></xs:element>
            """)
        private fun soap(name: String, child: String, value: String): String = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" xmlns:o="$NAMESPACE"><s:Body>
                <o:$name><o:$child>$value</o:$child></o:$name>
            </s:Body></s:Envelope>
        """.trimIndent()
        private fun declaration(kind: String, name: String): String = when (kind) {
            "element", "attribute" -> """<xs:$kind name="$name" type="xs:string"/>"""
            "simpleType" -> """<xs:simpleType name="$name"><xs:restriction base="xs:string"/></xs:simpleType>"""
            else -> """<xs:$kind name="$name"/>"""
        }
        private fun document(declarations: String): String = """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:o="$NAMESPACE" targetNamespace="$NAMESPACE">$declarations</xs:schema>
        """.trimIndent()
        private fun schema(name: String, child: String): String = """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="$NAMESPACE">
                <xs:element name="$name"><xs:complexType><xs:sequence>
                    <xs:element name="$child" type="xs:string"/>
                </xs:sequence></xs:complexType></xs:element>
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
                    <wsdl:operation name="submit"><soap:operation soapAction="/aggregation/submit"/>
                        <wsdl:input><soap:body use="literal"/></wsdl:input>
                        <wsdl:output><soap:body use="literal"/></wsdl:output>
                    </wsdl:operation>
                </wsdl:binding>
                <wsdl:service name="InlineTypes"><wsdl:port name="Port" binding="tns:Binding">
                    <soap:address location="http://localhost:9000/aggregation"/>
                </wsdl:port></wsdl:service>
            </wsdl:definitions>
        """.trimIndent()
    }
}
