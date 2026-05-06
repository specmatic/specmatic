package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.value.XMLNode
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.core.wsdl.parser.WSDLTypeInfo

class SimpleTypeExtension(private var simpleTypeNode: XMLNode, var wsdl: WSDL) : ComplexTypeChild {
    override fun process(
        wsdlTypeInfos: List<WSDLTypeInfo>,
        existingTypes: Map<String, Pattern>,
        typeStack: Set<String>
    ): List<WSDLTypeInfo> {
        val extension = simpleTypeNode.findFirstChildByName("extension", "Node ${simpleTypeNode.realName} does not have a child node named extension")

        val simpleTypeInfo = if (isPrimitiveType(extension.baseAsTypeNode())) {
            WSDLTypeInfo(nodes = listOf(elementTypeValue(extension.baseAsTypeNode())), types = existingTypes)
        } else {
            val simpleType = wsdl.findSimpleType(extension, "base") ?: throw ContractException("Type with name in base of node ${extension.name} could not be found")
            createSimpleTypeInfo(simpleType, wsdl, existingTypes = existingTypes)
        }

        return wsdlTypeInfos.map { it.plus(simpleTypeInfo) }
    }
}

private fun XMLNode.baseAsTypeNode(): XMLNode =
    copy(attributes = attributes.plus("type" to attributes.getValue("base")))
