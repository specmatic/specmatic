package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.XMLAttributeWildcard
import io.specmatic.core.pattern.XML_ATTR_OPTIONAL_SUFFIX
import io.specmatic.core.pattern.XMLProcessContents
import io.specmatic.core.pattern.withoutOptionality
import io.specmatic.core.pattern.xmlNamespaceConstraint
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.localName
import io.specmatic.core.wsdl.parser.WSDL

private data class TypeReferenceKey(val namespace: String, val localName: String)

class AttributeElement(xmlNode: XMLNode, wsdl: WSDL) {
    private val resolvedAttribute = resolveAttributeReference(xmlNode, wsdl)
    val name: String = fromNameAttribute(resolvedAttribute)
        ?: throw ContractException("'name' not defined for attribute: ${xmlNode.oneLineDescription}")
    val type: Value = attributeTypeValue(resolvedAttribute, wsdl)
    private val mandatory: Boolean = isMandatory(resolvedAttribute) ?: false
    val nameWithOptionality: String = when (mandatory) {
        true -> name
        else -> "${name}${XML_ATTR_OPTIONAL_SUFFIX}"
    }
}

private fun resolveAttributeReference(attribute: XMLNode, wsdl: WSDL): XMLNode {
    if (!attribute.attributes.containsKey("ref")) {
        return attribute
    }

    val fullyQualifiedName = attribute.fullyQualifiedNameFromAttribute("ref")
    val referencedAttribute = wsdl.findAttribute(fullyQualifiedName, attribute.schema)
    return referencedAttribute.plusAttributes(attribute.attributes.minus("ref"))
}

private fun attributeTypeValue(attribute: XMLNode, wsdl: WSDL): Value {
    val typeName = fromTypeAttribute(attribute)
    val inlineSimpleType = inlineSimpleType(attribute)

    return when {
        typeName == "anySimpleType" -> StringValue("(string)")
        inlineSimpleType != null -> simpleTypeValue(inlineSimpleType, wsdl)
        fromRestriction(attribute) != null -> simpleTypeValue(attribute, wsdl)
        typeName == null -> StringValue("(string)")
        isPrimitiveType(attribute) -> elementTypeValue(attribute)
        else -> wsdl.findSimpleType(attribute, "type")?.let { simpleTypeValue(it, wsdl) } ?: elementTypeValue(attribute)
    }
}

private fun inlineSimpleType(attribute: XMLNode): XMLNode? =
    attribute.childNodes.filterIsInstance<XMLNode>().firstOrNull { it.name == "simpleType" }

fun isMandatory(element: XMLNode): Boolean? {
    return element.attributes["use"]?.let {
        it.toStringLiteral().localName() == "required"
    }
}

fun fromNameAttribute(element: XMLNode): String? {
    return element.attributes["name"]?.toStringLiteral()?.localName()
}

fun attributesFrom(complexType: XMLNode, wsdl: WSDL): List<AttributeElement> {
    return expandAttributesFromComplexType(complexType, wsdl, initialVisitedTypeReferences(complexType))
}

fun attributeWildcardsFrom(complexType: XMLNode, wsdl: WSDL): List<XMLAttributeWildcard> {
    return expandAttributeWildcardsFromComplexType(complexType, wsdl, initialVisitedTypeReferences(complexType))
}

fun attributePatternMap(attributes: List<AttributeElement>): Map<String, Pattern> {
    val duplicateAttribute = attributes
        .groupBy { withoutOptionality(it.nameWithOptionality) }
        .filterValues { it.size > 1 }
        .keys
        .firstOrNull()

    if (duplicateAttribute != null)
        throw ContractException("Duplicate attribute $duplicateAttribute found while expanding attributeGroup")

    return attributes.associate { it.nameWithOptionality to it.type.exactMatchElseType() }
}

private fun expandAttributes(
    parentNode: XMLNode,
    wsdl: WSDL,
    visitedAttributeGroups: Set<String>
): List<AttributeElement> {
    return parentNode.childNodes.filterIsInstance<XMLNode>().flatMap { childNode ->
        when (childNode.name) {
            "attribute" -> listOf(AttributeElement(childNode, wsdl))
            "attributeGroup" -> expandAttributeGroup(childNode, wsdl, visitedAttributeGroups)
            else -> emptyList()
        }
    }
}

private fun expandAttributesFromComplexType(
    complexType: XMLNode,
    wsdl: WSDL,
    visitedTypeReferences: Set<TypeReferenceKey>
): List<AttributeElement> {
    val complexContentInheritedAttributes = complexContentDerivations(complexType).flatMap { derivation ->
        val baseTypeKey = derivation.baseTypeKey()
        when {
            baseTypeKey in visitedTypeReferences -> emptyList()
            else -> {
                val baseComplexType = wsdl.getComplexTypeNode(wsdl.findTypeFromAttribute(derivation, "base")).complexType
                expandAttributesFromComplexType(baseComplexType, wsdl, visitedTypeReferences.plus(baseTypeKey))
            }
        }
    }
    val simpleContentInheritedAttributes = simpleContentDerivations(complexType).flatMap { derivation ->
        val baseTypeKey = derivation.baseTypeKey()
        when {
            baseTypeKey in visitedTypeReferences -> emptyList()
            baseTypeKey.isPrimitiveTypeReference() -> emptyList()
            else -> wsdl.findComplexTypeOrNull(derivation, "base")?.let { baseComplexType ->
                expandAttributesFromComplexType(baseComplexType, wsdl, visitedTypeReferences.plus(baseTypeKey))
            } ?: emptyList()
        }
    }

    return complexContentInheritedAttributes
        .plus(simpleContentInheritedAttributes)
        .plus(expandAttributes(complexType, wsdl, emptySet()))
        .plus(derivationNodes(complexType).flatMap { expandAttributes(it, wsdl, emptySet()) })
}

private fun expandAttributeWildcards(
    parentNode: XMLNode,
    wsdl: WSDL,
    visitedAttributeGroups: Set<String>
): List<XMLAttributeWildcard> {
    return parentNode.childNodes.filterIsInstance<XMLNode>().flatMap { childNode ->
        when (childNode.name) {
            "anyAttribute" -> listOf(attributeWildcard(childNode))
            "attributeGroup" -> expandAttributeGroupWildcards(childNode, wsdl, visitedAttributeGroups)
            else -> emptyList()
        }
    }
}

private fun expandAttributeWildcardsFromComplexType(
    complexType: XMLNode,
    wsdl: WSDL,
    visitedTypeReferences: Set<TypeReferenceKey>
): List<XMLAttributeWildcard> {
    val complexContentInheritedWildcards = complexContentDerivations(complexType).flatMap { derivation ->
        val baseTypeKey = derivation.baseTypeKey()
        when {
            baseTypeKey in visitedTypeReferences -> emptyList()
            else -> {
                val baseComplexType = wsdl.getComplexTypeNode(wsdl.findTypeFromAttribute(derivation, "base")).complexType
                expandAttributeWildcardsFromComplexType(baseComplexType, wsdl, visitedTypeReferences.plus(baseTypeKey))
            }
        }
    }
    val simpleContentInheritedWildcards = simpleContentDerivations(complexType).flatMap { derivation ->
        val baseTypeKey = derivation.baseTypeKey()
        when {
            baseTypeKey in visitedTypeReferences -> emptyList()
            baseTypeKey.isPrimitiveTypeReference() -> emptyList()
            else -> wsdl.findComplexTypeOrNull(derivation, "base")?.let { baseComplexType ->
                expandAttributeWildcardsFromComplexType(baseComplexType, wsdl, visitedTypeReferences.plus(baseTypeKey))
            } ?: emptyList()
        }
    }

    return complexContentInheritedWildcards
        .plus(simpleContentInheritedWildcards)
        .plus(expandAttributeWildcards(complexType, wsdl, emptySet()))
        .plus(derivationNodes(complexType).flatMap { expandAttributeWildcards(it, wsdl, emptySet()) })
}

private fun attributeWildcard(anyAttribute: XMLNode): XMLAttributeWildcard {
    val targetNamespace = schemaTargetNamespace(anyAttribute)

    return XMLAttributeWildcard(
        namespaceConstraint = xmlNamespaceConstraint(anyAttribute.attributes["namespace"]?.toStringLiteral(), targetNamespace),
        processContents = XMLProcessContents.from(anyAttribute.attributes["processContents"]?.toStringLiteral())
    )
}

private fun initialVisitedTypeReferences(complexType: XMLNode): Set<TypeReferenceKey> {
    val name = complexType.attributes["name"]?.toStringLiteral()?.localName() ?: return emptySet()
    return setOf(TypeReferenceKey(schemaTargetNamespace(complexType).orEmpty(), name))
}

private fun derivationNodes(complexType: XMLNode): List<XMLNode> {
    return complexType.childNodes.filterIsInstance<XMLNode>()
        .flatMap { childNode ->
            when (childNode.name) {
                "complexContent" -> listOfNotNull(
                    childNode.findFirstChildByName("extension") ?: childNode.findFirstChildByName("restriction")
                )
                "simpleContent" -> listOfNotNull(
                    childNode.findFirstChildByName("extension") ?: childNode.findFirstChildByName("restriction")
                )
                else -> emptyList()
            }
        }
}

private fun complexContentDerivations(complexType: XMLNode): List<XMLNode> {
    return complexType.childNodes.filterIsInstance<XMLNode>()
        .filter { it.name == "complexContent" }
        .mapNotNull { it.findFirstChildByName("extension") ?: it.findFirstChildByName("restriction") }
}

private fun simpleContentDerivations(complexType: XMLNode): List<XMLNode> {
    return complexType.childNodes.filterIsInstance<XMLNode>()
        .filter { it.name == "simpleContent" }
        .mapNotNull { it.findFirstChildByName("extension") ?: it.findFirstChildByName("restriction") }
}

private fun XMLNode.baseTypeKey(): TypeReferenceKey {
    val baseType = fullyQualifiedNameFromAttribute("base")
    val namespace = baseType.namespace.ifBlank { schemaTargetNamespace(this).orEmpty() }
    return TypeReferenceKey(namespace, baseType.localName)
}

private fun TypeReferenceKey.isPrimitiveTypeReference(): Boolean =
    namespace == primitiveNamespace || (namespace.isBlank() && localName in primitiveTypes)

private fun expandAttributeGroup(
    attributeGroupReference: XMLNode,
    wsdl: WSDL,
    visitedAttributeGroups: Set<String>
): List<AttributeElement> {
    val fullyQualifiedName = attributeGroupReference.fullyQualifiedNameFromAttribute("ref")
    val groupKey = "${fullyQualifiedName.namespace}:${fullyQualifiedName.localName}"

    if (groupKey in visitedAttributeGroups) {
        logger.debug("Recursive attributeGroup reference ${fullyQualifiedName.qName} already seen; ignoring recursive branch")
        return emptyList()
    }

    val attributeGroup = wsdl.findAttributeGroup(fullyQualifiedName, attributeGroupReference.schema)
    return expandAttributes(attributeGroup, wsdl, visitedAttributeGroups.plus(groupKey))
}

private fun expandAttributeGroupWildcards(
    attributeGroupReference: XMLNode,
    wsdl: WSDL,
    visitedAttributeGroups: Set<String>
): List<XMLAttributeWildcard> {
    val fullyQualifiedName = attributeGroupReference.fullyQualifiedNameFromAttribute("ref")
    val groupKey = "${fullyQualifiedName.namespace}:${fullyQualifiedName.localName}"

    if (groupKey in visitedAttributeGroups) {
        return emptyList()
    }

    val attributeGroup = wsdl.findAttributeGroup(fullyQualifiedName, attributeGroupReference.schema)
    return expandAttributeWildcards(attributeGroup, wsdl, visitedAttributeGroups.plus(groupKey))
}
