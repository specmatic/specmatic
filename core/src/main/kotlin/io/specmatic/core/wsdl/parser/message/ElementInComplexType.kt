package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.pattern.*
import io.specmatic.core.value.XMLNode
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.core.wsdl.parser.WSDLTypeInfo

class ElementInComplexType(
    private val element: XMLNode,
    val wsdl: WSDL,
    private val parentTypeName: String
): ComplexTypeChild {
    override fun process(wsdlTypeInfos: List<WSDLTypeInfo>, existingTypes: Map<String, Pattern>, typeStack: Set<String>): List<WSDLTypeInfo> {
        val range = elementOccurrenceRange(element)
        if (range.isZero) return wsdlTypeInfos
        val wsdlElement = wsdl.getWSDLElementType(parentTypeName, element.copy(attributes = element.attributes - "minOccurs" - "maxOccurs"))
        val (specmaticTypeName, soapElement) = wsdlElement.getWSDLElement()

        val typeInfo = soapElement.deriveSpecmaticTypes(specmaticTypeName, existingTypes, typeStack)

        val rangedInfo = typeInfo.copy(members = typeInfo.effectiveMembers.map { it.withoutLocalOccurrence().withOccurrenceRange(range) })
        return wsdlTypeInfos.map { it.plus(rangedInfo) }
    }
}

private fun Pattern.withoutLocalOccurrence(): Pattern = when (this) {
    is XMLPattern -> copy(pattern = pattern.copy(attributes = pattern.attributes - OCCURS_ATTRIBUTE_NAME))
    is XMLSubstitutionGroupPattern -> copy(candidates = candidates.map { it.withoutLocalOccurrence() })
    else -> this
}
