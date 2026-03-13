package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.pattern.Pattern
import io.specmatic.core.value.XMLNode
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.core.wsdl.parser.WSDLTypeInfo

internal class ChoiceOfChildrenInComplexType(
    private val child: XMLNode,
    private val wsdl: WSDL,
    private val parentTypeName: String
) : ComplexTypeChild {
    override fun process(wsdlTypeInfos: List<WSDLTypeInfo>, existingTypes: Map<String, Pattern>, typeStack: Set<String>): List<WSDLTypeInfo> {
        val choiceVariants = child.childNodes.filterIsInstance<XMLNode>().filterNot { it.name == "annotation" }.flatMap { choiceChild ->
            complexTypeChildNode(choiceChild, wsdl, parentTypeName).process(listOf(WSDLTypeInfo()), existingTypes, typeStack)
        }

        val optionalChoiceVariants = if (child.attributes["minOccurs"]?.toStringLiteral() == "0") {
            choiceVariants.plus(WSDLTypeInfo())
        } else {
            choiceVariants
        }

        return combineVariants(wsdlTypeInfos, optionalChoiceVariants)
    }
}
