package io.specmatic.core.wsdl.parser.operation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.specmatic.core.wsdl.parser.SOAPMessageType
import io.specmatic.core.wsdl.parser.message.OCCURS_ATTRIBUTE_NAME
import io.specmatic.core.wsdl.payload.EmptySOAPPayload

internal class SOAPRequestTest {
    @Test
    fun `generates gherkin statements for a request with a soap action`() {
        val soapRequest = SOAPRequest("/customer", "add", "/add", EmptySOAPPayload(SOAPMessageType.Input))

        val statements = soapRequest.statements()
        assertThat(statements).hasSize(4)

        assertThat(statements).contains("When POST /customer")
        assertThat(statements).contains("And enum SoapAction (string) values \"/add\",/add")
        assertThat(statements).contains("And request-header SOAPAction (SoapAction)")
        assertThat(statements.last()).isEqualToIgnoringNewLines("""
        And request-body
        ""${'"'}
        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
          <soapenv:Header $OCCURS_ATTRIBUTE_NAME="optional"/>
          <soapenv:Body/>
        </soapenv:Envelope>
        ""${'"'}
        """.trimIndent())
    }

    @Test
    fun `generates gherkin statements for a request without a soap action`() {
        val soapRequest = SOAPRequest("/customer", "add", "", EmptySOAPPayload(SOAPMessageType.Input))

        val statements = soapRequest.statements()
        assertThat(statements).hasSize(2)

        assertThat(statements).contains("When POST /customer")
        assertThat(statements.last()).isEqualToIgnoringNewLines("""
        And request-body
        ""${'"'}
        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
          <soapenv:Header $OCCURS_ATTRIBUTE_NAME="optional"/>
          <soapenv:Body/>
        </soapenv:Envelope>
        ""${'"'}
        """.trimIndent())
    }
}