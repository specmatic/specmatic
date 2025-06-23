package io.specmatic.core.wsdl.parser.operation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.specmatic.core.wsdl.parser.SOAPMessageType
import io.specmatic.core.wsdl.parser.message.OCCURS_ATTRIBUTE_NAME
import io.specmatic.core.wsdl.payload.EmptySOAPPayload

internal class SOAPResponseTest {
    @Test
    fun `SOAP response generation`() {
        val soapResponse = SOAPResponse(EmptySOAPPayload(SOAPMessageType.Input))
        val statements = soapResponse.statements()
        assertThat(statements).hasSize(2)
        assertThat(statements).contains("Then status 200")
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