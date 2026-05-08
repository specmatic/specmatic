package io.specmatic.core.wsdl.payload

import io.specmatic.core.pattern.NodeOccurrence
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.message.MULTIPLE_ATTRIBUTE_VALUE
import io.specmatic.core.wsdl.parser.message.OCCURS_ATTRIBUTE_NAME
import io.specmatic.core.wsdl.parser.message.OPTIONAL_ATTRIBUTE_VALUE

private const val HEADER_NODE_NAME = "soapenv:Header"

class RequestHeaders private constructor(
    private val headers: List<Header>
) {
    data class HeaderDetails(
        val type: String,
        val occurrence: NodeOccurrence,
    )

    data class Header(
        val node: XMLNode,
        val namespaces: Map<String, String> = emptyMap(),
    )

    constructor(headers: Map<String, HeaderDetails> = emptyMap()) : this(
        headers.map { entry ->
            primitiveHeader(entry.key, entry.value.type, entry.value.occurrence)
        }
    )

    val namespaces: Map<String, String>
        get() = headers.fold(emptyMap()) { namespaces, header ->
            namespaces.plus(header.namespaces)
        }

    fun toHeaderNode(parentNamespaces: Map<String, String> = emptyMap()): XMLNode? {
        if (headers.isEmpty()) return null

        val headerNamespaces = parentNamespaces.plus(namespaces)
        return toXMLNode("<$HEADER_NODE_NAME/>", headerNamespaces).copy(
            childNodes = headers.map { it.node.withNamespaceContext(headerNamespaces.plus(it.namespaces)) }
        )
    }

    companion object {
        fun fromHeaders(headers: List<Header>): RequestHeaders = RequestHeaders(headers)

        fun primitiveHeader(name: String, type: String, occurrence: NodeOccurrence): Header {
            return Header(withOccurrence(toXMLNode("<$name>$type</$name>"), occurrence))
        }

        fun withOccurrence(node: XMLNode, occurrence: NodeOccurrence): XMLNode {
            return node.copy(attributes = node.attributes.plus(occurrenceAttributes(occurrence)))
        }

        private fun occurrenceAttributes(occurrence: NodeOccurrence) =
            when (occurrence) {
                NodeOccurrence.Optional -> mapOf(OCCURS_ATTRIBUTE_NAME to OPTIONAL_ATTRIBUTE_VALUE)
                NodeOccurrence.Multiple -> mapOf(OCCURS_ATTRIBUTE_NAME to MULTIPLE_ATTRIBUTE_VALUE)
                else -> emptyMap()
            }.mapValues { (_, value) -> StringValue(value) }
    }
}

private fun XMLNode.withNamespaceContext(namespaceContext: Map<String, String>): XMLNode {
    val updatedNamespaces = namespaceContext.plus(namespaces)

    return copy(
        namespaces = updatedNamespaces,
        childNodes = childNodes.map {
            when (it) {
                is XMLNode -> it.withNamespaceContext(updatedNamespaces)
                else -> it
            }
        }
    )
}
