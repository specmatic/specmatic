package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.pattern.Pattern
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
        val extension = complexTypeNode.findFirstChildByName("extension", "Found complexContent node without base attribute: $complexTypeNode")

        val parentComplexType = wsdl.findTypeFromAttribute(extension, "base")
        val parentTypeVariants = generateChildren(parentTypeName, parentComplexType, existingTypes, typeStack, wsdl)

        val extensionChild = extension.childNodes.filterIsInstance<XMLNode>().filterNot {
            it.name == "annotation"
        }.firstOrNull()

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

}
