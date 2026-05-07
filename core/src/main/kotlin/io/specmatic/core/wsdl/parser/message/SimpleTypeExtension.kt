package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.value.FullyQualifiedName
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.XMLValue
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.core.wsdl.parser.WSDLTypeInfo

class SimpleTypeExtension(private var simpleTypeNode: XMLNode, var wsdl: WSDL) : ComplexTypeChild {
    override fun process(
        wsdlTypeInfos: List<WSDLTypeInfo>,
        existingTypes: Map<String, Pattern>,
        typeStack: Set<String>
    ): List<WSDLTypeInfo> {
        val extension = simpleTypeNode.findFirstChildByName("extension", "Node ${simpleTypeNode.realName} does not have a child node named extension")

        val simpleTypeInfo = WSDLTypeInfo(
            nodes = listOf(simpleContentBaseValue(extension, wsdl)),
            types = existingTypes
        )

        return wsdlTypeInfos.map { it.plus(simpleTypeInfo) }
    }
}

private fun simpleContentBaseValue(extension: XMLNode, wsdl: WSDL): XMLValue {
    val base = extension.fullyQualifiedNameFromAttribute("base")

    return when {
        base.isKnownPrimitiveType() -> elementTypeValue(extension.baseAsTypeNode())
        base.isUnsupportedPrimitiveType() -> unsupportedPrimitiveBaseValue(base)
        else -> {
            val simpleType = wsdl.findSimpleType(extension, "base")
                ?: throw ContractException("Type with name in base of node ${extension.name} could not be found")
            simpleTypeValue(simpleType)
        }
    }
}

private fun unsupportedPrimitiveBaseValue(base: FullyQualifiedName): StringValue {
    logger.debug("Unsupported XML Schema primitive type ${base.qName} in simpleContent extension base; treating it as string")
    return StringValue("(string)")
}

private fun FullyQualifiedName.isKnownPrimitiveType(): Boolean {
    return when (namespace) {
        primitiveNamespace -> localName in primitiveTypes
        "" -> localName in primitiveTypes
        else -> false
    }
}

private fun FullyQualifiedName.isUnsupportedPrimitiveType(): Boolean =
    namespace == primitiveNamespace && localName !in primitiveTypes

private fun XMLNode.baseAsTypeNode(): XMLNode =
    copy(attributes = attributes.plus("type" to attributes.getValue("base")))
