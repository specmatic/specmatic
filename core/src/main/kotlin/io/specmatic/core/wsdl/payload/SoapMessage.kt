package io.specmatic.core.wsdl.payload

import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.message.OCCURS_ATTRIBUTE_NAME
import io.specmatic.core.wsdl.parser.message.OPTIONAL_ATTRIBUTE_VALUE
import io.specmatic.core.wsdl.parser.message.primitiveNamespace

fun soapMessage(bodyPayload: XMLNode, namespaces: Map<String, String>, requestHeaders: RequestHeaders): XMLNode {
    val payload = soapSkeleton(namespaces)

    val headerNode: XMLNode? = requestHeaders.toHeaderNode()

    val bodyNode: XMLNode = toXMLNode("<soapenv:Body/>").let {
        it.copy(childNodes = it.childNodes.plus(bodyPayload))
    }

    val payloadNodes = listOfNotNull(headerNode, bodyNode)

    return payload.copy(childNodes = payload.childNodes.plus(payloadNodes))
}

internal fun soapSkeleton(namespaces: Map<String, String>): XMLNode {
    val namespacesString = when(namespaces.size){
        0 -> ""
        else -> namespaces.entries
            .joinToString(" ") {
                "xmlns:${it.key}=\"${it.value}\""
            }
            .prependIndent(" ")
    }
    return toXMLNode(
        """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsd="$primitiveNamespace"$namespacesString></soapenv:Envelope>
        """)
}
