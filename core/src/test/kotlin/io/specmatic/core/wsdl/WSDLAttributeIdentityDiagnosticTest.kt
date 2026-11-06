package io.specmatic.core.wsdl

import io.specmatic.core.Result
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WSDLAttributeIdentityDiagnosticTest {
    @TempDir lateinit var directory: File

    @Test
    fun `wrong namespace reports missing and unexpected attributes without reusing matched ids`() {
        val result = validate("""id="local" o:id="order" t:id="tracking"""", idsNamespace = "urn:wrong")
        assertThat(result.isSuccess()).isFalse()
        assertThat(result.reportString()).contains(
            "Required attribute 'id' in XML namespace 'urn:ids' is missing.",
            "Unexpected attribute 't:id' in XML namespace 'urn:wrong'."
        ).doesNotContain("got id", "Expected XML attribute")
        assertThat(result.toIssues().flatMap { it.ruleViolations }.map { it.id }).containsExactly("R2001", "R2003")
        assertThat(breadcrumbs(result)).containsExactly(
            ">> REQUEST.BODY.Envelope.Body.Request",
            ">> REQUEST.BODY.Envelope.Body.Request.t:id"
        )
    }

    @Test
    fun `missing namespaced id reports only the missing declaration`() {
        val result = validate("""id="local" o:id="order"""")
        assertThat(result.reportString()).contains("Required attribute 'id' in XML namespace 'urn:ids' is missing.")
            .doesNotContain("Unexpected attribute", "got id")
        assertThat(breadcrumbs(result)).containsExactly(">> REQUEST.BODY.Envelope.Body.Request")
    }

    @Test
    fun `optional namespaced id may be absent`() {
        assertThat(validate("""id="local" o:id="order"""", idsUse = "optional").isSuccess()).isTrue()
    }

    @Test
    fun `optional id in the wrong namespace is unexpected without a missing error`() {
        val result = validate("""id="local" o:id="order" t:id="tracking"""", idsNamespace = "urn:wrong", idsUse = "optional")
        assertThat(result.reportString()).contains("Unexpected attribute 't:id' in XML namespace 'urn:wrong'.")
            .doesNotContain("Required attribute")
        assertThat(breadcrumbs(result)).containsExactly(">> REQUEST.BODY.Envelope.Body.Request.t:id")
    }

    @Test
    fun `wildcard allowed attributes do not satisfy a missing required id`() {
        val result = validate("""id="local" o:id="order" t:id="tracking"""", idsNamespace = "urn:wrong", wildcard = OTHER_ATTRIBUTES)
        assertThat(result.reportString()).contains("Required attribute 'id' in XML namespace 'urn:ids' is missing.")
            .doesNotContain("Unexpected attribute")
        assertThat(breadcrumbs(result)).containsExactly(">> REQUEST.BODY.Envelope.Body.Request")
    }

    @Test
    fun `wildcard accepts another same local name when all required identities are present`() {
        val result = validate("""xmlns:w="urn:extra" id="local" o:id="order" t:id="tracking" w:id="extra"""", wildcard = OTHER_ATTRIBUTES)
        assertThat(result.isSuccess()).describedAs(result.reportString()).isTrue()
    }

    @Test
    fun `wildcard namespace restrictions still reject an extra attribute`() {
        val result = validate("""id="local" o:id="order" t:id="tracking" o:extra="extra"""", wildcard = OTHER_ATTRIBUTES)
        assertThat(result.reportString()).contains("Unexpected attribute 'o:extra' in XML namespace 'urn:orders'.")
        assertThat(breadcrumbs(result)).containsExactly(">> REQUEST.BODY.Envelope.Body.Request.o:extra")
    }

    @Test
    fun `all missing namespace identities are reported`() {
        val result = validate("""id="local"""")
        assertThat(result.reportString()).contains(
            "Required attribute 'id' in XML namespace 'urn:orders' is missing.",
            "Required attribute 'id' in XML namespace 'urn:ids' is missing."
        ).doesNotContain("Unexpected attribute")
    }

    @Test
    fun `missing unqualified attribute uses plain namespace wording`() {
        val result = validate("""o:id="order" t:id="tracking"""")
        assertThat(result.reportString()).contains("Required attribute 'id' with no XML namespace is missing.")
            .doesNotContain("(none)", "Unexpected attribute")
    }

    @Test
    fun `unexpected unqualified attribute uses plain namespace wording`() {
        val result = validate("""id="local" o:id="order" t:id="tracking" extra="extra"""")
        assertThat(result.reportString()).contains("Unexpected attribute 'extra' with no XML namespace.")
        assertThat(breadcrumbs(result)).containsExactly(">> REQUEST.BODY.Envelope.Body.Request.extra")
    }

    @Test
    fun `matched attribute values are still validated`() {
        val result = validate("""id="local" o:id="order" t:id="invalid"""", idsType = "xs:int")
        assertThat(result.isSuccess()).isFalse()
        assertThat(result.reportString()).doesNotContain("Required attribute", "Unexpected attribute")
        assertThat(breadcrumbs(result)).containsExactly(">> REQUEST.BODY.Envelope.Body.Request.t:id")
    }

    private fun breadcrumbs(result: Result): List<String> = result.reportString().lineSequence()
        .map(String::trim).filter { it.startsWith(">>") }.toList()

    private fun validate(attributes: String, idsNamespace: String = "urn:ids", idsUse: String = "required", wildcard: String = "", idsType: String = "xs:string"): Result {
        directory.resolve("ids.xsd").writeText("""<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:ids">
            <xs:attribute name="id" type="$idsType"/></xs:schema>""")
        val fixture = WSDLNamespaceFixture("urn:orders", "/attribute-identities")
        val declarations = """<xs:import namespace="urn:ids" schemaLocation="ids.xsd"/>
            <xs:attribute name="id" type="xs:string"/>
            <xs:element name="Request"><xs:complexType><xs:sequence><xs:element name="code" type="xs:string"/></xs:sequence>
            <xs:attribute name="id" type="xs:string" use="required"/>
            <xs:attribute ref="tns:id" use="required"/><xs:attribute ref="ids:id" use="$idsUse"/>$wildcard
            </xs:complexType></xs:element><xs:element name="Response" type="xs:string"/>"""
        val schema = fixture.schema(declarations).replace("<xs:schema ", "<xs:schema xmlns:ids=\"urn:ids\" ")
        val scenario = parseContractFileToFeature(directory.resolve("attributes.wsdl").apply {
            writeText(fixture.wsdl(schema))
        }).scenarios.single()
        val document = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
            <s:Body><o:Request xmlns:o="urn:orders" xmlns:t="$idsNamespace" $attributes><code>AB</code></o:Request></s:Body>
            </s:Envelope>"""
        return scenario.httpRequestPattern.matches(scenario.generateHttpRequest().copy(body = StringValue(document)), scenario.resolver)
    }
    companion object {
        private const val OTHER_ATTRIBUTES = """<xs:anyAttribute namespace="##other" processContents="skip"/>"""
    }
}
