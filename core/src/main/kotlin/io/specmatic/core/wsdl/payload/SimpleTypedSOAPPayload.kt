package io.specmatic.core.wsdl.payload

import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.value.XMLNode
import io.specmatic.core.wsdl.parser.SOAPMessageType

data class SimpleTypedSOAPPayload(
    val soapMessageType: SOAPMessageType,
    val node: XMLNode,
    val namespaces: Map<String, String>,
    val bodyPattern: XMLPattern = XMLPattern(node),
) :
    SOAPPayload {
    override fun specmaticStatement(headers: RequestHeaders): List<String> {
        val body = soapMessage(node, namespaces, headers)
        return listOf("And ${soapMessageType.specmaticBodyType}-body\n\"\"\"\n$body\n\"\"\"")
    }

    override fun toPattern(headers: RequestHeaders): Pattern {
        val envelope = XMLPattern(soapMessage(node, namespaces, headers), isSOAP = true)
        return envelope.copy(
            pattern = envelope.pattern.copy(
                nodes = envelope.pattern.nodes.map { childPattern ->
                    when {
                        childPattern is XMLPattern && childPattern.pattern.name == "Body" ->
                            childPattern.copy(pattern = childPattern.pattern.copy(nodes = listOf(bodyPattern)))

                        else -> childPattern
                    }
                }
            )
        )
    }
}
