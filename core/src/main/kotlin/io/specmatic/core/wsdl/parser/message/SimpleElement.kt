package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.value.FullyQualifiedName
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.XMLValue
import io.specmatic.core.value.localName
import io.specmatic.core.value.namespacePrefix
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

private enum class PrimitiveFamily {
    STRING,
    NUMBER,
    DATETIME,
    BOOLEAN,
    ANYTHING
}

internal data class StringRestrictions(
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val regex: String? = null
) {
    val isEmpty: Boolean
        get() = minLength == null && maxLength == null && regex == null

    fun merge(narrower: StringRestrictions): StringRestrictions =
        StringRestrictions(
            minLength = listOfNotNull(minLength, narrower.minLength).maxOrNull(),
            maxLength = listOfNotNull(maxLength, narrower.maxLength).minOrNull(),
            regex = narrower.regex ?: regex
        )
}

private data class SimpleTypeReference(
    val fullyQualifiedName: FullyQualifiedName,
    val node: XMLNode,
    val attributeName: String
) {
    val key: SimpleTypeReferenceKey
        get() = SimpleTypeReferenceKey(fullyQualifiedName.namespace, fullyQualifiedName.localName)
}

private data class SimpleTypeReferenceKey(val namespace: String, val localName: String)

private data class ResolvedSimpleType(
    val primitiveName: String,
    val family: PrimitiveFamily,
    val restrictions: StringRestrictions = StringRestrictions()
) {
    fun withRestrictions(narrower: StringRestrictions): ResolvedSimpleType {
        return when {
            family == PrimitiveFamily.STRING -> copy(restrictions = restrictions.merge(narrower))
            else -> this
        }
    }

    fun value(): StringValue {
        return when {
            family == PrimitiveFamily.STRING && !restrictions.isEmpty -> constrainedStringValue(restrictions)
            else -> primitiveTypeValue(primitiveName)
        }
    }
}

fun createSimpleTypeInfo(
    typeSourceNode: XMLNode,
    wsdl: WSDL,
    existingTypes: Map<String, Pattern> = emptyMap(),
    actualElement: XMLNode? = null
): WSDLTypeInfo {
    val resolvedElement = actualElement ?: typeSourceNode

    val namespaceUri = resolvedElement.fullyQualifiedName(wsdl).namespace
    return createSimpleType(typeSourceNode, wsdl, resolvedElement).let { (nodes, prefix) ->
        toTypeInfo(nodes = nodes, existingTypes = existingTypes, prefix = prefix, namespaceUri = namespaceUri)
    }
}

fun createSimpleType(element: XMLNode, wsdl: WSDL, actualElement: XMLNode? = null): Pair<List<XMLValue>, String?> {
    val value = simpleTypeValue(element, wsdl)

    val resolvedElement = actualElement ?: element

    val specmaticAttributes = deriveSpecmaticAttributes(resolvedElement)
    val fqname = resolvedElement.fullyQualifiedName(wsdl)
    val prefix = fqname.prefix.ifBlank { null }

    return Pair(listOf(XMLNode(fqname.qName, specmaticAttributes, listOf(value))), prefix)
}

private fun toTypeInfo(nodes: List<XMLValue>, existingTypes: Map<String, Pattern>, prefix: String?, namespaceUri: String? = null): WSDLTypeInfo {
    val members = nodes.map {
        XMLPattern(it as XMLNode).plusNamespaceUri(namespaceUri)
    }

    return if (prefix != null) {
        WSDLTypeInfo(nodes = nodes, members = members, types = existingTypes, namespacePrefixes = setOf(prefix))
    } else {
        WSDLTypeInfo(nodes = nodes, members = members, types = existingTypes)
    }
}

private fun restrictionFacets(element: XMLNode): StringRestrictions {
    val restrictionNode = restrictionNode(element) ?: return StringRestrictions()

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

fun elementTypeValue(element: XMLNode): StringValue =
    primitiveTypeValue(simpleTypeName(element))

fun simpleTypeValue(element: XMLNode, wsdl: WSDL): StringValue =
    resolveSimpleType(element, wsdl, emptySet()).value()

private fun resolveSimpleType(element: XMLNode, wsdl: WSDL, visited: Set<SimpleTypeReferenceKey>): ResolvedSimpleType {
    val reference = simpleTypeReference(element)
        ?: throw ContractException("Could not find type for node ${element.displayableValue()}")
    val base = reference.fullyQualifiedName

    val resolvedBase = when {
        base.isKnownPrimitiveType() -> ResolvedSimpleType(base.localName, base.primitiveFamily())
        base.isUnsupportedPrimitiveType() -> unsupportedPrimitiveType(base)
        reference.key in visited -> throw ContractException("Recursive simpleType base reference ${base.qName} found")
        else -> {
            val simpleType = wsdl.findSimpleType(reference.node, reference.attributeName)
                ?: throw ContractException("Type with name ${base.qName} in ${reference.attributeName} of node ${reference.node.name} could not be found")
            resolveSimpleType(simpleType, wsdl, visited.plus(reference.key))
        }
    }

    return resolvedBase.withRestrictions(restrictionFacets(element))
}

private fun simpleTypeReference(element: XMLNode): SimpleTypeReference? {
    return when {
        element.attributes.containsKey("type") -> SimpleTypeReference(element.simpleTypeQualifiedNameFromAttribute("type"), element, "type")
        else -> restrictionNode(element)?.let { SimpleTypeReference(it.simpleTypeQualifiedNameFromAttribute("base"), it, "base") }
    }
}

private fun XMLNode.simpleTypeQualifiedNameFromAttribute(attributeName: String): FullyQualifiedName {
    return try {
        fullyQualifiedNameFromAttribute(attributeName)
    } catch (e: ContractException) {
        val qName = getAttributeValue(attributeName)
        val prefix = qName.namespacePrefix()

        if (prefix == "xsd" || prefix == "xs") {
            FullyQualifiedName(prefix, primitiveNamespace, qName.localName())
        } else {
            throw e
        }
    }
}

private fun unsupportedPrimitiveType(base: FullyQualifiedName): ResolvedSimpleType {
    logger.debug("Unsupported XML Schema primitive type ${base.qName}; treating it as string")
    return ResolvedSimpleType("string", PrimitiveFamily.STRING)
}

private fun FullyQualifiedName.isKnownPrimitiveType(): Boolean {
    return when (namespace) {
        primitiveNamespace -> localName.isKnownPrimitiveType()
        "" -> localName.isKnownPrimitiveType()
        else -> false
    }
}

private fun FullyQualifiedName.isUnsupportedPrimitiveType(): Boolean =
    namespace == primitiveNamespace && !localName.isKnownPrimitiveType()

private fun String.isKnownPrimitiveType(): Boolean =
    this in primitiveTypes || this == "anyType"

private fun FullyQualifiedName.primitiveFamily(): PrimitiveFamily =
    primitiveFamily(localName)

private fun primitiveFamily(typeName: String): PrimitiveFamily = when (typeName) {
    in primitiveStringTypes -> PrimitiveFamily.STRING
    in constrainedPrimitiveNumberTypes, in primitiveNumberTypes -> PrimitiveFamily.NUMBER
    in primitiveDateTypes -> PrimitiveFamily.DATETIME
    in primitiveBooleanType -> PrimitiveFamily.BOOLEAN
    "anyType" -> PrimitiveFamily.ANYTHING
    else -> throw ContractException("""Primitive type "$typeName" not recognized""")
}

private fun primitiveTypeValue(typeName: String): StringValue = when (typeName) {
    in primitiveStringTypes -> StringValue("(string)")
    in constrainedPrimitiveNumberTypes -> constrainedNumberValue(constrainedPrimitiveNumberTypes.getValue(typeName))
    in primitiveNumberTypes -> StringValue("(number)")
    in primitiveDateTypes -> StringValue("(datetime)")
    in primitiveBooleanType -> StringValue("(boolean)")
    "anyType" -> StringValue("(anything)")
    else -> throw ContractException("""Primitive type "$typeName" not recognized""")
}

private fun constrainedNumberValue(restriction: PrimitiveNumberRestriction): StringValue {
    val clauses = listOfNotNull(
        restriction.minimum?.let { "minimum $it" },
        restriction.maximum?.let { "maximum $it" },
    )

    return if (clauses.isEmpty()) {
        StringValue("(number)")
    } else {
        StringValue("(number) ${clauses.joinToString(" ")})")
    }
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
