package io.specmatic.core.wsdl

import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WSDLTypeRuleDiagnosticTest {
    @TempDir lateinit var directory: File

    @ParameterizedTest(name = "{0}")
    @MethodSource("typeCases")
    fun `xsi type failures reuse namespace type and missing property rules`(description: String, type: String?, expectedId: String?) {
        val fixture = WSDLNamespaceFixture("urn:orders", "/types")
        val declarations = """<xs:complexType name="Base" abstract="true"><xs:sequence>
            <xs:element name="code" type="xs:string"/></xs:sequence></xs:complexType>
            <xs:complexType name="Derived"><xs:complexContent><xs:extension base="tns:Base"><xs:sequence>
            <xs:element name="extra" type="xs:string"/></xs:sequence></xs:extension></xs:complexContent></xs:complexType>
            <xs:complexType name="Other"><xs:sequence><xs:element name="other" type="xs:string"/></xs:sequence></xs:complexType>
            <xs:element name="Request" type="tns:Base"/><xs:element name="Response" type="xs:string"/>"""
        val scenario = parseContractFileToFeature(directory.resolve("types.wsdl").apply {
            writeText(fixture.wsdl(fixture.schema(declarations, "elementFormDefault=\"qualified\"")))
        }).scenarios.single()
        val typeAttribute = type?.let { "xsi:type=\"$it\"" }.orEmpty()
        val document = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" xmlns:o="urn:orders"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><s:Body>
            <o:Request $typeAttribute><o:code>value</o:code><o:extra>extra</o:extra></o:Request></s:Body></s:Envelope>"""
        val result = scenario.httpRequestPattern.matches(scenario.generateHttpRequest().copy(body = StringValue(document)), scenario.resolver)
        assertThat(result.isSuccess()).describedAs("$description: ${result.reportString()}").isEqualTo(expectedId == null)
        val ids = result.toIssues().flatMap { it.ruleViolations }.map { it.id }
        if (expectedId == null) assertThat(ids).isEmpty() else assertThat(ids).containsExactly(expectedId)
    }

    companion object {
        @JvmStatic
        fun typeCases(): List<Arguments> = listOf(
            Arguments.of("unbound type prefix", "missing:Derived", "R1005"),
            Arguments.of("unknown type", "o:Missing", "R1001"),
            Arguments.of("incompatible type", "o:Other", "R1001"),
            Arguments.of("abstract type", "o:Base", "R1001"),
            Arguments.of("missing concrete type", null, "R2001"),
            Arguments.of("valid concrete type", "o:Derived", null)
        )
    }
}
