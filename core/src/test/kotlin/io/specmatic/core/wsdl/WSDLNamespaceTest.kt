package io.specmatic.core.wsdl

import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.stub.HttpStub
import io.specmatic.test.TestExecutor
import io.specmatic.testPort
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WSDLNamespaceTest {
    @TempDir lateinit var directory: File

    @Test
    fun `WSDL request rejects a different namespace URI`() {
        val file = directory.resolve("namespace.wsdl").apply { writeText(wsdl(schema(payload("Request", "code") + payload("Response", "status")))) }
        val scenario = parseContractFileToFeature(file).scenarios.single()
        val valid = scenario.generateHttpRequest()
        val invalid = valid.copy(body = StringValue(valid.body.toStringLiteral().replace(NAMESPACE, "urn:wrong")))
        assertThat(scenario.httpRequestPattern.matches(valid, scenario.resolver).isSuccess()).isTrue()
        val result = scenario.httpRequestPattern.matches(invalid, scenario.resolver)
        assertThat(result.isSuccess()).isFalse()
        assertThat(result.reportString()).contains(NAMESPACE, "urn:wrong")
    }

    @Test
    fun `response compatibility rejects a namespace change but accepts a prefix change`() {
        fun scenario(name: String, definition: String) = parseContractFileToFeature(
            directory.resolve(name).apply { writeText(definition) }
        ).scenarios.single()
        val definition = wsdl(schema(payload("Request", "code") + payload("Response", "status")))
        val original = scenario("original.wsdl", definition)
        val renamed = scenario("prefix.wsdl", definition.replace("tns", "alternate"))
        val changed = scenario("changed.wsdl", definition.replace(NAMESPACE, "urn:changed"))
        assertThat(original.httpResponsePattern.encompasses(renamed.httpResponsePattern, original.resolver, renamed.resolver).isSuccess()).isTrue()
        val result = original.httpResponsePattern.encompasses(changed.httpResponsePattern, original.resolver, changed.resolver)
        assertThat(result.isSuccess()).isFalse()
        assertThat(result.reportString()).contains(NAMESPACE, "urn:changed")
    }

    @Test
    fun `qualified attribute matching uses URI instead of prefix spelling`() {
        val declarations = """<xs:element name="Request"><xs:complexType>
            <xs:attribute name="id" type="xs:string" use="required"/>
            </xs:complexType></xs:element><xs:element name="Response" type="xs:string"/>"""
        val file = directory.resolve("attributes.wsdl").apply {
            writeText(wsdl(schema(declarations).replace("elementFormDefault=\"qualified\"", "attributeFormDefault=\"qualified\"")))
        }
        val scenario = parseContractFileToFeature(file).scenarios.single()
        val generated = scenario.generateHttpRequest()
        val valid = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" xmlns:o="$NAMESPACE" xmlns:alias="$NAMESPACE">
            <s:Body><o:Request alias:id="123"/></s:Body></s:Envelope>"""
        val result = scenario.httpRequestPattern.matches(generated.copy(body = StringValue(valid)), scenario.resolver)
        assertThat(result.isSuccess()).describedAs(result.reportString()).isTrue()
        val invalid = valid.replace("xmlns:alias=\"$NAMESPACE\"", "xmlns:alias=\"urn:wrong\"")
        val invalidResult = scenario.httpRequestPattern.matches(generated.copy(body = StringValue(invalid)), scenario.resolver)
        assertThat(invalidResult.isSuccess()).isFalse()
        assertThat(invalidResult.reportString()).contains(NAMESPACE, "urn:wrong")
    }

    @Test
    fun `attribute compatibility accepts aliases but detects qualification changes`() {
        val declarations = """<xs:element name="Request" type="xs:string"/>
            <xs:element name="Response"><xs:complexType>
            <xs:attribute name="id" type="xs:string" use="required"/>
            </xs:complexType></xs:element>"""
        val definition = wsdl(schema(declarations).replace("elementFormDefault=\"qualified\"", "attributeFormDefault=\"qualified\""))
        fun scenario(name: String, text: String) = parseContractFileToFeature(directory.resolve(name).apply { writeText(text) }).scenarios.single()
        val original = scenario("original-attributes.wsdl", definition)
        val renamed = scenario("renamed-attributes.wsdl", definition.replace("tns", "alternate"))
        val unqualified = scenario("unqualified-attributes.wsdl", definition.replace("attributeFormDefault=\"qualified\"", "attributeFormDefault=\"unqualified\""))
        val aliasResult = original.httpResponsePattern.encompasses(renamed.httpResponsePattern, original.resolver, renamed.resolver)
        assertThat(aliasResult.isSuccess()).describedAs(aliasResult.reportString()).isTrue()
        assertThat(original.httpResponsePattern.encompasses(unqualified.httpResponsePattern, original.resolver, unqualified.resolver).isSuccess()).isFalse()
        assertThat(unqualified.httpResponsePattern.encompasses(original.httpResponsePattern, unqualified.resolver, original.resolver).isSuccess()).isFalse()
    }

    @Test
    fun `nillable elements still require the declared namespace`() {
        val declarations = """<xs:element name="Request" type="xs:string" nillable="true"/>
            <xs:element name="Response" type="xs:string"/>"""
        val scenario = parseContractFileToFeature(directory.resolve("nil.wsdl").apply { writeText(wsdl(schema(declarations))) }).scenarios.single()
        val request = scenario.generateHttpRequest()
        val body = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" xmlns:w="urn:wrong">
            <s:Body><w:Request/></s:Body></s:Envelope>"""
        assertThat(scenario.httpRequestPattern.matches(request.copy(body = StringValue(body)), scenario.resolver).isSuccess()).isFalse()
    }

    @Test
    fun `unprefixed xsi type uses default namespace rather than element namespace`() {
        val declarations = """<xs:complexType name="Base"><xs:sequence><xs:element name="code" type="xs:string"/></xs:sequence></xs:complexType>
            <xs:complexType name="Derived"><xs:complexContent><xs:extension base="tns:Base"><xs:sequence><xs:element name="extra" type="xs:string"/></xs:sequence></xs:extension></xs:complexContent></xs:complexType>
            <xs:element name="Request" type="tns:Base"/><xs:element name="Response" type="xs:string"/>"""
        val scenario = parseContractFileToFeature(directory.resolve("xsi.wsdl").apply { writeText(wsdl(schema(declarations))) }).scenarios.single()
        val request = scenario.generateHttpRequest()
        val valid = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" xmlns:o="$NAMESPACE" xmlns:i="http://www.w3.org/2001/XMLSchema-instance">
            <s:Body><o:Request xmlns="$NAMESPACE" i:type="Derived"><o:code>c</o:code><o:extra>x</o:extra></o:Request></s:Body></s:Envelope>"""
        val validResult = scenario.httpRequestPattern.matches(request.copy(body = StringValue(valid)), scenario.resolver)
        assertThat(validResult.isSuccess()).describedAs(validResult.reportString()).isTrue()
        val invalid = valid.replace("xmlns=\"$NAMESPACE\"", "xmlns=\"urn:wrong\"")
        assertThat(scenario.httpRequestPattern.matches(request.copy(body = StringValue(invalid)), scenario.resolver).isSuccess()).isFalse()
    }

    @Test
    fun `local namespace wildcard accepts an explicit default namespace reset`() {
        val declarations = """<xs:element name="Request"><xs:complexType><xs:sequence>
            <xs:any namespace="##local" processContents="skip"/>
            </xs:sequence></xs:complexType></xs:element><xs:element name="Response" type="xs:string"/>"""
        val scenario = parseContractFileToFeature(directory.resolve("wildcard.wsdl").apply { writeText(wsdl(schema(declarations))) }).scenarios.single()
        val request = scenario.generateHttpRequest()
        val body = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" xmlns:o="$NAMESPACE">
            <s:Body><o:Request><local xmlns=""/></o:Request></s:Body></s:Envelope>"""
        val result = scenario.httpRequestPattern.matches(request.copy(body = StringValue(body)), scenario.resolver)
        assertThat(result.isSuccess()).describedAs(result.reportString()).isTrue()
    }

    @Test
    fun `SOAP 1_2 uses its envelope namespace for matching and generation`() {
        val definition = wsdl(schema(payload("Request", "code") + payload("Response", "status")))
            .replace("http://schemas.xmlsoap.org/wsdl/soap/", "http://schemas.xmlsoap.org/wsdl/soap12/")
        val scenario = parseContractFileToFeature(directory.resolve("soap12.wsdl").apply { writeText(definition) }).scenarios.single()
        val request = scenario.generateHttpRequest()
        assertThat(request.body.toStringLiteral()).contains("http://www.w3.org/2003/05/soap-envelope")
        assertThat(scenario.httpRequestPattern.matches(request, scenario.resolver).isSuccess()).isTrue()
        val wrong = request.copy(body = StringValue(request.body.toStringLiteral().replace("http://www.w3.org/2003/05/soap-envelope", "http://schemas.xmlsoap.org/soap/envelope/")))
        assertThat(scenario.httpRequestPattern.matches(wrong, scenario.resolver).isSuccess()).isFalse()
    }

    @Test
    fun `an empty SOAP header cannot bypass namespace validation`() {
        val scenario = parseContractFileToFeature(directory.resolve("headers.wsdl").apply {
            writeText(wsdl(schema(payload("Request", "code") + payload("Response", "status"))))
        }).scenarios.single()
        val request = scenario.generateHttpRequest()
        val body = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" xmlns:o="$NAMESPACE" xmlns:w="urn:wrong">
            <w:Header/><s:Body><o:Request><o:code>value</o:code></o:Request></s:Body></s:Envelope>"""
        assertThat(scenario.httpRequestPattern.matches(request.copy(body = StringValue(body)), scenario.resolver).isSuccess()).isFalse()
    }

    @Test
    fun `a same named fault in another namespace cannot bypass payload validation`() {
        val scenario = parseContractFileToFeature(directory.resolve("fault.wsdl").apply {
            writeText(wsdl(schema(payload("Request", "code") + payload("Response", "status"))))
        }).scenarios.single()
        val request = scenario.generateHttpRequest()
        val body = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" xmlns:w="urn:wrong">
            <s:Body><w:Fault/></s:Body></s:Envelope>"""
        assertThat(scenario.httpRequestPattern.matches(request.copy(body = StringValue(body)), scenario.resolver).isSuccess()).isFalse()
    }

    @Test
    fun `HTTP mock accepts namespace aliases and rejects a wrong URI`() {
        val feature = parseContractFileToFeature(directory.resolve("runtime.wsdl").apply {
            writeText(wsdl(schema(payload("Request", "code") + payload("Response", "status"))))
        })
        val request = feature.scenarios.single().generateHttpRequest()
        HttpStub(feature, port = testPort()).use { stub ->
            assertThat(stub.client.execute(request).status).isEqualTo(200)
            val alias = request.body.toStringLiteral().replace("tns", "alias")
            assertThat(stub.client.execute(request.copy(body = StringValue(alias))).status).isEqualTo(200)
            val invalid = request.copy(body = StringValue(alias.replace(NAMESPACE, "urn:wrong")))
            val response = stub.client.execute(invalid)
            assertThat(response.status).isEqualTo(400)
            assertThat(response.body.toStringLiteral()).contains(NAMESPACE, "urn:wrong")
        }
    }

    @Test
    fun `contract runner rejects a response with a wrong namespace URI`() {
        val feature = parseContractFileToFeature(directory.resolve("response.wsdl").apply {
            writeText(wsdl(schema(payload("Request", "code") + payload("Response", "status"))))
        })
        val response = feature.scenarios.single().generateHttpResponse()
        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse = response.copy(
                body = StringValue(response.body.toStringLiteral().replace(NAMESPACE, "urn:wrong"))
            )
        })
        assertThat(results.failureCount).isGreaterThan(0)
        assertThat(results.report()).contains(NAMESPACE, "urn:wrong")
    }

    @Test
    fun `optional same local name in another namespace does not hide a value failure`() {
        directory.resolve("other.xsd").writeText("""<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:other">
            <xs:element name="code" type="xs:int"/></xs:schema>""")
        val declarations = """<xs:import namespace="urn:other" schemaLocation="other.xsd"/>
            <xs:element name="code" type="xs:string"/>
            <xs:element name="Request"><xs:complexType><xs:sequence>
                <xs:element ref="tns:code" minOccurs="0"/><xs:element ref="other:code"/>
            </xs:sequence></xs:complexType></xs:element><xs:element name="Response" type="xs:string"/>"""
        val definition = wsdl(schema(declarations)).replace("<wsdl:definitions ", "<wsdl:definitions xmlns:other=\"urn:other\" ")
        val scenario = parseContractFileToFeature(directory.resolve("names.wsdl").apply { writeText(definition) }).scenarios.single()
        val request = scenario.generateHttpRequest()
        val body = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" xmlns:o="$NAMESPACE" xmlns:b="urn:other">
            <s:Body><o:Request><b:code>123</b:code></o:Request></s:Body></s:Envelope>"""
        val valid = scenario.httpRequestPattern.matches(request.copy(body = StringValue(body)), scenario.resolver)
        assertThat(valid.isSuccess()).describedAs(valid.reportString()).isTrue()
        val invalid = scenario.httpRequestPattern.matches(request.copy(body = StringValue(body.replace("123", "not-a-number"))), scenario.resolver)
        assertThat(invalid.isSuccess()).isFalse()
        assertThat(invalid.reportString()).contains("code", "not-a-number")
    }

    @Test
    fun `unbound xsi type prefixes fail request and response validation`() {
        val declarations = """<xs:complexType name="Base"><xs:sequence><xs:element name="code" type="xs:string"/></xs:sequence></xs:complexType>
            <xs:element name="Request" type="tns:Base"/><xs:element name="Response" type="tns:Base"/>"""
        val scenario = parseContractFileToFeature(directory.resolve("unbound-type.wsdl").apply { writeText(wsdl(schema(declarations))) }).scenarios.single()
        fun body(name: String) = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" xmlns:o="$NAMESPACE" xmlns:i="http://www.w3.org/2001/XMLSchema-instance">
            <s:Body><o:$name i:type="missing:Derived"><o:code>value</o:code></o:$name></s:Body></s:Envelope>"""
        val request = scenario.generateHttpRequest().copy(body = StringValue(body("Request")))
        val response = scenario.generateHttpResponse().copy(body = StringValue(body("Response")))
        val requestResult = scenario.httpRequestPattern.matches(request, scenario.resolver)
        val responseResult = scenario.httpResponsePattern.matchesResponse(response, scenario.resolver)
        assertThat(requestResult.isSuccess()).isFalse()
        assertThat(responseResult.isSuccess()).isFalse()
        assertThat(requestResult.reportString()).contains("missing", "Derived")
        assertThat(responseResult.reportString()).contains("missing", "Derived")
    }

    @Test
    fun `nillable empty payloads validate qualified attributes in requests and responses`() {
        fun declaration(name: String) = """<xs:element name="$name"><xs:complexType><xs:sequence>
            <xs:element name="item" nillable="true"><xs:complexType>
                <xs:attribute name="id" type="xs:string" use="required" form="qualified"/>
            </xs:complexType></xs:element></xs:sequence></xs:complexType></xs:element>"""
        val definition = wsdl(schema(declaration("Request") + declaration("Response")))
        val scenario = parseContractFileToFeature(directory.resolve("nil-attributes.wsdl").apply { writeText(definition) }).scenarios.single()
        fun body(name: String, attribute: String) = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" xmlns:o="$NAMESPACE" xmlns:w="urn:wrong" xmlns:i="http://www.w3.org/2001/XMLSchema-instance">
            <s:Body><o:$name><o:item i:nil="true" $attribute/></o:$name></s:Body></s:Envelope>"""
        val request = scenario.generateHttpRequest()
        val response = scenario.generateHttpResponse()
        for (attribute in listOf("o:id=\"123\"", "w:id=\"123\"", "id=\"123\"", "")) {
            val requestResult = scenario.httpRequestPattern.matches(request.copy(body = StringValue(body("Request", attribute))), scenario.resolver)
            val responseResult = scenario.httpResponsePattern.matchesResponse(response.copy(body = StringValue(body("Response", attribute))), scenario.resolver)
            assertThat(requestResult.isSuccess()).describedAs("request attribute $attribute: ${requestResult.reportString()}").isEqualTo(attribute.startsWith("o:"))
            assertThat(responseResult.isSuccess()).describedAs("response attribute $attribute: ${responseResult.reportString()}").isEqualTo(attribute.startsWith("o:"))
        }
    }

    companion object {
        private const val NAMESPACE = "http://example.com/orders"
        private val namespaceFixture = WSDLNamespaceFixture(NAMESPACE, "/namespaces")

        private fun schema(declarations: String): String =
            namespaceFixture.schema(declarations, "elementFormDefault=\"qualified\"")

        private fun payload(name: String, child: String): String = """
            <xs:element name="$name"><xs:complexType><xs:sequence>
                <xs:element name="$child" type="xs:string"/>
            </xs:sequence></xs:complexType></xs:element>
        """.trimIndent()
        private fun wsdl(schema: String): String = namespaceFixture.wsdl(schema)
    }
}
