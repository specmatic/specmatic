package io.specmatic.core.wsdl.payload

import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.TYPE_ATTRIBUTE_NAME
import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.message.AttributeElement

data class ComplexTypedSOAPPayload(
    val nodeName: String,
    val specmaticTypeName: String,
    val namespaces: Map<String, String>,
    val attributes: List<AttributeElement> = emptyList()
) : SOAPPayload {
    override fun toPattern(headers: RequestHeaders): Pattern {
        return XMLPattern(toEnvelope(headers), isSOAP = true)
    }

    private fun toEnvelope(headers: RequestHeaders): XMLNode {
        val xml = buildXmlDataForComplexElement(nodeName, specmaticTypeName, attributes)
        return soapMessage(toXMLNode(xml), namespaces, headers)
    }
}

fun buildXmlDataForComplexElement(
    nodeName: String,
    specmaticTypeName: String,
    attributes: List<AttributeElement>
): String {
    val attributeString = attributes.joinToString(" ") {
        "${it.nameWithOptionality}=\"${it.type}\""
    }

    return "<${nodeName} $TYPE_ATTRIBUTE_NAME=\"${specmaticTypeName.trim()}\" $attributeString />"
}
