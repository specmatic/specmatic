package io.specmatic.core.wsdl

import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.HttpRequest
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.params.provider.CsvSource
import org.w3c.dom.Element
import java.io.File
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

class WSDLNamespaceQualificationTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `global elements retain their namespace under unqualified local defaults`() {
        val schema = schema("""
            <xs:element name="Request"><xs:complexType><xs:sequence>
                <xs:element name="local" type="xs:string"/>
            </xs:sequence></xs:complexType></xs:element>
            <xs:element name="Response" type="xs:string"/>
        """)
        val payload = generatedPayload(schema)
        assertThat(payload.namespaceURI).isEqualTo(NAMESPACE)
        assertThat(payload.getElementsByTagNameNS("", "local").length).isEqualTo(1)
    }

    @Test
    fun `namespace URI escaping preserves identity when generating SOAP messages`() {
        val namespace = "http://example.com/orders?a=1&b=2"
        val escapedNamespace = "http://example.com/orders?a=1&amp;b=2"
        val schema = schema("""<xs:element name="Request" type="xs:string"/>
            <xs:element name="Response" type="xs:string"/>""").replace(NAMESPACE, escapedNamespace)
        val definition = wsdl(schema).replace(NAMESPACE, escapedNamespace)
        val scenario = parseContractFileToFeature(directory.resolve("escaped-namespace.wsdl").apply { writeText(definition) }).scenarios.single()
        for (body in listOf(scenario.generateHttpRequest().body, scenario.generateHttpResponse().body)) {
            val payload = soapPayloadElement(body.toStringLiteral())
            SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                .newSchema(StreamSource(StringReader(schema))).newValidator().validate(DOMSource(payload))
            assertThat(payload.namespaceURI).isEqualTo(namespace)
            assertThat(body.toStringLiteral()).contains(escapedNamespace)
        }
    }

    @Test
    fun `qualified attributes are generated in their schema namespace`() {
        val schema = schema("""
            <xs:element name="Request"><xs:complexType>
                <xs:attribute name="id" type="xs:string" use="required"/>
            </xs:complexType></xs:element>
            <xs:element name="Response" type="xs:string"/>
        """, "attributeFormDefault=\"qualified\"")
        val payload = generatedPayload(schema)
        assertThat(payload.getAttributeNodeNS(NAMESPACE, "id")).isNotNull()
        assertThat(payload.getAttributeNodeNS(null, "id")).isNull()
    }

    @ParameterizedTest
    @CsvSource("qualified, unqualified", "unqualified, qualified")
    fun `local attribute form overrides its source schema default`(defaultForm: String, form: String) {
        val schema = schema("""
            <xs:element name="Request"><xs:complexType>
                <xs:attribute name="id" type="xs:string" use="required" form="$form"/>
            </xs:complexType></xs:element><xs:element name="Response" type="xs:string"/>
        """, "attributeFormDefault=\"$defaultForm\"")
        val payload = generatedPayload(schema)
        val expectedNamespace = if (form == "qualified") NAMESPACE else null
        assertThat(payload.getAttributeNodeNS(expectedNamespace, "id")).isNotNull()
    }

    @Test
    fun `qualified and unqualified attributes sharing a local name remain distinct`() {
        val schema = schema("""
            <xs:attribute name="id" type="xs:string"/>
            <xs:element name="Request"><xs:complexType>
                <xs:attribute name="id" type="xs:string" use="required"/>
                <xs:attribute ref="tns:id" use="required"/>
            </xs:complexType></xs:element>
            <xs:element name="Response" type="xs:string"/>
        """)
        val payload = generatedPayload(schema)
        assertThat(payload.getAttributeNodeNS(NAMESPACE, "id")).isNotNull()
        assertThat(payload.getAttributeNodeNS(null, "id")).isNotNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["http://example.com/types", "urn:types", "https://example.com/namespaces"])
    fun `qualified local element uses declaration namespace rather than imported type namespace`(typeNamespace: String) {
        directory.resolve("types.xsd").writeText("""
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="$typeNamespace">
                <xs:complexType name="Data"><xs:sequence>
                    <xs:element name="value" type="xs:string" form="qualified"/>
                </xs:sequence></xs:complexType>
            </xs:schema>
        """.trimIndent())
        val schema = schema("""
            <xs:import namespace="$typeNamespace" schemaLocation="types.xsd"/>
            <xs:element name="Request"><xs:complexType><xs:sequence>
                <xs:element xmlns:types="$typeNamespace" name="item" type="types:Data" form="qualified"/>
            </xs:sequence></xs:complexType></xs:element>
            <xs:element name="Response" type="xs:string"/>
        """)
        val payload = generatedPayload(schema)
        assertThat(payload.getElementsByTagNameNS(NAMESPACE, "item").length).isEqualTo(1)
        assertThat(payload.getElementsByTagNameNS(typeNamespace, "item").length).isZero()
        assertThat(payload.getElementsByTagNameNS(typeNamespace, "value").length).isEqualTo(1)
    }

    @ParameterizedTest
    @CsvSource("qualified,unqualified", "unqualified,qualified")
    fun `local form overrides the schema element qualification default`(defaultForm: String, localForm: String) {
        val schema = schema("""
            <xs:element name="Request"><xs:complexType><xs:sequence>
                <xs:element name="local" type="xs:string" form="$localForm"/>
            </xs:sequence></xs:complexType></xs:element>
            <xs:element name="Response" type="xs:string"/>
        """, "elementFormDefault=\"$defaultForm\"")
        val payload = generatedPayload(schema)
        val child = payload.getElementsByTagNameNS("*", "local").item(0) as Element
        assertThat(child.namespaceURI.orEmpty()).isEqualTo(if (localForm == "qualified") NAMESPACE else "")
    }

    @Test
    fun `generated schema prefixes avoid conflicting bindings in WSDL definitions`() {
        directory.resolve("types.xsd").writeText("""
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="http://example.com/types"
                elementFormDefault="qualified">
                <xs:complexType name="Data"><xs:sequence>
                    <xs:element name="value" type="xs:string"/>
                </xs:sequence></xs:complexType>
            </xs:schema>
        """.trimIndent())
        val schema = schema("""
            <xs:import namespace="http://example.com/types" schemaLocation="types.xsd"/>
            <xs:element name="Request"><xs:complexType><xs:sequence>
                <xs:element xmlns:types="http://example.com/types" name="item" type="types:Data"/>
            </xs:sequence></xs:complexType></xs:element>
            <xs:element name="Response" type="xs:string"/>
        """)
        val payload = generatedPayload(schema, "xmlns:Types=\"urn:already-bound\"")
        assertThat(payload.getElementsByTagNameNS("http://example.com/types", "value").length).isEqualTo(1)
    }

    @Test
    fun `default namespace bindings resolve WSDL references and generate schema valid messages`() {
        val schema = schema("""
            <xs:element name="Request"><xs:complexType><xs:sequence>
                <xs:element name="local" type="xs:string"/>
            </xs:sequence></xs:complexType></xs:element>
            <xs:element name="Response" type="xs:string"/>
        """)
        val definition = wsdl(schema)
            .replace("xmlns:tns=\"$NAMESPACE\"", "xmlns=\"$NAMESPACE\"")
            .replace("tns:", "")
        val file = directory.resolve("default-namespace.wsdl").apply { writeText(definition) }
        val scenario = parseContractFileToFeature(file).scenarios.single()
        for (body in listOf(scenario.generateHttpRequest().body, scenario.generateHttpResponse().body)) {
            assertThat(validatedPayload(body.toStringLiteral(), schema).namespaceURI).isEqualTo(NAMESPACE)
        }
    }

    @Test
    fun `substitution member in another namespace matches the declared head contract`() {
        directory.resolve("head.xsd").writeText(schema("""
            <xs:element name="Request"><xs:complexType><xs:sequence>
                <xs:element ref="tns:Head"/>
            </xs:sequence></xs:complexType></xs:element>
            <xs:element name="Head" type="tns:Base" abstract="true"/>
            <xs:element name="Response" type="xs:string"/>
            <xs:complexType name="Base"><xs:sequence>
                <xs:element name="base" type="xs:string"/>
            </xs:sequence></xs:complexType>
        """, "elementFormDefault=\"qualified\""))
        directory.resolve("member.xsd").writeText("""
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="$NAMESPACE"
                xmlns:member="http://example.com/substitutes" targetNamespace="http://example.com/substitutes"
                elementFormDefault="qualified">
                <xs:import namespace="$NAMESPACE" schemaLocation="head.xsd"/>
                <xs:complexType name="Derived"><xs:complexContent><xs:extension base="tns:Base">
                    <xs:sequence><xs:element name="detail" type="xs:string"/></xs:sequence>
                </xs:extension></xs:complexContent></xs:complexType>
                <xs:element name="Member" type="member:Derived" substitutionGroup="tns:Head"/>
            </xs:schema>
        """.trimIndent())
        val imports = """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="http://example.com/holder">
                <xs:import namespace="$NAMESPACE" schemaLocation="head.xsd"/>
                <xs:import namespace="http://example.com/substitutes" schemaLocation="member.xsd"/>
            </xs:schema>
        """.trimIndent()
        val file = directory.resolve("substitution.wsdl").apply { writeText(wsdl(imports)) }
        val scenario = parseContractFileToFeature(file).scenarios.single()
        val body = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" xmlns:tns="$NAMESPACE"
                xmlns:member="http://example.com/substitutes"><s:Body>
                <tns:Request><member:Member><tns:base>base</tns:base><member:detail>detail</member:detail></member:Member></tns:Request>
            </s:Body></s:Envelope>
        """.trimIndent()
        validatedPayload(body, imports)
        val result = scenario.httpRequestPattern.matches(
            HttpRequest(method = "POST", path = "/namespace",
                headers = mapOf("Content-Type" to "text/xml", "SOAPAction" to "\"/namespace/submit\""),
                body = StringValue(body)),
            scenario.resolver,
        )
        assertThat(result.isSuccess()).withFailMessage(result.reportString()).isTrue()
    }

    private fun generatedPayload(schema: String, rootNamespaces: String = ""): Element {
        val file = directory.resolve("namespace.wsdl").apply {
            writeText(wsdl(schema).replace("<wsdl:definitions ", "<wsdl:definitions $rootNamespaces "))
        }
        val request = parseContractFileToFeature(file).scenarios.single().generateHttpRequest()
        return validatedPayload(request.body.toStringLiteral(), schema)
    }

    private fun validatedPayload(bodyText: String, schema: String): Element {
        val payload = soapPayloadElement(bodyText)
        SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
            .newSchema(StreamSource(StringReader(schema)).apply {
                systemId = directory.resolve("validation.xsd").toURI().toString()
            }).newValidator().validate(DOMSource(payload))
        return payload
    }

    private val namespaceFixture = WSDLNamespaceFixture(NAMESPACE, "/namespace")

    private fun schema(declarations: String, defaults: String = ""): String =
        namespaceFixture.schema(declarations, defaults)

    private fun wsdl(schema: String): String = namespaceFixture.wsdl(schema)

    companion object {
        private const val NAMESPACE = "http://example.com/namespaces"
    }
}
