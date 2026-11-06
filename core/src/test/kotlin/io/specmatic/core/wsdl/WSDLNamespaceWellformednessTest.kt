package io.specmatic.core.wsdl

import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WSDLNamespaceWellformednessTest {
    @TempDir lateinit var directory: File

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidNamespaceDocuments")
    fun `WSDL rejects namespace-malformed documents`(description: String, declarations: String, body: String) {
        val result = validate(envelope(declarations, body))
        assertThat(result.isSuccess()).describedAs(description).isFalse()
        assertThat(result.reportString()).contains("namespace")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validNamespaceDocuments")
    fun `WSDL accepts namespace-wellformed documents`(description: String, declarations: String, body: String) {
        val result = validate(envelope(declarations, body))
        assertThat(result.isSuccess()).describedAs("$description: ${result.reportString()}").isTrue()
    }

    @Test
    fun `large skipped payloads retain namespace validation`() {
        val children = "<alias:Child alias:id=\"one\"/>".repeat(10_000)
        val validBody = "<o:Request xmlns:alias=\"urn:child\">$children</o:Request>"
        assertThat(validate(envelope("", validBody)).isSuccess()).isTrue()
        val invalidBody = "<o:Request xmlns:alias=\"urn:child\">$children<missing:Child/></o:Request>"
        assertThat(validate(envelope("", invalidBody)).reportString()).contains("namespace")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("namespaceFailureBreadcrumbs")
    fun `namespace failures report the actual XML ancestry`(description: String, declarations: String, body: String, breadcrumb: String) {
        val result = validate(envelope(declarations, body))
        val breadcrumbs = result.reportString().lineSequence()
            .map(String::trim).filter { it.startsWith(">>") }.toList()

        assertThat(breadcrumbs).describedAs(description).containsExactly(">> $breadcrumb")
    }

    private fun validate(document: String): io.specmatic.core.Result {
        val declarations = """<xs:element name="Request"><xs:complexType><xs:sequence>
            <xs:any minOccurs="0" maxOccurs="unbounded" processContents="skip"/>
            </xs:sequence><xs:anyAttribute processContents="skip"/></xs:complexType></xs:element>
            <xs:element name="Response" type="xs:string"/>"""
        val file = directory.resolve("namespace.wsdl").apply { writeText(wsdl(schema(declarations))) }
        val scenario = parseContractFileToFeature(file).scenarios.single()
        return scenario.httpRequestPattern.matches(scenario.generateHttpRequest().copy(body = StringValue(document)), scenario.resolver)
    }

    private fun envelope(declarations: String, body: String): String = """
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" xmlns:o="$NAMESPACE" $declarations>
            <s:Body>$body</s:Body></s:Envelope>
    """.trimIndent()

    companion object {
        private const val NAMESPACE = "http://example.com/orders"
        @JvmStatic
        fun namespaceFailureBreadcrumbs(): List<Arguments> = listOf(
            Arguments.of("unbound payload prefix", "", "<missing:Request/>", "REQUEST.BODY.Envelope.Body.Request"),
            Arguments.of("nested skipped descendant", "", "<o:Request><Outer><missing:Inner/></Outer></o:Request>", "REQUEST.BODY.Envelope.Body.Request.Outer.Inner"),
            Arguments.of("completed siblings are excluded", "", "<o:Request><First><Valid/></First><Second><missing:Inner/></Second></o:Request>", "REQUEST.BODY.Envelope.Body.Request.Second.Inner"),
            Arguments.of("unbound attribute prefix", "", """<o:Request missing:id="one"/>""", "REQUEST.BODY.Envelope.Body.Request"),
            Arguments.of("invalid nested binding", "", """<o:Request><Outer xmlns:bad=""/></o:Request>""", "REQUEST.BODY.Envelope.Body.Request.Outer"),
            Arguments.of("duplicate expanded attributes", "", """<o:Request><Outer xmlns:a="urn:ids" xmlns:b="urn:ids" a:id="one" b:id="two"/></o:Request>""", "REQUEST.BODY.Envelope.Body.Request.Outer"),
            Arguments.of("envelope is included once", """xmlns:xml="urn:wrong"""", "<o:Request/>", "REQUEST.BODY.Envelope")
        )

        @JvmStatic
        fun invalidNamespaceDocuments(): List<Arguments> = listOf(
            Arguments.of("empty declared prefix", "", """<o:Request><Child xmlns:="urn:invalid"/></o:Request>"""),
            Arguments.of("wrong reserved xml binding", "xmlns:xml=\"urn:wrong\"", "<o:Request/>"),
            Arguments.of("reserved xmlns prefix", "xmlns:xmlns=\"urn:wrong\"", "<o:Request/>"),
            Arguments.of("reserved xmlns namespace", "xmlns:alias=\"http://www.w3.org/2000/xmlns/\"", "<o:Request/>"),
            Arguments.of("reserved xml namespace under another prefix", "xmlns:alias=\"http://www.w3.org/XML/1998/namespace\"", "<o:Request/>"),
            Arguments.of("empty prefixed binding", "xmlns:alias=\"\"", "<o:Request/>"),
            Arguments.of("unbound attribute prefix", "", """<o:Request missing:id="one"/>"""),
            Arguments.of("unbound prefix under skipped wildcard", "", "<o:Request><missing:Child/></o:Request>"),
            Arguments.of("multiple colons in element name", "", "<o:Request><o:bad:name/></o:Request>"),
            Arguments.of("multiple colons in attribute name", "", """<o:Request o:bad:id="one"/>"""),
            Arguments.of("reserved xmlns element prefix", "", "<o:Request><xmlns:Child/></o:Request>"),
            Arguments.of("duplicate expanded attributes after child rebinding", "xmlns:a=\"urn:a\" xmlns:b=\"urn:b\"", """<o:Request><Child xmlns:b="urn:a" a:id="one" b:id="two"/></o:Request>"""),
            Arguments.of("duplicate expanded attributes", "xmlns:a=\"urn:ids\" xmlns:b=\"urn:ids\"", """<o:Request a:id="one" b:id="two"/>""")
        )

        @JvmStatic
        fun validNamespaceDocuments(): List<Arguments> = listOf(
            Arguments.of("escaped XML content", "", """<o:Request data="a&amp;b"><o:Anything>a&lt;b&amp;c</o:Anything></o:Request>"""),
            Arguments.of("unused legal declaration", "xmlns:unused=\"urn:unused\"", "<o:Request/>"),
            Arguments.of("inherited prefix on skipped child", "xmlns:alias=\"urn:other\"", "<o:Request><alias:Child/></o:Request>"),
            Arguments.of("default namespace reset on skipped child", "", """<o:Request xmlns="urn:other"><Local xmlns=""/></o:Request>"""),
            Arguments.of("implicit xml prefix", "", """<o:Request xml:lang="en"/>"""),
            Arguments.of("explicit legal xml prefix", "xmlns:xml=\"http://www.w3.org/XML/1998/namespace\"", """<o:Request xml:lang="en"/>"""),
            Arguments.of("child rebinding does not leak to siblings", "xmlns:a=\"urn:a\" xmlns:b=\"urn:b\"", """<o:Request><Child xmlns:b="urn:a"/><Sibling a:id="one" b:id="two"/></o:Request>"""),
            Arguments.of("default namespace does not qualify attributes", "", """<o:Request xmlns="urn:ids" xmlns:a="urn:ids" id="one" a:id="two"/>"""),
            Arguments.of("Unicode namespace names", "xmlns:α=\"urn:unicode\"", """<o:Request><α:Δ α:属性="one"/></o:Request>"""),
            Arguments.of("same local attribute names in distinct namespaces", "xmlns:a=\"urn:a\" xmlns:b=\"urn:b\"", """<o:Request a:id="one" b:id="two"/>""")
        )

        private val namespaceFixture = WSDLNamespaceFixture(NAMESPACE, "/namespaces")

        private fun schema(declarations: String): String =
            namespaceFixture.schema(declarations, "elementFormDefault=\"qualified\"")

        private fun wsdl(schema: String): String = namespaceFixture.wsdl(schema)
    }
}
