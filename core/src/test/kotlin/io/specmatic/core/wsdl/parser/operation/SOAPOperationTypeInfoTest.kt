package io.specmatic.core.wsdl.parser.operation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.specmatic.core.wsdl.parser.SOAPMessageType
import io.specmatic.core.wsdl.parser.message.OCCURS_ATTRIBUTE_NAME
import io.specmatic.core.wsdl.payload.EmptySOAPPayload

internal class SOAPOperationTypeInfoTest {
    @Test
    fun `generates a gherkin scenario given operation info`() {
        val info = SOAPOperationTypeInfo("customer", SOAPRequest("/get", "getDetails", "/getDetails", EmptySOAPPayload(SOAPMessageType.Input)), SOAPResponse(EmptySOAPPayload(SOAPMessageType.Output)), SOAPTypes(emptyMap()))
        val expectedSOAPPayload = """
            Scenario: customer
              When POST /get
              And enum SoapAction (string) values "/getDetails",/getDetails
              And request-header SOAPAction (SoapAction)
              And request-body
              ""${'"'}
              <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header $OCCURS_ATTRIBUTE_NAME="optional"/><soapenv:Body/></soapenv:Envelope>
              ""${'"'}
              Then status 200
              And response-body
              ""${'"'}
              <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="http://www.w3.org/2001/XMLSchema"><soapenv:Header $OCCURS_ATTRIBUTE_NAME="optional"/><soapenv:Body/></soapenv:Envelope>
              ""${'"'}
        """.trimIndent().trim()

        assertThat(info.toGherkinScenario()).isEqualToIgnoringWhitespace(expectedSOAPPayload)
    }
}