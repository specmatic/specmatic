package io.specmatic.core.wsdl

import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.value.StringValue
import io.specmatic.core.HttpRequest
import io.specmatic.core.CONTENT_TYPE
import io.specmatic.stub.HttpStub
import io.specmatic.testPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

class WSDLAlternativeDiagnosticTest {
    @TempDir lateinit var directory: File

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `choice errors explain the matching element attribute instead of unrelated alternatives`(reverse: Boolean) {
        val fixture = WSDLNamespaceFixture("urn:alternatives", "/alternatives")
        val a = """<xs:element name="A"><xs:complexType><xs:attribute name="attr" type="xs:string" use="required"/></xs:complexType></xs:element>"""
        val b = """<xs:element name="B"><xs:complexType><xs:attribute name="attr" type="xs:int" use="required"/></xs:complexType></xs:element>"""
        val alternatives = if (reverse) b + a else a + b
        val schema = fixture.schema("""<xs:element name="Request"><xs:complexType><xs:choice>$alternatives</xs:choice></xs:complexType></xs:element>
            <xs:element name="Response" type="xs:string"/>""", "elementFormDefault=\"qualified\"")
        val feature = parseContractFileToFeature(directory.resolve("choice.wsdl").apply { writeText(fixture.wsdl(schema)) })
        val xml = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" xmlns:payload="urn:alternatives">
            <s:Body><payload:Request><payload:B attr="hello"/></payload:Request></s:Body></s:Envelope>"""
        HttpStub(feature, port = testPort()).use { stub ->
            listOf(xml, xml.replace(" attr=\"hello\"", "")).forEach { requestXml ->
                val response = stub.client.execute(HttpRequest("POST", "/alternatives",
                    headers = mapOf(CONTENT_TYPE to "text/xml", "SOAPAction" to "/alternatives/submit"), body = StringValue(requestXml)))
                val report = response.body.toStringLiteral()
                assertThat(response.status).withFailMessage(report).isEqualTo(400)
                assertThat(report).contains("Request.B", "attr")
                    .doesNotContain("named A", "name A", "A.attr", "XMLChoiceGroupPattern", "XMLSimpleValue_", "NumberPattern", "xml-choice-group")
                if (requestXml == xml) assertThat(report).contains("Request.B.attr", "hello", "number")
            }
        }
    }
}
