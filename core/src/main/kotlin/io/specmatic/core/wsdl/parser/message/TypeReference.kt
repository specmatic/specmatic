package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.value.XMLNode
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.core.wsdl.parser.hasSimpleTypeAttribute
import io.specmatic.core.wsdl.parser.specmaticTypeName as toSpecmaticTypeName

data class TypeReference(val child: XMLNode, val wsdl: WSDL): ChildElementType {
    override fun getWSDLElement(): Pair<String, WSDLElement> {
        val wsdlTypeReference = child.attributes.getValue("type").toStringLiteral()
        val specmaticTypeName = toSpecmaticTypeName(wsdlTypeReference)

        val element = when {
            hasSimpleTypeAttribute(child) -> SimpleElement(wsdlTypeReference, child, wsdl)
            else -> ReferredType(wsdlTypeReference, child, wsdl)
        }

        return Pair(specmaticTypeName, element)
    }
}
