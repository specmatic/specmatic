package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.XMLValue
import io.specmatic.core.value.localName
import io.specmatic.core.wsdl.parser.SOAPMessageType
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.core.wsdl.parser.WSDLTypeInfo
import io.specmatic.core.wsdl.payload.SOAPPayload
import io.specmatic.core.wsdl.payload.SimpleTypedSOAPPayload

data class SimpleElement(
    val wsdlTypeReference: String,
    val element: XMLNode,
    val wsdl: WSDL,
    val simpleTypeNode: XMLNode? = null
) : WSDLElement {
    override fun deriveSpecmaticTypes(specmaticTypeName: String, existingTypes: Map<String, Pattern>, typeStack: Set<String>): WSDLTypeInfo {
        return createSimpleTypeInfo(
            typeSourceNode = simpleTypeNode ?: element,
            wsdl = wsdl,
            existingTypes = existingTypes,
            actualElement = element
        )
    }

    override fun getSOAPPayload(
        soapMessageType: SOAPMessageType,
        nodeNameForSOAPBody: String,
        specmaticTypeName: String,
        namespaces: Map<String, String>,
        typeInfo: WSDLTypeInfo
    ): SOAPPayload {
        return SimpleTypedSOAPPayload(soapMessageType, typeInfo.nodes.first() as XMLNode, namespaces)
    }

}

internal data class StringRestrictions(
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val regex: String? = null
) {
    val isEmpty: Boolean
        get() = minLength == null && maxLength == null && regex == null
}

fun createSimpleTypeInfo(
    typeSourceNode: XMLNode,
    wsdl: WSDL,
    existingTypes: Map<String, Pattern> = emptyMap(),
    actualElement: XMLNode? = null
): WSDLTypeInfo {
    val resolvedElement = actualElement ?: typeSourceNode
    val stringRestrictions = stringRestrictions(typeSourceNode)

    return if (stringRestrictions != null && !stringRestrictions.isEmpty) {
        createConstrainedStringTypeInfo(
            resolvedElement = resolvedElement,
            wsdl = wsdl,
            stringRestrictions = stringRestrictions,
            existingTypes = existingTypes
        )
    } else {
        createSimpleType(typeSourceNode, wsdl, resolvedElement).let { (nodes, prefix) ->
            toTypeInfo(nodes = nodes, existingTypes = existingTypes, prefix = prefix)
        }
    }
}

private fun createConstrainedStringTypeInfo(
    resolvedElement: XMLNode,
    wsdl: WSDL,
    stringRestrictions: StringRestrictions,
    existingTypes: Map<String, Pattern>
): WSDLTypeInfo {
    val specmaticAttributes = deriveSpecmaticAttributes(resolvedElement)
    val fqname = resolvedElement.fullyQualifiedName(wsdl)
    val prefix = fqname.prefix.ifBlank { null }
    val constrainedNode = XMLNode(
        fqname.qName,
        specmaticAttributes,
        listOf(constrainedStringValue(stringRestrictions))
    )
    return toTypeInfo(nodes = listOf(constrainedNode), existingTypes = existingTypes, prefix = prefix)
}

fun createSimpleType(element: XMLNode, wsdl: WSDL, actualElement: XMLNode? = null): Pair<List<XMLValue>, String?> {
    val value = elementTypeValue(element)

    val resolvedElement = actualElement ?: element

    val specmaticAttributes = deriveSpecmaticAttributes(resolvedElement)
    val fqname = resolvedElement.fullyQualifiedName(wsdl)
    val prefix = fqname.prefix.ifBlank { null }

    return Pair(listOf(XMLNode(fqname.qName, specmaticAttributes, listOf(value))), prefix)
}

private fun toTypeInfo(nodes: List<XMLValue>, existingTypes: Map<String, Pattern>, prefix: String?): WSDLTypeInfo {
    return if (prefix != null) {
        WSDLTypeInfo(nodes = nodes, types = existingTypes, namespacePrefixes = setOf(prefix))
    } else {
        WSDLTypeInfo(nodes = nodes, types = existingTypes)
    }
}

private fun stringRestrictions(element: XMLNode): StringRestrictions? {
    val restrictionNode = restrictionNode(element) ?: return null
    val baseType = restrictionNode.getAttributeValue("base").localName()

    if (baseType != "token") {
        return null
    }

    return StringRestrictions(
        minLength = restrictionNode.findNumericRestrictionValue("minLength"),
        maxLength = restrictionNode.findNumericRestrictionValue("maxLength"),
        regex = restrictionNode.findRestrictionPattern()
    )
}

private fun restrictionNode(element: XMLNode): XMLNode? {
    if (element.name == "restriction") {
        return element
    }

    val childElements = element.childNodes.filterIsInstance<XMLNode>()
    return childElements.firstOrNull { it.name == "restriction" }
        ?: childElements.firstOrNull { it.name == "simpleType" }?.let(::restrictionNode)
}

private fun XMLNode.findNumericRestrictionValue(name: String): Int? =
    childNodes.filterIsInstance<XMLNode>()
        .firstOrNull { it.name == name }
        ?.attributes?.get("value")
        ?.toStringLiteral()
        ?.toIntOrNull()

private fun XMLNode.findRestrictionPattern(): String? =
    childNodes.filterIsInstance<XMLNode>()
        .firstOrNull { it.name == "pattern" }
        ?.attributes?.get("value")
        ?.toStringLiteral()

private fun constrainedStringValue(restrictions: StringRestrictions): StringValue {
    val restrictionClauses = listOfNotNull(
        restrictions.minLength?.let { "minLength $it" },
        restrictions.maxLength?.let { "maxLength $it" },
        restrictions.regex?.let { "regex $it" }
    )

    val token = if (restrictionClauses.isEmpty()) {
        "(string)"
    } else {
        "(string) ${restrictionClauses.joinToString(" ")})"
    }

    return StringValue(token)
}

fun elementTypeValue(element: XMLNode): StringValue = when (val typeName = simpleTypeName(element)) {
    in primitiveStringTypes -> StringValue("(string)")
    in primitiveNumberTypes -> StringValue("(number)")
    in primitiveDateTypes -> StringValue("(datetime)")
    in primitiveBooleanType -> StringValue("(boolean)")
    "anyType" -> StringValue("(anything)")

    else -> throw ContractException("""Primitive type "$typeName" not recognized""")
}

fun simpleTypeName(element: XMLNode): String {
    return fromTypeAttribute(element) ?: fromRestriction(element) ?: throw ContractException("Could not find type for node ${element.displayableValue()}")
}

fun fromRestriction(element: XMLNode): String? {
    return element.childNodes.find { it is XMLNode && it.name == "restriction" }?.let {
        it as XMLNode
        it.getAttributeValue("base").localName()
    }
}

fun fromTypeAttribute(element: XMLNode): String? {
    return element.attributes["type"]?.toStringLiteral()?.localName()
}
