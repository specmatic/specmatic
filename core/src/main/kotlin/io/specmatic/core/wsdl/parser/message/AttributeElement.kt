package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.XML_ATTR_OPTIONAL_SUFFIX
import io.specmatic.core.pattern.withoutOptionality
import io.specmatic.core.value.Value
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.localName
import io.specmatic.core.wsdl.parser.WSDL

class AttributeElement(xmlNode: XMLNode) {
    val name: String = fromNameAttribute(xmlNode)
        ?: throw ContractException("'name' not defined for attribute: ${xmlNode.oneLineDescription}")
    val type: Value = elementTypeValue(xmlNode)
    private val mandatory: Boolean = isMandatory(xmlNode) ?: false
    val nameWithOptionality: String = when (mandatory) {
        true -> name
        else -> "${name}${XML_ATTR_OPTIONAL_SUFFIX}"
    }
}

fun isMandatory(element: XMLNode): Boolean? {
    return element.attributes["use"]?.let {
        it.toStringLiteral().localName() == "required"
    }
}

fun fromNameAttribute(element: XMLNode): String? {
    return element.attributes["name"]?.toStringLiteral()?.localName()
}

fun attributesFrom(complexType: XMLNode, wsdl: WSDL): List<AttributeElement> {
    return expandAttributes(complexType, wsdl, emptySet())
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
            "attribute" -> listOf(AttributeElement(childNode))
            "attributeGroup" -> expandAttributeGroup(childNode, wsdl, visitedAttributeGroups)
            else -> emptyList()
        }
    }
}

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
