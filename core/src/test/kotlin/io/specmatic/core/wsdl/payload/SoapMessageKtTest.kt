package io.specmatic.core.wsdl.payload

import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.message.OCCURS_ATTRIBUTE_NAME
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class SoapMessageKtTest {
    @Test
    fun `generate a soap message with no namespaces`() {
        val message = toXMLNode(soapMessage(toXMLNode("<person/>"), emptyMap(), RequestHeaders()).toStringLiteral())
        val expected =
            toXMLNode("""<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header $OCCURS_ATTRIBUTE_NAME="optional"/><soapenv:Body><person/></soapenv:Body></soapenv:Envelope>""")

        assertThat(message).isEqualTo(expected)
    }

    @Test
    fun `generate a soap message with one namespace`() {
        val message = toXMLNode(soapMessage(toXMLNode("<person/>"), mapOf("ns" to "http://namespace.com"), RequestHeaders()).toStringLiteral())
        val expected =
            toXMLNode("""<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ns="http://namespace.com" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header $OCCURS_ATTRIBUTE_NAME="optional"/><soapenv:Body><person/></soapenv:Body></soapenv:Envelope>""")

        assertThat(message).isEqualTo(expected)
    }
}