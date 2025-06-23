package io.specmatic.core.wsdl.payload

import io.specmatic.core.wsdl.parser.SOAPMessageType
import io.specmatic.core.wsdl.parser.message.OCCURS_ATTRIBUTE_NAME
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class EmptySOAPPayloadTest {
    @Test
    fun `generates a request-body statement with an empty SOAP body`() {
        val statement = EmptySOAPPayload(SOAPMessageType.Input).specmaticStatement().first().trim()
        assertThat(statement).isEqualToIgnoringWhitespace("""And request-body
""${'"'}
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header $OCCURS_ATTRIBUTE_NAME="optional"/><soapenv:Body/></soapenv:Envelope>
""${'"'}""")
    }
}