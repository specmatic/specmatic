package io.specmatic.core.wsdl.payload

import io.specmatic.core.pattern.NodeOccurrence
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.message.MULTIPLE_ATTRIBUTE_VALUE
import io.specmatic.core.wsdl.parser.message.OCCURS_ATTRIBUTE_NAME
import io.specmatic.core.wsdl.parser.message.OPTIONAL_ATTRIBUTE_VALUE

private const val HEADER_NODE_NAME = "soapenv:Header"

class RequestHeaders(
    private val headers: Map<String, HeaderDetails> = emptyMap()
) {
    data class HeaderDetails(
        val type: String,
        val occurrence: NodeOccurrence,
    )

    fun toHeaderNode(): XMLNode? {
        if (headers.isEmpty()) return null

        val headerChildNode =
            headers.entries.joinToString("") { (key, details) ->
                val (headerType, occurrence) = details

                val occurrenceClause =
                    when (occurrence) {
                        NodeOccurrence.Optional -> " $OCCURS_ATTRIBUTE_NAME=\"$OPTIONAL_ATTRIBUTE_VALUE\""
                        NodeOccurrence.Multiple -> " $OCCURS_ATTRIBUTE_NAME=\"$MULTIPLE_ATTRIBUTE_VALUE\""
                        else -> ""
                    }

                "<$key$occurrenceClause>$headerType</$key>"
            }

        return toXMLNode("<$HEADER_NODE_NAME>$headerChildNode</$HEADER_NODE_NAME>")
    }
}