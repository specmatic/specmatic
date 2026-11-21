package io.specmatic.core.wsdl.payload

import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.toXMLNode

class EmptySOAPPayload : SOAPPayload {
    override fun toPattern(headers: RequestHeaders): Pattern {
        return XMLPattern(emptySoapMessage(headers), isSOAP = true)
    }
}

internal fun emptySoapMessage(headers: RequestHeaders = RequestHeaders()): XMLNode {
    val payload = soapSkeleton(headers.namespaces)
    val headerNode = headers.toHeaderNode(payload.namespaces)
    val bodyNode = toXMLNode("<soapenv:Body/>")
    return payload.withChildNodes(payload.childNodes.plus(listOfNotNull(headerNode, bodyNode)))
}
