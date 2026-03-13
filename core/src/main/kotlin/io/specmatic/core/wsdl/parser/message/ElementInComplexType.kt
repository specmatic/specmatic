package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.pattern.Pattern
import io.specmatic.core.value.XMLNode
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.core.wsdl.parser.WSDLTypeInfo

class ElementInComplexType(
    private val element: XMLNode,
    val wsdl: WSDL,
    private val parentTypeName: String
): ComplexTypeChild {
    override fun process(wsdlTypeInfos: List<WSDLTypeInfo>, existingTypes: Map<String, Pattern>, typeStack: Set<String>): List<WSDLTypeInfo> {
        val wsdlElement = wsdl.getWSDLElementType(parentTypeName, element)
        val (specmaticTypeName, soapElement) = wsdlElement.getWSDLElement()

        val typeInfo = soapElement.deriveSpecmaticTypes(specmaticTypeName, existingTypes, typeStack)

        return wsdlTypeInfos.map { it.plus(typeInfo) }
    }
}
