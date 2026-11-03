package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.pattern.*
import io.specmatic.core.value.XMLNode
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.core.wsdl.parser.WSDLTypeInfo

internal class CollectionOfChildrenInComplexType(
    private val child: XMLNode,
    val wsdl: WSDL,
    private val parentTypeName: String
):
    ComplexTypeChild {
    override fun process(wsdlTypeInfos: List<WSDLTypeInfo>, existingTypes: Map<String, Pattern>, typeStack: Set<String>): List<WSDLTypeInfo> {
        val range = elementOccurrenceRange(child)
        if (range.isZero) return wsdlTypeInfos
        val childVariants = generateChildren(parentTypeName, child, existingTypes, typeStack, wsdl)
        val groups = childVariants.map { variant ->
            val group = if (child.name == "all") XMLAllPattern(variant.effectiveMembers) else XMLSequencePattern(variant.effectiveMembers)
            variant.copy(members = listOf(XMLParticlePattern(group, range)))
        }
        return combineVariants(wsdlTypeInfos, groups)
    }

}
