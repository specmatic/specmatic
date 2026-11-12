package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.XMLChoiceGroupPattern
import io.specmatic.core.value.XMLNode
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.core.wsdl.parser.WSDLTypeInfo

internal class ChoiceOfChildrenInComplexType(
    private val child: XMLNode,
    private val wsdl: WSDL,
    private val parentTypeName: String
) : ComplexTypeChild {
    override fun process(wsdlTypeInfos: List<WSDLTypeInfo>, existingTypes: Map<String, Pattern>, typeStack: Set<String>): List<WSDLTypeInfo> {
        if (elementOccurrenceRange(child).isZero) return wsdlTypeInfos
        val choiceVariants = child.childNodes.filterIsInstance<XMLNode>().filterNot { it.name == "annotation" }.flatMap { choiceChild ->
            complexTypeChildNode(choiceChild, wsdl, parentTypeName, allowAllGroup = false).process(listOf(WSDLTypeInfo()), existingTypes, typeStack)
        }

        val choiceTypeInfos = choiceTypeInfos(choiceVariants, existingTypes)

        return combineVariants(wsdlTypeInfos, choiceTypeInfos)
    }

    private fun choiceTypeInfos(choiceVariants: List<WSDLTypeInfo>, existingTypes: Map<String, Pattern>): List<WSDLTypeInfo> {
        val range = elementOccurrenceRange(child)

        return listOf(
            WSDLTypeInfo(
                members = listOf(
                    XMLChoiceGroupPattern(
                        choices = choiceVariants.map { it.effectiveMembers }
                    ).withOccurrenceRange(range)
                ),
                types = choiceVariants.fold(existingTypes) { accumulated, variant -> accumulated + variant.types },
                namespacePrefixes = choiceVariants.flatMap { it.namespacePrefixes }.toSet()
            )
        )
    }
}
