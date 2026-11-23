package io.specmatic.core.wsdl

import io.specmatic.core.*
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

class WSDLAllDiagnosticTest {
    @TempDir lateinit var directory: File

    @Test
    fun `all preserves a known optional member constraint and type failure in requests and responses`() {
        for ((scalar, invalid, rule, message) in listOf(
            listOf(lengthType, "LONG", "R1003", "maxLength 3"),
            listOf("type=\"xs:int\"/>", "invalid", "R1001", "Expected number"))) {
            val scenario = scenario(scalar = scalar)
            for ((direction, result) in results(scenario, "<Entry><Code>$invalid</Code></Entry><Tail>ok</Tail>")) {
                assertFailure(result, "$direction.BODY.Envelope.Body.${root(direction)}.Entry.Code", rule, message)
            }
        }
    }

    @Test
    fun `namespace identity qualifies equivalent prefixes and nested failures but not wrong outer names`() {
        val scenario = scenario()
        for ((direction, result) in results(scenario, "<q:Entry xmlns:q=\"$namespace\"><q:Code>LONG</q:Code></q:Entry><Tail>ok</Tail>"))
            assertFailure(result, "$direction.BODY.Envelope.Body.${root(direction)}.Entry.Code", "R1003", "maxLength 3")
        for ((direction, result) in results(scenario, "<Entry><Code xmlns=\"urn:wrong\">ok</Code></Entry><Tail>ok</Tail>"))
            assertFailure(result, "$direction.BODY.Envelope.Body.${root(direction)}.Entry.Code", "R1004", "urn:wrong")
        for (children in listOf(
            "<Other><Code>ok</Code></Other><Tail>ok</Tail>",
            "<Entry xmlns=\"urn:wrong\"><Code>ok</Code></Entry><Tail>ok</Tail>")) {
            for ((direction, result) in results(scenario, children))
                assertFailure(result, "$direction.BODY.Envelope.Body.${root(direction)}.Tail", "R1002", "Expected Tail")
        }
    }

    @Test
    fun `valid omission reordering alternatives and required failures retain acceptance against XSD`() {
        for (group in listOf("all", "sequence", "choice")) for (optional in listOf(true, false)) {
            val scenario = scenario(group = group, optional = optional)
            val cases = mutableListOf(
                "<Entry><Code>ok</Code></Entry><Tail>ok</Tail>" to true,
                "<Tail>ok</Tail>" to optional,
                "<Entry><Code>LONG</Code></Entry><Tail>ok</Tail>" to false,
                "<Other/><Tail>ok</Tail>" to false,
                "<Entry><Code>ok</Code></Entry>" to false)
            if (group == "all") cases += "<Tail>ok</Tail><Entry><Code>ok</Code></Entry>" to true
            if (group == "choice") cases += "<Alternative>ok</Alternative><Tail>ok</Tail>" to true
            for ((children, valid) in cases) for ((direction, result) in results(scenario, children)) {
                assertThat(result.isSuccess()).describedAs("$group optional=$optional $children ${result.reportString()}").isEqualTo(valid)
                assertThat(schemaAccepts(group, optional, root(direction), children)).isEqualTo(valid)
            }
        }
    }

    @Test
    fun `sequence and either choice branch order retain useful nested errors`() {
        for (group in listOf("sequence", "choice")) for (optional in listOf(true, false)) for (reverse in listOf(true, false)) {
            val scenario = scenario(group = group, optional = optional, reverse = reverse)
            for ((direction, result) in results(scenario, "<Entry><Code>LONG</Code></Entry><Tail>ok</Tail>"))
                assertFailure(result, "$direction.BODY.Envelope.Body.${root(direction)}.Entry.Code", "R1003", "maxLength 3")
        }
    }

    private fun scenario(scalar: String = lengthType, group: String = "all", optional: Boolean = true, reverse: Boolean = false): Scenario {
        val fixture = WSDLNamespaceFixture(namespace, "/diagnostics")
        val declarations = declaration("Request", group, optional, scalar, reverse) + declaration("Response", group, optional, scalar, reverse)
        return parseContractFileToFeature(directory.resolve("diagnostics.wsdl").apply {
            writeText(fixture.wsdl(fixture.schema(declarations, "elementFormDefault=\"qualified\"")))
        }).scenarios.single()
    }

    private fun declaration(name: String, group: String, optional: Boolean, scalar: String = lengthType, reverse: Boolean = false): String {
        val entry = """<xs:element name="Entry" minOccurs="${if (optional) 0 else 1}"><xs:complexType><xs:sequence>
            <xs:element name="Code" $scalar</xs:sequence></xs:complexType></xs:element>"""
        val tail = """<xs:element name="Tail" type="xs:string"/>"""
        val alternative = """<xs:element name="Alternative" type="xs:string"/>"""
        val content = if (group == "choice") "<xs:sequence><xs:choice>${if (reverse) alternative + entry else entry + alternative}</xs:choice>$tail</xs:sequence>"
            else "<xs:$group>$entry$tail</xs:$group>"
        return """<xs:element name="$name"><xs:complexType>$content</xs:complexType></xs:element>"""
    }

    private fun results(scenario: Scenario, children: String): List<Pair<String, Result>> {
        fun body(name: String) = StringValue("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><$name xmlns="$namespace">$children</$name></s:Body></s:Envelope>""")
        val request = scenario.generateHttpRequest().copy(body = body("Request"))
        val response = HttpResponse(status = 200, headers = mapOf("Content-Type" to "text/xml"), body = body("Response"))
        return listOf("REQUEST" to scenario.matches(request, scenario.resolver),
            "RESPONSE" to scenario.httpResponsePattern.matchesResponse(response, scenario.resolver))
    }

    private fun assertFailure(result: Result, breadcrumb: String, rule: String, message: String) {
        assertThat(result.isSuccess()).isFalse()
        val details = (result as Result.Failure).toMatchFailureDetailList()
        assertThat(details.map { it.breadCrumbs.joinToString(".") }).containsExactly(breadcrumb)
        assertThat(result.toIssues().flatMap { it.ruleViolations }.map { it.id }).containsExactly(rule)
        assertThat(result.reportString()).contains(message)
    }

    private fun schemaAccepts(group: String, optional: Boolean, name: String, children: String): Boolean {
        val xsd = """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="$namespace" elementFormDefault="qualified">
            ${declaration(name, group, optional)}</xs:schema>"""
        return try {
            SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(StreamSource(xsd.reader())).newValidator()
                .validate(StreamSource("""<$name xmlns="$namespace">$children</$name>""".reader()))
            true
        } catch (_: org.xml.sax.SAXException) { false }
    }

    private fun root(direction: String) = if (direction == "REQUEST") "Request" else "Response"

    companion object {
        private const val namespace = "urn:diagnostics"
        private const val lengthType = "><xs:simpleType><xs:restriction base=\"xs:string\"><xs:maxLength value=\"3\"/></xs:restriction></xs:simpleType></xs:element>"
    }
}
