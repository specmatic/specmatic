package io.specmatic.core.wsdl

import io.specmatic.core.Result
import io.specmatic.core.pattern.XMLPattern
import org.junit.jupiter.api.Test
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WSDLNamespaceDiagnosticTest {
    @TempDir lateinit var directory: File

    @ParameterizedTest(name = "{2}")
    @MethodSource("namespaceMessages")
    fun `namespace errors describe missing namespaces in plain language`(expected: String, actual: String, message: String) {
        val result = validate("""<o:Request a:id="42"><code xmlns="$actual">value</code></o:Request>""", qualifiedCode = expected.isNotEmpty())
        assertThat(result.isSuccess()).isFalse()
        assertThat(result.reportString()).contains(message).doesNotContain("(none)")
        assertThat(result.toIssues().flatMap { it.ruleViolations }.map { it.id }).containsExactly("R1004")
        assertThat(result.reportString()).contains("https://docs.specmatic.io/rules#r1004")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("attributeBreadcrumbs")
    fun `attribute breadcrumbs use payload names or stop at the containing element`(description: String, attributes: String, expectedBreadcrumbs: List<String>) {
        val result = validate("<o:Request $attributes><code>value</code></o:Request>")
        assertThat(result.isSuccess()).isFalse()
        val breadcrumbs = result.reportString().lineSequence().map(String::trim).filter { it.startsWith(">>") }.toList()
        assertThat(breadcrumbs).describedAs(description).containsExactlyElementsOf(expectedBreadcrumbs.map { ">> $it" })
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("compatibilityMessages")
    fun `compatibility namespace failures use the same rule and plain wording`(expectedNamespace: String, actualNamespace: String, message: String) {
        fun scenario(name: String, namespace: String) = parseContractFileToFeature(directory.resolve(name).apply {
            val fixture = WSDLNamespaceFixture(namespace, "/namespaces")
            writeText(fixture.wsdl(fixture.schema("""<xs:element name="Request" type="xs:string"/>
                <xs:element name="Response" type="xs:string"/>""")))
        }).scenarios.single()
        val expected = scenario("expected.wsdl", expectedNamespace)
        val actual = scenario("actual.wsdl", actualNamespace)
        val result = expected.httpResponsePattern.encompasses(actual.httpResponsePattern, expected.resolver, actual.resolver)
        assertThat(result.isSuccess()).isFalse()
        assertThat(result.reportString()).contains(message).doesNotContain("(none)")
        assertThat(result.toIssues().flatMap { it.ruleViolations }.map { it.id }).containsExactly("R1004")
    }

    @Test
    fun `compatibility distinguishes an unspecified namespace requirement from no namespace`() {
        val fixture = WSDLNamespaceFixture("urn:orders", "/namespaces")
        val scenario = parseContractFileToFeature(directory.resolve("legacy.wsdl").apply {
            writeText(fixture.wsdl(fixture.schema("""<xs:element name="Request" type="xs:string"/>
                <xs:element name="Response" type="xs:string"/>""")))
        }).scenarios.single()
        val unspecified = XMLPattern(scenario.generateHttpResponse().body.toStringLiteral())
        val result = scenario.httpResponsePattern.body.encompasses(unspecified, scenario.resolver, scenario.resolver)
        assertThat(result.isSuccess()).isFalse()
        assertThat(result.reportString()).contains("but the other pattern has no namespace requirement.")
        assertThat(result.toIssues().flatMap { it.ruleViolations }.map { it.id }).containsExactly("R1004")
    }

    private fun validate(payload: String, qualifiedCode: Boolean = false): Result {
        val fixture = WSDLNamespaceFixture("urn:orders", "/namespaces")
        val form = if (qualifiedCode) "qualified" else "unqualified"
        val declarations = """<xs:element name="Request"><xs:complexType><xs:sequence>
            <xs:element name="code" type="xs:string" form="$form"/></xs:sequence>
            <xs:attribute name="id" type="xs:int" use="required" form="qualified"/>
            </xs:complexType></xs:element><xs:element name="Response" type="xs:string"/>"""
        val scenario = parseContractFileToFeature(directory.resolve("diagnostics.wsdl").apply {
            writeText(fixture.wsdl(fixture.schema(declarations)))
        }).scenarios.single()
        val document = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" xmlns:o="urn:orders"
            xmlns:a="urn:orders" xmlns:t="urn:wrong"><s:Body>$payload</s:Body></s:Envelope>"""
        return scenario.httpRequestPattern.matches(scenario.generateHttpRequest().copy(body = StringValue(document)), scenario.resolver)
    }

    companion object {
        @JvmStatic
        fun attributeBreadcrumbs(): List<Arguments> = listOf(
            Arguments.of("invalid attribute value", """a:id="invalid"""", listOf("REQUEST.BODY.Envelope.Body.Request.a:id")),
            Arguments.of("wrong attribute namespace", """t:id="42"""", listOf("REQUEST.BODY.Envelope.Body.Request", "REQUEST.BODY.Envelope.Body.Request.t:id")),
            Arguments.of("unexpected attribute", """a:id="42" t:extra="yes"""", listOf("REQUEST.BODY.Envelope.Body.Request.t:extra")),
            Arguments.of("missing attribute", "", listOf("REQUEST.BODY.Envelope.Body.Request"))
        )

        @JvmStatic
        fun compatibilityMessages(): List<Arguments> = listOf(
            Arguments.of("urn:orders", "urn:changed", "Expected XML namespace 'urn:orders' for element 'Response', got 'urn:changed'."),
            Arguments.of("urn:changed", "urn:orders", "Expected XML namespace 'urn:changed' for element 'Response', got 'urn:orders'.")
        )

        @JvmStatic
        fun namespaceMessages(): List<Arguments> = listOf(
            Arguments.of("", "urn:orders", "Expected no XML namespace for element 'code', got 'urn:orders'."),
            Arguments.of("urn:orders", "", "Expected XML namespace 'urn:orders' for element 'code', got no namespace."),
            Arguments.of("urn:orders", "urn:wrong", "Expected XML namespace 'urn:orders' for element 'code', got 'urn:wrong'.")
        )
    }
}
