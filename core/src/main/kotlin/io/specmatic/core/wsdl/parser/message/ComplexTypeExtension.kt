package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.value.XMLNode
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.core.wsdl.parser.WSDLTypeInfo

class ComplexTypeExtension(
    private val complexTypeNode: XMLNode,
    val wsdl: WSDL,
    private val parentTypeName: String
): ComplexTypeChild {
    override fun process(
        wsdlTypeInfos: List<WSDLTypeInfo>,
        existingTypes: Map<String, Pattern>,
        typeStack: Set<String>
    ): List<WSDLTypeInfo> {
        val derivation = complexTypeNode.findComplexContentDerivation()

        return when (derivation.name) {
            "extension" -> processExtension(wsdlTypeInfos, existingTypes, typeStack, derivation)
            "restriction" -> processRestriction(wsdlTypeInfos, existingTypes, typeStack, derivation)
            else -> throw ContractException("Couldn't recognize complexContent derivation node $derivation")
        }
    }

    private fun processExtension(
        wsdlTypeInfos: List<WSDLTypeInfo>,
        existingTypes: Map<String, Pattern>,
        typeStack: Set<String>,
        extension: XMLNode
    ): List<WSDLTypeInfo> {
        val parentComplexType = wsdl.findTypeFromAttribute(extension, "base")
        val parentTypeVariants = generateChildren(parentTypeName, parentComplexType, existingTypes, typeStack, wsdl)
        val extensionChild = extension.childElementForDerivation()

        return wsdlTypeInfos.flatMap { current ->
            parentTypeVariants.flatMap { parentTypeInfo ->
                val combinedParent = current.plus(parentTypeInfo)
                when {
                    extensionChild != null -> {
                        val extensionVariants = generateChildren(parentTypeName, extensionChild, combinedParent.types, typeStack, wsdl)
                        combineVariants(listOf(combinedParent), extensionVariants)
                    }
                    else -> listOf(combinedParent)
                }
            }
        }
    }

    private fun processRestriction(
        wsdlTypeInfos: List<WSDLTypeInfo>,
        existingTypes: Map<String, Pattern>,
        typeStack: Set<String>,
        restriction: XMLNode
    ): List<WSDLTypeInfo> {
        wsdl.findTypeFromAttribute(restriction, "base")

        val restrictionChild = restriction.childElementForDerivation()
        return when {
            restrictionChild == null -> wsdlTypeInfos
            else -> combineVariants(
                wsdlTypeInfos,
                generateChildren(parentTypeName, restrictionChild, existingTypes, typeStack, wsdl)
            )
        }
    }
}

private fun XMLNode.findComplexContentDerivation(): XMLNode {
    return findFirstChildByName("extension")
        ?: findFirstChildByName("restriction")
        ?: throw ContractException("Node $realName does not have a child node named extension or restriction")
}

private fun XMLNode.childElementForDerivation(): XMLNode? {
    return childNodes.filterIsInstance<XMLNode>().filterNot {
        it.name == "annotation" || it.name == "attribute" || it.name == "attributeGroup" || it.name == "anyAttribute"
    }.firstOrNull()
}
