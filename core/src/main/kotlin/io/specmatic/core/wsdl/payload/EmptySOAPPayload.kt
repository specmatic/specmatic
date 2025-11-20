package io.specmatic.core.wsdl.payload

import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.SOAPMessageType

data class EmptySOAPPayload(private val soapMessageType: SOAPMessageType): SOAPPayload {
    override fun specmaticStatement(requestHeaders: RequestHeaders): List<String> {
        val body = emptySoapMessage()
        return listOf("And ${soapMessageType.specmaticBodyType}-body\n\"\"\"\n$body\n\"\"\"")
    }
}

internal fun emptySoapMessage(): XMLNode {
    val payload = soapSkeleton(emptyMap())
    val bodyNode = toXMLNode("<soapenv:Body/>")
    return payload.copy(childNodes = payload.childNodes.plus(bodyNode))
}

