package io.specmatic.core.wsdl.parser

import io.mockk.every
import io.mockk.mockk
import io.specmatic.core.value.FullyQualifiedName
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.message.GetMessageTypeReference
import io.specmatic.core.wsdl.parser.message.MessageTypeProcessingComplete
import io.specmatic.core.wsdl.parser.message.ParseMessageWithElementRef
import io.specmatic.core.wsdl.payload.EmptySOAPPayload
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

internal class GetMessageTypeReferenceTest{
    @Test
    fun `message node with no part results in an empty SOAP payload`() {
        val messageNodeName = "tns:messageNodeName"
        val messageTypeNode: XMLNode = toXMLNode("<tns:message xmlns:tns=\"http://namespace\" message=\"$messageNodeName\" />")

        val wsdl: WSDL = mockk()
        every {
            wsdl.findMessageNode(ofType<FullyQualifiedName>())
        }.returns(toXMLNode("<message />"))

        val next = GetMessageTypeReference(wsdl, messageTypeNode, SOAPMessageType.Input, emptyMap(), "").execute()

        if(next is MessageTypeProcessingComplete) {
            assertThat(next.soapPayloadType?.soapPayload).isEqualTo(EmptySOAPPayload(SOAPMessageType.Input))
        } else {
            fail("Expected the processing to end when the message node is empty")
        }
    }

    @Test
    fun `message node with a part transitions to the step of parsing the payload`() {
        val messageNodeName = "tns:messageNodeName"
        val messageTypeNode: XMLNode = toXMLNode("<tns:input xmlns:tns=\"http://namespace\" message=\"$messageNodeName\" />")

        val wsdl: WSDL = mockk()
        every {
            wsdl.findMessageNode(ofType<FullyQualifiedName>())
        }.returns(toXMLNode("<tns:message xmlns:msg=\"http://localhost:namespace/msg\"><tns:part element=\"msg:payload\"/></tns:message>"))

        val next = GetMessageTypeReference(wsdl, messageTypeNode, SOAPMessageType.Input, emptyMap(), "").execute()

        if(next is ParseMessageWithElementRef) {
            assertThat(next.soapPayloadType).isNull()
        } else {
            fail("Expected the processing to start parsing the structure of what goes inside the SOAP body")
        }
    }
}