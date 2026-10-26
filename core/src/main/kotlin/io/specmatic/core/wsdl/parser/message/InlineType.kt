package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.pattern.ContractException
import io.specmatic.core.value.XMLNode
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.core.wsdl.parser.hasSimpleTypeAttribute

data class InlineType(val parentTypeName: String, val child: XMLNode, val wsdl: WSDL): ChildElementType {
    override fun getWSDLElement(): Pair<String, WSDLElement> {
        val wsdlTypeReference = ""

        val elementName = child.attributes["name"]
            ?: throw ContractException("Element does not have a name: $child")
        val specmaticTypeName = "${parentTypeName}_$elementName"

        val element = when {
            hasSimpleTypeAttribute(child) || child.findFirstChildByName("simpleType") != null ->
                SimpleElement(wsdlTypeReference, child, wsdl)
            else -> ComplexElement(wsdlTypeReference, child, wsdl)
        }

        return Pair(specmaticTypeName, element)
    }
}
