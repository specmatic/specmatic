package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.WSDLTypeDerivationMethod
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
        val parentTypeVariants = generateChildren(parentTypeName, parentComplexType.asComplexTypeNode(), existingTypes, typeStack, wsdl)
        val extensionChild = extension.childElementForDerivation()
        val baseContent = modelContent(parentComplexType.asComplexTypeNode())
        val addedContent = extensionChild?.let { modelContent(it) } ?: ModelContent.Empty
        if ((baseContent == ModelContent.All && addedContent != ModelContent.Empty) ||
            (baseContent != ModelContent.Empty && addedContent == ModelContent.All))
            throw ContractException("XSD 1.0 cannot extend nonempty all content with another particle: ${extension.oneLineDescription}")

        return wsdlTypeInfos.flatMap { current ->
            parentTypeVariants.flatMap { parentTypeInfo ->
                val combinedParent = current.plus(parentTypeInfo)
                when {
                    extensionChild != null -> {
                        val extensionVariants = complexTypeChildNode(extensionChild, wsdl, parentTypeName).process(listOf(WSDLTypeInfo()), combinedParent.types, typeStack)
                        combineVariants(listOf(combinedParent), extensionVariants)
                    }
                    else -> listOf(combinedParent)
                }
            }
        }.withBaseTypeMetadata(extension)
    }

    private fun processRestriction(
        wsdlTypeInfos: List<WSDLTypeInfo>,
        existingTypes: Map<String, Pattern>,
        typeStack: Set<String>,
        restriction: XMLNode
    ): List<WSDLTypeInfo> {
        wsdl.findTypeFromAttribute(restriction, "base")

        val restrictionChild = restriction.childElementForDerivation()
        val restrictedTypeInfos = when {
            restrictionChild == null -> wsdlTypeInfos
            else -> combineVariants(
                wsdlTypeInfos,
                complexTypeChildNode(restrictionChild, wsdl, parentTypeName).process(listOf(WSDLTypeInfo()), existingTypes, typeStack)
            )
        }

        return restrictedTypeInfos.withBaseTypeMetadata(restriction)
    }

    private enum class ModelContent { Empty, All, Other }

    private fun modelContent(node: XMLNode, visited: Set<String> = emptySet()): ModelContent = when (node.name) {
        "all" -> if (node.childNodes.filterIsInstance<XMLNode>().any { it.name == "element" }) ModelContent.All else ModelContent.Empty
        "sequence", "choice" -> if (node.childNodes.filterIsInstance<XMLNode>().any { it.name != "annotation" }) ModelContent.Other else ModelContent.Empty
        "element", "any" -> ModelContent.Other
        "complexType", "complexContent" -> node.childNodes.filterIsInstance<XMLNode>()
            .filter { it.name in setOf("all", "sequence", "choice", "group", "complexContent", "extension", "restriction", "element") }
            .firstOrNull()?.let { modelContent(it, visited) } ?: ModelContent.Empty
        "group" -> {
            val name = node.fullyQualifiedNameFromAttribute("ref")
            val key = "group:{${name.namespace}}${name.localName}"
            if (key in visited) ModelContent.Other else wsdl.findGroup(name, node.schema).childNodes.filterIsInstance<XMLNode>()
                .firstOrNull { it.name != "annotation" }?.let { modelContent(it, visited + key) } ?: ModelContent.Empty
        }
        "extension", "restriction" -> {
            val own = node.childElementForDerivation()?.let { modelContent(it, visited) } ?: ModelContent.Empty
            if (own != ModelContent.Empty || node.name == "restriction") own
            else {
                val key = "type:${node.fullyQualifiedNameFromAttribute("base")}"
                if (key in visited) ModelContent.Other else modelContent(wsdl.findTypeFromAttribute(node, "base").asComplexTypeNode(), visited + key)
            }
        }
        else -> ModelContent.Empty
    }

    private fun XMLNode.asComplexTypeNode(): XMLNode =
        when (name) {
            "complexType" -> this
            else -> wsdl.getComplexTypeNode(this).complexType
        }
}

private fun List<WSDLTypeInfo>.withBaseTypeMetadata(derivation: XMLNode): List<WSDLTypeInfo> {
    val baseType = derivation.resolvedBaseTypeName()
    return map {
        it.copy(
            wsdlBaseTypeNamespace = baseType?.namespace,
            wsdlBaseTypeName = baseType?.localName,
            wsdlBaseTypeDerivationMethod = derivation.wsdlTypeDerivationMethod()
        )
    }
}

private fun XMLNode.wsdlTypeDerivationMethod(): WSDLTypeDerivationMethod? =
    when (name) {
        "extension" -> WSDLTypeDerivationMethod.Extension
        "restriction" -> WSDLTypeDerivationMethod.Restriction
        else -> null
    }

private fun XMLNode.resolvedBaseTypeName() =
    fullyQualifiedNameFromAttribute("base").takeIf { it.namespace.isNotBlank() }

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
