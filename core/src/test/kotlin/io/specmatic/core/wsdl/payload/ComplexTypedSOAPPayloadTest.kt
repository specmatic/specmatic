package io.specmatic.core.wsdl.payload

import io.specmatic.core.wsdl.parser.SOAPMessageType
import io.specmatic.trimmedLinesList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class ComplexTypedSOAPPayloadTest {
    @Test
    fun `generates a complex payload with a single namespace`() {
        val type = ComplexTypedSOAPPayload(SOAPMessageType.Input, "person", "Person", mapOf("ns0" to "http://ns"))
        val statement = type.specmaticStatement(RequestHeaders()).first().trim()

        println(statement)
        assertThat(statement.trimmedLinesList()).isEqualTo("""
            And request-body
            ""${'"'}
            <soapenv:Envelope xmlns:ns0="http://ns" xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
              <soapenv:Header specmatic_occurs="optional"/>
              <soapenv:Body>
                <person specmatic_type="Person"/>
              </soapenv:Body>
            </soapenv:Envelope>
            ""${'"'}""".trimIndent().trimmedLinesList())
    }

    @Test
    fun `trims whitespace from specmatic type name in generated payload`() {
        // Create payload with type name that has trailing whitespace
        val type = ComplexTypedSOAPPayload(SOAPMessageType.Input, "person", "Person  ", mapOf("ns0" to "http://ns"))
        val statement = type.specmaticStatement(RequestHeaders()).first().trim()

        // Verify the generated XML has trimmed type name (no whitespace)
        assertThat(statement).contains("specmatic_type=\"Person\"")
        assertThat(statement).doesNotContain("specmatic_type=\"Person  \"")
        assertThat(statement).doesNotContain("Person \"")
    }

    @Test
    fun `buildXmlDataForComplexElement trims whitespace from type name`() {
        val xml = buildXmlDataForComplexElement("person", "  PersonType  ", emptyList())

        // Verify the type attribute has no leading or trailing whitespace
        assertThat(xml).contains("specmatic_type=\"PersonType\"")
        assertThat(xml).doesNotContain("  PersonType  ")
    }
}