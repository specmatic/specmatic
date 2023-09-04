package `in`.specmatic.core.wsdl.payload

import `in`.specmatic.core.pattern.TYPE_ATTRIBUTE_NAME
import `in`.specmatic.core.value.toXMLNode
import `in`.specmatic.core.wsdl.parser.SOAPMessageType
import `in`.specmatic.core.wsdl.parser.message.AttributeElement

data class ComplexTypedSOAPPayload(
    val soapMessageType: SOAPMessageType,
    val nodeName: String,
    val qontractTypeName: String,
    val namespaces: Map<String, String>,
    val attributes: List<AttributeElement> = emptyList()
) :
    SOAPPayload {
    override fun qontractStatement(): List<String> {
        val xml = buildXmlDataForComplexElement(nodeName, qontractTypeName, attributes)
        val body = soapMessage(toXMLNode(xml), namespaces)
        return listOf("And ${soapMessageType.qontractBodyType}-body\n\"\"\"\n${body.toPrettyStringValue()}\n\"\"\"")
    }
}

fun buildXmlDataForComplexElement(
    nodeName: String,
    qontractTypeName: String,
    attributes: List<AttributeElement>
): String {
    val attributeString = attributes.joinToString(" ") {
        "${it.nameWithOptionality}=\"${it.type}\""
    }

    return "<${nodeName} $TYPE_ATTRIBUTE_NAME=\"$qontractTypeName\" $attributeString />"
}
