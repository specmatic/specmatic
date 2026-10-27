package io.specmatic.core.wsdl

import io.specmatic.core.CONTENT_TYPE
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.value.StringValue
import io.specmatic.test.TestExecutor
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.pattern.ContractException
import org.junit.jupiter.api.assertThrows
import io.specmatic.stub.HttpStub
import io.specmatic.testPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.validation.SchemaFactory

class WSDLIncludeTest {
    @TempDir
    lateinit var directory: File

    @ParameterizedTest
    @ValueSource(strings = ["embedded", "imported-xsd", "imported-wsdl"])
    fun `local includes generate schema-valid SOAP messages`(entry: String) {
        write("response.xsd", schema(payload("Response", "status")))
        val root = write("root.xsd", schema("""<xs:include schemaLocation="response.xsd"/>${payload("Request", "code")}"""))
        val definition = when (entry) {
            "imported-xsd" -> wsdl(importSchema("root.xsd"))
            "imported-wsdl" -> {
                write("types.wsdl", """<wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/" xmlns:tns="$NAMESPACE" targetNamespace="http://example.com/types">
                    <wsdl:types>${root.readText()}</wsdl:types></wsdl:definitions>""")
                wsdl("").replace("<wsdl:types>", """<wsdl:import namespace="http://example.com/types" location="types.wsdl"/><wsdl:types>""")
            }
            else -> wsdl(root.readText())
        }
        val feature = parseContractFileToFeature(write("include.wsdl", definition))
        val request = feature.scenarios.single().generateHttpRequest()
        assertThat(validatePayload(request.body.toStringLiteral(), root).localName).isEqualTo("Request")
        HttpStub(feature, port = testPort()).use { stub ->
            val response = stub.client.execute(request)
            assertThat(response.status).isEqualTo(200)
            assertThat(validatePayload(response.body.toStringLiteral(), root).localName).isEqualTo("Response")
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `chameleon includes adopt the including namespace`(nested: Boolean) {
        val code = """<xs:simpleType name="Code"><xs:restriction base="xs:string">
            <xs:minLength value="2"/><xs:maxLength value="4"/>
        </xs:restriction></xs:simpleType>"""
        val typeDefinition = if (nested) {
            write("nested/code.xsd", schema(code).replace("targetNamespace=\"$NAMESPACE\"", ""))
            "<xs:include schemaLocation=\"nested/code.xsd\"/>"
        } else code
        val declarations = """
            $typeDefinition
            <xs:element name="Response" type="Result"/>
            <xs:complexType name="Result"><xs:sequence><xs:element name="status" type="Code"/></xs:sequence></xs:complexType>
        """
        write("common.xsd", schema(declarations).replace("targetNamespace=\"$NAMESPACE\"", ""))
        val root = write("root.xsd", schema("""<xs:include schemaLocation="common.xsd"/>${payload("Request", "code")}"""))
        val feature = parseContractFileToFeature(write("include.wsdl", wsdl(root.readText())))
        val request = feature.scenarios.single().generateHttpRequest()
        validatePayload(request.body.toStringLiteral(), root)
        HttpStub(feature, port = testPort()).use { stub ->
            val response = stub.client.execute(request)
            assertThat(response.status).isEqualTo(200)
            val payload = validatePayload(response.body.toStringLiteral(), root)
            assertThat(payload.namespaceURI).isEqualTo(NAMESPACE)
            assertThat(payload.getElementsByTagNameNS(NAMESPACE, "status").length).isEqualTo(1)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["inline", "named"])
    fun `one chameleon file remains distinct under two namespaces`(placement: String) {
        val a = "http://example.com/a"
        val b = "http://example.com/b"
        val declarations = if (placement == "inline") payload("Box", "code") else """
            <xs:element name="Box" type="BoxType"/>
            <xs:complexType name="BoxType"><xs:sequence><xs:element name="code" type="Code"/></xs:sequence></xs:complexType>
            <xs:simpleType name="Code"><xs:restriction base="xs:string"><xs:minLength value="2"/><xs:maxLength value="4"/></xs:restriction></xs:simpleType>
        """
        write("common.xsd", schema(declarations).replace("targetNamespace=\"$NAMESPACE\"", ""))
        write("a.xsd", schema("""<xs:include schemaLocation="common.xsd"/>""").replace(NAMESPACE, a))
        write("b.xsd", schema("""<xs:include schemaLocation="common.xsd"/>""").replace(NAMESPACE, b))
        val root = write("root.xsd", schema("""
            <xs:import namespace="$a" schemaLocation="a.xsd"/>
            <xs:import namespace="$b" schemaLocation="b.xsd"/>
            <xs:element name="Request"><xs:complexType><xs:sequence>
                <xs:element ref="a:Box"/><xs:element ref="b:Box"/>
            </xs:sequence></xs:complexType></xs:element>
            <xs:element name="Response" type="xs:string"/>
        """).replace("xmlns:tns=", "xmlns:a=\"$a\" xmlns:b=\"$b\" xmlns:tns="))
        SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(root)
        val definition = wsdl(root.readText()).replaceFirst("xmlns:tns=", "xmlns:a=\"$a\" xmlns:b=\"$b\" xmlns:tns=")
        val feature = parseContractFileToFeature(write("include.wsdl", definition))
        val payload = validatePayload(feature.scenarios.single().generateHttpRequest().body.toStringLiteral(), root)
        assertThat(payload.getElementsByTagNameNS(a, "Box").length).isEqualTo(1)
        assertThat(payload.getElementsByTagNameNS(b, "Box").length).isEqualTo(1)
        assertThat(payload.getElementsByTagNameNS(a, "code").length).isEqualTo(1)
        assertThat(payload.getElementsByTagNameNS(b, "code").length).isEqualTo(1)
    }

    @Test
    fun `incompatible include namespace reports source and cause`() {
        val wrong = "http://example.com/wrong"
        write("wrong.xsd", schema("<xs:complexType name=\"Unused\"/>").replace(NAMESPACE, wrong))
        val declarations = """<xs:include schemaLocation="wrong.xsd"/>${payload("Request", "code")}${payload("Response", "status")}"""
        val error = assertThrows<ContractException> {
            parseContractFileToFeature(write("include.wsdl", wsdl(schema(declarations))))
        }
        assertThat(error.message).contains("include", "include.wsdl", "wrong.xsd", NAMESPACE, wrong)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing-location", "blank-location", "missing-file", "malformed-file", "directory-file", "nested-missing-file"])
    fun `invalid includes report their source and cause`(problem: String) {
        val include = when (problem) {
            "missing-location" -> "<xs:include/>"
            "blank-location" -> "<xs:include schemaLocation=\" \"/>"
            "nested-missing-file" -> {
                write("nested/parent.xsd", schema("""<xs:include schemaLocation="../broken.xsd"/>"""))
                "<xs:include schemaLocation=\"nested/parent.xsd\"/>"
            }
            else -> "<xs:include schemaLocation=\"broken.xsd\"/>"
        }
        if (problem == "malformed-file") write("broken.xsd", "<not-closed")
        if (problem == "directory-file") directory.resolve("broken.xsd").mkdirs()
        val error = assertThrows<ContractException> {
            parseContractFileToFeature(write("include.wsdl", wsdl(schema(include + payload("Request", "code") + payload("Response", "status")))))
        }
        assertThat(error.message).contains("include", "include.wsdl")
        assertThat(error.message).contains(if (problem.endsWith("location")) "schemaLocation" else "broken.xsd")
        if (problem == "nested-missing-file") assertThat(error.message).contains("parent.xsd")
    }

    @ParameterizedTest
    @ValueSource(strings = ["repeated", "diamond", "cycle"])
    fun `repeated and cyclic includes retain reachable declarations`(shape: String) {
        directory.resolve("nested").mkdirs()
        val commonIncludes = if (shape == "cycle") "<xs:include schemaLocation=\"root.xsd\"/>" else ""
        write("common.xsd", schema(commonIncludes + payload("Response", "status")))
        val includes = when (shape) {
            "repeated" -> """<xs:include schemaLocation="common.xsd"/><xs:include schemaLocation="nested/../common.xsd"/>"""
            "diamond" -> {
                write("left.xsd", schema("""<xs:include schemaLocation="common.xsd"/>"""))
                write("right.xsd", schema("""<xs:include schemaLocation="common.xsd"/>"""))
                """<xs:include schemaLocation="left.xsd"/><xs:include schemaLocation="right.xsd"/>"""
            }
            else -> "<xs:include schemaLocation=\"common.xsd\"/>"
        }
        val root = write("root.xsd", schema(includes + payload("Request", "code")))
        SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(root)
        val feature = parseContractFileToFeature(write("include.wsdl", wsdl(importSchema("root.xsd"))))
        val request = feature.scenarios.single().generateHttpRequest()
        validatePayload(request.body.toStringLiteral(), root)
        HttpStub(feature, port = testPort()).use { stub ->
            val response = stub.client.execute(request)
            assertThat(response.status).isEqualTo(200)
            assertThat(validatePayload(response.body.toStringLiteral(), root).localName).isEqualTo("Response")
        }
    }

    @Test
    fun `nested include paths resolve against their containing file`() {
        write("schemas/types.xsd", schema(payload("Response", "rightStatus")))
        write("types.xsd", schema(payload("Response", "wrongStatus")))
        write("schemas/deep/middle.xsd", schema("""<xs:include schemaLocation="../types.xsd"/>"""))
        val root = write("schemas/root.xsd", schema("""<xs:include schemaLocation="deep/middle.xsd"/>${payload("Request", "code")}"""))
        val feature = parseContractFileToFeature(write("include.wsdl", wsdl(importSchema("schemas/root.xsd"))))
        val request = feature.scenarios.single().generateHttpRequest()
        validatePayload(request.body.toStringLiteral(), root)
        HttpStub(feature, port = testPort()).use { stub ->
            val response = stub.client.execute(request)
            assertThat(response.status).isEqualTo(200)
            val payload = validatePayload(response.body.toStringLiteral(), root)
            assertThat(payload.getElementsByTagNameNS(NAMESPACE, "rightStatus").length).isEqualTo(1)
            assertThat(payload.getElementsByTagNameNS(NAMESPACE, "wrongStatus").length).isZero()
        }
    }

    @Test
    fun `included declarations retain their schema context`() {
        val types = schema("""
            <xs:complexType name="Wrapper"><xs:sequence><xs:element name="part" type="tns:Child"/></xs:sequence></xs:complexType>
            <xs:complexType name="Child"><xs:sequence><xs:element name="code" type="p:string"/></xs:sequence></xs:complexType>
        """).replace("elementFormDefault=\"qualified\"", "elementFormDefault=\"unqualified\"")
            .replace("xmlns:tns=", "xmlns:p=\"http://www.w3.org/2001/XMLSchema\" xmlns:tns=")
        write("types.xsd", types)
        val root = write("root.xsd", schema("""
            <xs:include schemaLocation="types.xsd"/>
            <xs:element name="Request"><xs:complexType><xs:sequence><xs:element name="wrapper" type="p:Wrapper"/></xs:sequence></xs:complexType></xs:element>
            <xs:element name="Response" type="xs:string"/>
        """).replace("xmlns:tns=", "xmlns:p=\"$NAMESPACE\" xmlns:tns="))
        val feature = parseContractFileToFeature(write("include.wsdl", wsdl(root.readText())))
        val payload = validatePayload(feature.scenarios.single().generateHttpRequest().body.toStringLiteral(), root)
        assertThat(payload.getElementsByTagNameNS(NAMESPACE, "wrapper").length).isEqualTo(1)
        assertThat(payload.getElementsByTagNameNS("", "part").length).isEqualTo(1)
        assertThat(payload.getElementsByTagNameNS("", "code").length).isEqualTo(1)
    }

    @Test
    fun `mock validates included request constraints`() {
        val root = constrainedRoot()
        val feature = parseContractFileToFeature(write("include.wsdl", wsdl(root.readText())))
        val generatedRequest = feature.scenarios.single().generateHttpRequest()
        validatePayload(generatedRequest.body.toStringLiteral(), root)
        HttpStub(feature, port = testPort()).use { stub ->
            val valid = stub.client.execute(request(soap("Request", "code", "AB")))
            assertThat(valid.status).withFailMessage(valid.body.toStringLiteral()).isEqualTo(200)
            validatePayload(valid.body.toStringLiteral(), root)
            listOf("A", "ABCDE").forEach { value ->
                val invalid = stub.client.execute(request(soap("Request", "code", value)))
                assertThat(invalid.status).isEqualTo(400)
                assertThat(invalid.body.toStringLiteral()).contains("code", value)
            }
        }
    }

    @Test
    fun `contract execution validates included response constraints`() {
        val root = constrainedRoot()
        val feature = parseContractFileToFeature(write("include.wsdl", wsdl(root.readText())))
        fun provider(value: String) = object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse = HttpResponse(status = 200,
                headers = mapOf(CONTENT_TYPE to "text/xml"), body = StringValue(soap("Response", "status", value)))
        }
        validatePayload(soap("Response", "status", "AB"), root)
        val valid = feature.executeTests(provider("AB"))
        assertThat(valid.success()).withFailMessage(valid.report()).isTrue()
        listOf("A", "ABCDE").forEach { value ->
            val invalid = feature.executeTests(provider(value))
            assertThat(invalid.success()).isFalse()
            assertThat(invalid.report()).contains("RESPONSE", "status", value)
        }
    }

    @ParameterizedTest
    @CsvSource("element,element", "simpleType,complexType")
    fun `conflicting included declarations report both sources`(firstKind: String, secondKind: String) {
        fun declaration(kind: String) = when (kind) {
            "element" -> "<xs:element name=\"Conflict\" type=\"xs:string\"/>"
            "simpleType" -> "<xs:simpleType name=\"Conflict\"><xs:restriction base=\"xs:string\"/></xs:simpleType>"
            else -> "<xs:complexType name=\"Conflict\"/>"
        }
        write("types.xsd", schema(declaration(secondKind)))
        val root = schema("""<xs:include schemaLocation="types.xsd"/>${declaration(firstKind)}${payload("Request", "code")}${payload("Response", "status")}""")
        val error = assertThrows<ContractException> { parseContractFileToFeature(write("include.wsdl", wsdl(root))) }
        assertThat(error.message).contains("Conflict", "include.wsdl", "types.xsd")
    }

    @Test
    fun `included component references retain supported constraints`() {
        write("types.xsd", schema("""
            <xs:complexType name="Data"><xs:sequence><xs:element ref="tns:CodeElement"/></xs:sequence>
                <xs:attributeGroup ref="tns:TraceAttributes"/></xs:complexType>
            <xs:element name="CodeElement" type="tns:Code"/>
            <xs:simpleType name="Code"><xs:restriction base="xs:string"><xs:minLength value="2"/><xs:maxLength value="4"/></xs:restriction></xs:simpleType>
            <xs:attribute name="trace" type="xs:int"/>
            <xs:attributeGroup name="TraceAttributes"><xs:attribute ref="tns:trace" use="required"/></xs:attributeGroup>
        """))
        val root = schema("""<xs:include schemaLocation="types.xsd"/>
            <xs:element name="Request" type="tns:Data"/><xs:element name="Response" type="xs:string"/>""")
        SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(write("root.xsd", root))
        val feature = parseContractFileToFeature(write("include.wsdl", wsdl(root)))
        val generated = feature.scenarios.single().generateHttpRequest()
        val payload = soapPayload(generated.body.toStringLiteral())
        val trace = payload.getAttribute("trace")
        val code = payload.getElementsByTagNameNS(NAMESPACE, "CodeElement").item(0).textContent
        assertThat(trace.toIntOrNull()).isNotNull()
        assertThat(code.length).isBetween(2, 4)
        HttpStub(feature, port = testPort()).use { stub ->
            assertThat(stub.client.execute(generated).status).isEqualTo(200)
            val invalidTrace = generated.body.toStringLiteral().replace("trace=\"$trace\"", "trace=\"not-an-int\"")
            assertThat(invalidTrace).isNotEqualTo(generated.body.toStringLiteral())
            val attributeError = stub.client.execute(generated.copy(body = StringValue(invalidTrace)))
            assertThat(attributeError.status).isEqualTo(400)
            assertThat(attributeError.body.toStringLiteral()).contains("trace", "not-an-int")
            val invalidCode = generated.body.toStringLiteral().replace(">$code</", ">A</")
            assertThat(invalidCode).isNotEqualTo(generated.body.toStringLiteral())
            val elementError = stub.client.execute(generated.copy(body = StringValue(invalidCode)))
            assertThat(elementError.status).isEqualTo(400)
            assertThat(elementError.body.toStringLiteral()).contains("CodeElement")
        }
    }

    private fun constrainedRoot(): File {
        write("types.xsd", schema("""<xs:simpleType name="Code"><xs:restriction base="xs:string">
            <xs:minLength value="2"/><xs:maxLength value="4"/>
        </xs:restriction></xs:simpleType>"""))
        val request = payload("Request", "code").replace("type=\"xs:string\"", "type=\"tns:Code\"")
        val response = payload("Response", "status").replace("type=\"xs:string\"", "type=\"tns:Code\"")
        return write("root.xsd", schema("""<xs:include schemaLocation="types.xsd"/>$request$response"""))
    }

    private fun request(body: String) = HttpRequest(method = "POST", path = "/include",
        headers = mapOf(CONTENT_TYPE to "text/xml", "SOAPAction" to "\"/include/submit\""), body = StringValue(body))

    private fun write(name: String, content: String): File = directory.resolve(name).apply {
        parentFile.mkdirs()
        writeText(content)
    }

    private fun soapPayload(body: String): Element {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(InputSource(StringReader(body)))
        val soapBody = document.getElementsByTagNameNS("http://schemas.xmlsoap.org/soap/envelope/", "Body").item(0)
        return (0 until soapBody.childNodes.length).map { soapBody.childNodes.item(it) }
            .filterIsInstance<Element>().single()
    }

    private fun validatePayload(body: String, schema: File): Element {
        val payload = soapPayload(body)
        SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(schema)
            .newValidator().validate(DOMSource(payload))
        return payload
    }

    companion object {
        private const val NAMESPACE = "http://example.com/orders"
        private fun soap(name: String, child: String, value: String): String = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" xmlns:o="$NAMESPACE"><s:Body>
                <o:$name><o:$child>$value</o:$child></o:$name>
            </s:Body></s:Envelope>
        """.trimIndent()
        private fun importSchema(location: String): String = """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="http://example.com/holder">
                <xs:import namespace="$NAMESPACE" schemaLocation="$location"/>
            </xs:schema>
        """.trimIndent()
        private fun schema(declarations: String): String = """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="$NAMESPACE"
                targetNamespace="$NAMESPACE" elementFormDefault="qualified">$declarations</xs:schema>
        """.trimIndent()
        private fun payload(name: String, child: String): String = """
            <xs:element name="$name"><xs:complexType><xs:sequence>
                <xs:element name="$child" type="xs:string"/>
            </xs:sequence></xs:complexType></xs:element>
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
                    <wsdl:operation name="submit"><soap:operation soapAction="/include/submit"/>
                        <wsdl:input><soap:body use="literal"/></wsdl:input>
                        <wsdl:output><soap:body use="literal"/></wsdl:output>
                    </wsdl:operation>
                </wsdl:binding>
                <wsdl:service name="Includes"><wsdl:port name="Port" binding="tns:Binding">
                    <soap:address location="http://localhost:9000/include"/>
                </wsdl:port></wsdl:service>
            </wsdl:definitions>
        """.trimIndent()
    }
}
