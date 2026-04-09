package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.value.StringValue
import io.specmatic.core.value.XMLNode

fun deriveSpecmaticAttributes(element: XMLNode): Map<String, StringValue> {
    return when {
        elementIsOptional(element) -> mapOf(OCCURS_ATTRIBUTE_NAME to StringValue(OPTIONAL_ATTRIBUTE_VALUE))
        multipleElementsCanExist(element) -> mapOf(OCCURS_ATTRIBUTE_NAME to StringValue(MULTIPLE_ATTRIBUTE_VALUE))
        else -> emptyMap()
    }.let {
        if(element.attributes["nillable"]?.toStringLiteral()?.lowercase() == "true")
            it.plus(NILLABLE_ATTRIBUTE_NAME to StringValue("true"))
        else
            it
    }
}

private fun multipleElementsCanExist(element: XMLNode): Boolean {
    return element.attributes.containsKey("maxOccurs")
            && (element.attributes["maxOccurs"]?.toStringLiteral() == "unbounded"
            || element.attributes.getValue("maxOccurs").toStringLiteral().toInt() > 1)
}

private fun elementIsOptional(element: XMLNode): Boolean {
    return element.attributes["minOccurs"]?.toStringLiteral() == "0"
            && (!element.attributes.containsKey("maxOccurs") || element.attributes.getValue("maxOccurs").toStringLiteral() == "1")
}

fun isPrimitiveType(node: XMLNode): Boolean {
    val type = simpleTypeName(node)
    val namespace = node.resolveNamespace(type)

    if(namespace.isBlank())
        return primitiveTypes.contains(type)

    return namespace == primitiveNamespace
}

val primitiveStringTypes = listOf(
    "string",
    "token",
    "duration",
    "time",
    "date",
    "gYearMonth",
    "gYear",
    "gMonthDay",
    "gDay",
    "gMonth",
    "hexBinary",
    "base64Binary",
    "anyURI", // TODO maybe this can be converted to URL type
    "QName",
    "NOTATION"
)
val primitiveNumberTypes = listOf(
    "byte",
    "short",
    "int",
    "integer",
    "long",
    "unsignedByte",
    "unsignedShort",
    "unsignedInt",
    "unsignedLong",
    "positiveInteger",
    "negativeInteger",
    "nonPositiveInteger",
    "nonNegativeInteger",
    "decimal",
    "float",
    "double",
    "numeric"
)
val primitiveDateTypes = listOf("dateTime")
val primitiveBooleanType = listOf("boolean")
val primitiveTypes = primitiveStringTypes.plus(primitiveNumberTypes).plus(primitiveDateTypes).plus(primitiveBooleanType)

val constrainedPrimitiveNumberTypes = mapOf(
    "positiveInteger" to PrimitiveNumberRestriction(minimum = "1"),
    "negativeInteger" to PrimitiveNumberRestriction(maximum = "-1"),
    "nonPositiveInteger" to PrimitiveNumberRestriction(maximum = "0"),
    "nonNegativeInteger" to PrimitiveNumberRestriction(minimum = "0"),
)

data class PrimitiveNumberRestriction(
    val minimum: String? = null,
    val maximum: String? = null,
)

internal const val primitiveNamespace = "http://www.w3.org/2001/XMLSchema"

const val OCCURS_ATTRIBUTE_NAME = "specmatic_occurs"
const val NILLABLE_ATTRIBUTE_NAME = "specmatic_nillable"

const val OPTIONAL_ATTRIBUTE_VALUE = "optional"
const val MULTIPLE_ATTRIBUTE_VALUE = "multiple"
