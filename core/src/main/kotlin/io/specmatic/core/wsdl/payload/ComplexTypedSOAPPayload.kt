package io.specmatic.core.wsdl.payload

import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.TYPE_ATTRIBUTE_NAME
import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.pattern.XMLTypeData
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.localName
import io.specmatic.core.value.namespacePrefix
import io.specmatic.core.wsdl.parser.message.AttributeElement
import io.specmatic.core.wsdl.parser.message.attributeNamespaceMap
import io.specmatic.core.wsdl.parser.message.attributePatternMap

data class ComplexTypedSOAPPayload(
    val nodeName: String,
    val specmaticTypeName: String,
    val namespaces: Map<String, String>,
    val attributes: List<AttributeElement> = emptyList()
) : SOAPPayload {
    override fun toPattern(headers: RequestHeaders): Pattern {
        val bodyPattern = toBodyPattern()
        val envelope = XMLPattern(toEnvelopeShell(headers), isSOAP = true)

        return envelope.copy(
            pattern = envelope.pattern.copy(
                nodes = envelope.pattern.nodes.map { childPattern ->
                    if (childPattern is XMLPattern && childPattern.pattern.name == "Body") {
                        childPattern.copy(pattern = childPattern.pattern.copy(nodes = listOf(bodyPattern)))
                    } else {
                        childPattern
                    }
                }
            )
        )
    }

    private fun toBodyPattern(): XMLPattern =
        XMLPattern(
            XMLTypeData(
                name = nodeName.localName(),
                realName = nodeName,
                attributes = mapOf(TYPE_ATTRIBUTE_NAME to ExactValuePattern(StringValue(specmaticTypeName.trim()))) +
                    attributePatternMap(attributes),
                namespaceUri = namespaces[nodeName.namespacePrefix()].takeIf { nodeName.namespacePrefix().isNotBlank() },
                attributeNamespaceUris = attributeNamespaceMap(attributes)
            )
        )

    private fun toEnvelopeShell(headers: RequestHeaders): XMLNode {
        return soapMessage(XMLNode(nodeName, emptyMap(), emptyList(), namespaces), namespaces, headers)
    }
}
