package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.value.XMLNode
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.core.wsdl.parser.WSDLTypeInfo

class SimpleContentDerivation(private var simpleContentNode: XMLNode, var wsdl: WSDL) : ComplexTypeChild {
    override fun process(
        wsdlTypeInfos: List<WSDLTypeInfo>,
        existingTypes: Map<String, Pattern>,
        typeStack: Set<String>
    ): List<WSDLTypeInfo> {
        val derivation = simpleContentNode.findSimpleContentDerivation()
        val baseType = derivation.fullyQualifiedNameFromAttribute("base")

        val simpleTypeInfo = WSDLTypeInfo(
            nodes = listOf(simpleContentDerivationValue(derivation, wsdl)),
            types = existingTypes,
            wsdlBaseTypeNamespace = baseType.namespace,
            wsdlBaseTypeName = baseType.localName,
        )

        return wsdlTypeInfos.map { it.plus(simpleTypeInfo) }
    }
}

private fun XMLNode.findSimpleContentDerivation(): XMLNode {
    return findFirstChildByName("extension")
        ?: findFirstChildByName("restriction")
        ?: throw ContractException("Node $realName does not have a child node named extension or restriction")
}

private fun simpleContentDerivationValue(derivation: XMLNode, wsdl: WSDL) =
    when (derivation.name) {
        "extension" -> simpleTypeValue(derivation.baseAsTypeNode(), wsdl)
        "restriction" -> simpleTypeValue(derivation, wsdl)
        else -> throw ContractException("Couldn't recognize simpleContent derivation node $derivation")
    }

private fun XMLNode.baseAsTypeNode(): XMLNode =
    copy(attributes = attributes.plus("type" to attributes.getValue("base")))
