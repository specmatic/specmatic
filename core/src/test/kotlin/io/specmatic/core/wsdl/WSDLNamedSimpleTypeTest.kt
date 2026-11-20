package io.specmatic.core.wsdl

import io.specmatic.core.CONTENT_TYPE
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.value.StringValue
import io.specmatic.stub.HttpStub
import io.specmatic.test.TestExecutor
import io.specmatic.testPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import io.specmatic.core.pattern.ContractException
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.Arguments
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import org.xml.sax.InputSource
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WSDLNamedSimpleTypeTest {
    @TempDir
    lateinit var directory: File

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `named union validates each member through mock requests`(nested: Boolean) {
        val feature = load(UNION, nested)
        HttpStub(feature, port = testPort()).use { stub ->
            listOf("true", "AB").forEach { value ->
                val response = stub.client.execute(request(value, nested))
                assertThat(response.status).withFailMessage(response.body.toStringLiteral()).isEqualTo(200)
            }
            listOf("abc", "!").forEach { value ->
                val response = stub.client.execute(request(value, nested))
                assertThat(response.status).withFailMessage("Accepted $value").isEqualTo(400)
                assertThat(response.body.toStringLiteral()).contains("Request", value)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `named list validates whitespace separated restricted items through mock requests`(nested: Boolean) {
        val feature = load("""<xs:list itemType="tns:Code"/>""", nested)
        HttpStub(feature, port = testPort()).use { stub ->
            listOf("BA AA", " BA\tAA\nLH\r ", "", " \t\n").forEach { value ->
                val response = stub.client.execute(request(value, nested))
                assertThat(response.status).withFailMessage(response.body.toStringLiteral()).isEqualTo(200)
            }
            listOf("BA a1" to "a1", "BA AAA" to "AAA").forEach { (value, invalidItem) ->
                val response = stub.client.execute(request(value, nested))
                assertThat(response.status).withFailMessage("Accepted $value").isEqualTo(400)
                assertThat(response.body.toStringLiteral()).contains("Request", "[1]", invalidItem)
            }
        }
    }

    @ParameterizedTest
    @MethodSource("typesAndPlacements")
    fun `contract tests validate named simple type response values`(kind: Kind, nested: Boolean) {
        val feature = load(kind.definition, nested)
        (kind.valid.map { it to true } + kind.invalid.map { it to false }).forEach { (value, valid) ->
            val results = feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest) = HttpResponse(200,
                    headers = mapOf(CONTENT_TYPE to "text/xml"), body = StringValue(soap("Response", value, nested)))
            })
            assertThat(results.success()).withFailMessage("Response '$value': ${results.report()}").isEqualTo(valid)
            if (valid) assertThat(results.successCount).isGreaterThan(0)
            else assertThat(results.report()).contains("RESPONSE", "Response")
        }
    }

    @ParameterizedTest
    @MethodSource("typesAndPlacements")
    fun `generated requests and mock responses satisfy the XSD and complete a contract test loop`(kind: Kind, nested: Boolean) {
        val feature = load(kind.definition, nested)
        var exercised = 0
        HttpStub(feature, port = testPort()).use { stub ->
            val results = feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertSchemaValidPayload(request.body.toStringLiteral(), kind.definition, nested)
                    val response = stub.client.execute(request)
                    assertThat(response.status).withFailMessage(response.body.toStringLiteral()).isEqualTo(200)
                    assertSchemaValidPayload(response.body.toStringLiteral(), kind.definition, nested)
                    exercised++
                    return response
                }
            })
            assertThat(results.success()).withFailMessage(results.report()).isTrue()
            assertThat(results.successCount).isGreaterThan(0)
            assertThat(exercised).isGreaterThan(0)
        }
    }

    @ParameterizedTest
    @MethodSource("invalidDeclarations")
    fun `invalid named union and list declarations explain the XSD problem without repeated causes`(definition: String, explanation: String, extraDefinitions: String) {
        val error = assertThrows<ContractException> { load(definition, extraDefinitions = extraDefinitions) }
        val report = error.report()
        assertThat(report).contains("XSD", "Values", explanation)
        if (explanation.contains("Reference ") || explanation.contains("restrictions on"))
            assertThat(report).contains("Specmatic does not support")
        assertThat(report).doesNotContain("memberTypes", "itemType", "Recursive", "simple content base reference", "type of node", "XMLSimpleValue_", "Pattern(")
        val lines = report.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        assertThat(lines).withFailMessage(report).doesNotHaveDuplicates()
        assertThat(lines).anyMatch { it.startsWith("WSDL file: ") && it.endsWith("named.wsdl") }
        assertThat(report).doesNotContain(". in ")
    }

    @ParameterizedTest
    @ValueSource(strings = ["union", "list"])
    fun `unsupported inline types explain how to use a named XSD type`(kind: String) {
        val body = if (kind == "union") """<xs:union memberTypes="xs:boolean xs:string"/>""" else """<xs:list itemType="xs:string"/>"""
        val request = """<xs:element name="Request"><xs:complexType><xs:attribute name="value"><xs:simpleType>$body</xs:simpleType></xs:attribute></xs:complexType></xs:element>"""
        val document = schema("""<xs:restriction base="xs:string"/>""").replace(declaration("Request", false), request)
        val error = assertThrows<ContractException> {
            parseContractFileToFeature(directory.resolve("inline.wsdl").apply { writeText(wsdl(document)) })
        }
        assertThat(error.report()).contains("Specmatic does not support inline XSD ${kind}s", "named XSD")
            .doesNotContain("anonymous", "<xs:", "memberTypes", "itemType")
    }

    @ParameterizedTest
    @CsvSource("union,attribute", "list,attribute", "union,simpleContent", "list,simpleContent")
    fun `named simple types retain validation in attributes and simple content`(kind: String, carrier: String) {
        val definition = if (kind == "union") UNION else """<xs:list itemType="tns:Code"/>"""
        val document = schema(definition).let { source ->
            listOf("Request", "Response").fold(source) { xml, name ->
                val content = if (carrier == "attribute") """<xs:attribute name="code" type="tns:Values" use="required"/>"""
                    else """<xs:simpleContent><xs:extension base="tns:Values"/></xs:simpleContent>"""
                xml.replace(declaration(name, false), """<xs:element name="$name"><xs:complexType>$content</xs:complexType></xs:element>""")
            }
        }
        val feature = parseContractFileToFeature(directory.resolve("consumer.wsdl").apply { writeText(wsdl(document)) })
        fun payload(name: String, value: String) = if (carrier == "attribute")
            soap(name, "").replace("<t:$name>", """<t:$name code="$value">""") else soap(name, value)
        val valid = if (kind == "union") listOf("true", "AB") else listOf("BA AA", "")
        HttpStub(feature, port = testPort()).use { stub ->
            valid.forEach { value ->
                val response = stub.client.execute(request(value).copy(body = StringValue(payload("Request", value))))
                assertThat(response.status).withFailMessage(response.body.toStringLiteral()).isEqualTo(200)
            }
            val response = stub.client.execute(request("bad").copy(body = StringValue(payload("Request", "bad"))))
            assertThat(response.status).isEqualTo(400)
        }
        (valid.map { it to true } + ("bad" to false)).forEach { (value, expected) ->
            val result = feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest) = HttpResponse(200, headers = mapOf(CONTENT_TYPE to "text/xml"), body = StringValue(payload("Response", value)))
            })
            assertThat(result.success()).withFailMessage(result.report()).isEqualTo(expected)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `union errors explain the declared XSD union without internal symbols`(nested: Boolean) {
        val feature = load(UNION, nested)
        fun assertMessage(message: String) {
            assertThat(message).contains("XSD union", "Values", "xs:boolean", "tns:Code", "!")
                .doesNotContain("Pattern(", "XMLSimpleValue_", "(boolean)", "PrimitiveFamily", "Couldn't read value")
            var parentIndent: Int? = null
            var members = 0
            val lines = message.lines()
            lines.forEachIndexed { index, line ->
                if (line.trimStart().startsWith("Value ")) {
                    parentIndent = line.indexOfFirst { !it.isWhitespace() }
                    assertThat(lines.getOrNull(index + 1)).withFailMessage(message).isBlank()
                }
                if (line.trimStart().startsWith("XSD member type ")) {
                    assertThat(parentIndent).isNotNull()
                    assertThat(line.indexOfFirst { !it.isWhitespace() }).withFailMessage(message).isEqualTo(parentIndent!! + 2)
                    members++
                }
            }
            assertThat(members).isGreaterThanOrEqualTo(2)
        }
        HttpStub(feature, port = testPort()).use { stub ->
            val response = stub.client.execute(request("!", nested))
            assertThat(response.status).isEqualTo(400)
            assertMessage(response.body.toStringLiteral())
        }
        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest) = HttpResponse(200,
                headers = mapOf(CONTENT_TYPE to "text/xml"), body = StringValue(soap("Response", "!", nested)))
        })
        assertThat(results.success()).isFalse()
        assertMessage(results.report())
    }

    @Test
    fun `list errors explain the invalid value without a second position convention`() {
        val feature = load("""<xs:list itemType="tns:Code"/>""")
        fun assertMessage(message: String) {
            assertThat(message).contains("Value \"a1\" in XSD list 'Values'", "tns:Code", "[1]", "R1003")
                .doesNotContain("Item 2", "item 2", "index", "ordinal", "Pattern(", "XMLSimpleValue_", "(string)", "PrimitiveFamily")
            val explanation = message.lineSequence().first { it.contains("Value \"a1\" in XSD list") }
            assertThat(explanation).withFailMessage(message).contains("regex [A-Z]+")
        }
        HttpStub(feature, port = testPort()).use { stub ->
            val response = stub.client.execute(request("BA a1"))
            assertThat(response.status).isEqualTo(400)
            assertMessage(response.body.toStringLiteral())
        }
        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest) = HttpResponse(200,
                headers = mapOf(CONTENT_TYPE to "text/xml"), body = StringValue(soap("Response", "BA a1")))
        })
        assertThat(results.success()).isFalse()
        assertMessage(results.report())
    }

    @Test
    fun `repeated XML elements do not substitute for list text`() {
        val feature = load("""<xs:list itemType="xs:string"/>""", nested = true)
        HttpStub(feature, port = testPort()).use { stub ->
            val repeated = soap("Request", "BA AA", nested = true)
                .replace("<t:code>BA AA</t:code>", "<t:code>BA</t:code><t:code>AA</t:code>")
            val response = stub.client.execute(request("BA AA", nested = true).copy(body = StringValue(repeated)))
            assertThat(response.status).isEqualTo(400)
            assertThat(response.body.toStringLiteral()).contains("Request", "code")
        }
    }

    private fun assertSchemaValidPayload(xml: String, definition: String, nested: Boolean) {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val body = document.getElementsByTagNameNS(SOAP_NAMESPACE, "Body").item(0)
        val payload = (0 until body.childNodes.length).map { body.childNodes.item(it) }.filterIsInstance<org.w3c.dom.Element>().single()
        val schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).apply {
            setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        schemaFactory.newSchema(StreamSource(StringReader(schema(definition, nested))))
            .newValidator().validate(DOMSource(payload))
    }

    enum class Kind(val definition: String, val valid: List<String>, val invalid: List<String>) {
        UNION("""<xs:union memberTypes="xs:boolean tns:Code"/>""", listOf("true", "AB"), listOf("abc", "!")),
        REVERSED_UNION("""<xs:union memberTypes="tns:Code xs:boolean"/>""", listOf("false", "BA"), listOf("abc", "!")),
        LIST("""<xs:list itemType="tns:Code"/>""", listOf("BA AA", "", " BA\tAA\n"), listOf("BA a1", "BA AAA")),
        STRING_LIST("""<xs:list itemType="xs:string"/>""", listOf("BA AA", "", " \t\n"), emptyList()),
    }

    private fun load(definition: String, nested: Boolean = false, extraDefinitions: String = "") = parseContractFileToFeature(directory.resolve("named.wsdl").apply {
        writeText(wsdl(schema(definition, nested, extraDefinitions)))
    })

    private fun request(value: String, nested: Boolean = false) = HttpRequest("POST", "/inline", headers = mapOf(CONTENT_TYPE to "text/xml", "SOAPAction" to "/inline/submit"), body = StringValue(soap("Request", value, nested)))

    companion object {
        private const val NAMESPACE = "urn:named-simple-types"
        private const val SOAP_NAMESPACE = "http://schemas.xmlsoap.org/soap/envelope/"
        private const val CODE = """<xs:simpleType name="CodeBase"><xs:restriction base="xs:string"><xs:minLength value="2"/><xs:maxLength value="2"/><xs:pattern value="[A-Z]+"/></xs:restriction></xs:simpleType>
            <xs:simpleType name="Code"><xs:restriction base="tns:CodeBase"/></xs:simpleType>"""
        private const val UNION = """<xs:union memberTypes="xs:boolean tns:Code"/>"""
        private fun soap(name: String, value: String, nested: Boolean = false): String {
            val content = if (nested) "<t:code>$value</t:code>" else value
            return """<s:Envelope xmlns:s="$SOAP_NAMESPACE" xmlns:t="$NAMESPACE"><s:Body><t:$name>$content</t:$name></s:Body></s:Envelope>"""
        }
        @JvmStatic
        fun invalidDeclarations(): List<Arguments> {
            fun case(definition: String, message: String, extra: String = "") = Arguments.of(definition, message, extra)
            fun restricted(base: String) = """<xs:simpleType name="Base">$base</xs:simpleType>
                <xs:simpleType name="Restricted"><xs:restriction base="tns:Base"><xs:pattern value="[A-Z]+"/></xs:restriction></xs:simpleType>"""
            return listOf(
                case("""<xs:union memberTypes="xs:boolean tns:Missing"/>""", "XSD simple type 'tns:Missing' was not found"),
                case("""<xs:list itemType="tns:Missing"/>""", "XSD simple type 'tns:Missing' was not found"),
                case("""<xs:union memberTypes="xs:unsupported"/>""", "Specmatic does not recognize XSD type 'xs:unsupported'"),
                case("""<xs:list itemType="xs:unsupported"/>""", "Specmatic does not recognize XSD type 'xs:unsupported'"),
                case("""<xs:union memberTypes="tns:Values"/>""", "XSD type 'tns:Values' has a circular type reference"),
                case("""<xs:list itemType="tns:Values"/>""", "XSD type 'tns:Values' has a circular type reference"),
                case("""<xs:union/>""", "XSD union 'Values' must name at least one member type"),
                case("""<xs:list/>""", "XSD list 'Values' must name an item type"),
                case("""<xs:union><xs:simpleType><xs:restriction base="xs:string"/></xs:simpleType></xs:union>""", "Reference named XSD types instead"),
                case("""<xs:list><xs:simpleType><xs:restriction base="xs:string"/></xs:simpleType></xs:list>""", "Reference a named XSD type instead"),
                case("""<xs:union memberTypes="tns:Restricted"/>""", "regular-expression restrictions on XSD union 'Base'", restricted("""<xs:union memberTypes="xs:boolean xs:string"/>""")),
                case("""<xs:list itemType="tns:Restricted"/>""", "regular-expression restrictions on XSD list 'Base'", restricted("""<xs:list itemType="xs:string"/>""")),
            )
        }
        @JvmStatic
        fun typesAndPlacements() = Kind.entries.flatMap { kind -> listOf(false, true).map { Arguments.of(kind, it) } }
        private fun declaration(name: String, nested: Boolean) = if (nested)
            """<xs:element name="$name"><xs:complexType><xs:sequence><xs:element name="code" type="tns:Values"/></xs:sequence></xs:complexType></xs:element>"""
            else """<xs:element name="$name" type="tns:Values"/>"""
        private fun schema(definition: String, nested: Boolean = false, extraDefinitions: String = "") = """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="$NAMESPACE" targetNamespace="$NAMESPACE" elementFormDefault="qualified">
            $CODE
            $extraDefinitions
            <xs:simpleType name="Values">$definition</xs:simpleType>
            ${declaration("Request", nested)}
            ${declaration("Response", nested)}
        </xs:schema>"""
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
