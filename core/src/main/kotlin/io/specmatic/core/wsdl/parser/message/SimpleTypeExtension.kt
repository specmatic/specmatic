package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.XMLValue
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.core.wsdl.parser.WSDLTypeInfo

class SimpleTypeExtension(private var simpleTypeNode: XMLNode, var wsdl: WSDL) : ComplexTypeChild {
    override fun process(
        wsdlTypeInfo: WSDLTypeInfo,
        existingTypes: Map<String, XMLPattern>,
        typeStack: Set<String>
    ): WSDLTypeInfo {
        val extension = simpleTypeNode.findFirstChildByName("extension", "Node ${simpleTypeNode.realName} does not have a child node named extension")

        val simpleType = wsdl.findSimpleType(extension, "base") ?: throw ContractException("Type with name in base of node ${extension.name} could not be found")
        val simpleTypeInfo = createSimpleTypeInfo(simpleType, wsdl, existingTypes = existingTypes)
        return wsdlTypeInfo.plus(simpleTypeInfo)
    }
}
