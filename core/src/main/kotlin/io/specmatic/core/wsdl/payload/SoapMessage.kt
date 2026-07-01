package io.specmatic.core.wsdl.payload

import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.XMLValue
import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.message.primitiveNamespace

fun soapMessage(bodyPayload: XMLNode, namespaces: Map<String, String>, headers: RequestHeaders): XMLNode {
    val payload = soapSkeleton(namespaces.plus(headers.namespaces))

    val headerNode: XMLNode? = headers.toHeaderNode(payload.namespaces)

    val bodyNode: XMLNode = toXMLNode("<soapenv:Body/>").let {
        it.copy(childNodes = it.childNodes.plus(bodyPayload))
    }

    val payloadNodes = listOfNotNull(headerNode, bodyNode)

    return payload.withChildNodes(payload.childNodes.plus(payloadNodes))
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

internal fun XMLNode.withChildNodes(childNodes: List<XMLValue>): XMLNode {
    return XMLNode(realName, attributes, childNodes, inheritNamespacesInChildren = true)
}
