package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.pattern.Pattern
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
        val childVariants = generateChildren(parentTypeName, child, existingTypes, typeStack, wsdl)
        return combineVariants(wsdlTypeInfos, childVariants)
    }

}
