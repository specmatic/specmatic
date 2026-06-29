package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.value.localName
import io.specmatic.core.value.namespacePrefix
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.XMLNode
import io.specmatic.core.wsdl.parser.message.*

internal const val XML_SCHEMA_INSTANCE_NAMESPACE = "http://www.w3.org/2001/XMLSchema-instance"
internal const val XML_SCHEMA_NAMESPACE = "http://www.w3.org/2001/XMLSchema"

internal data class WSDLTypeName(val namespace: String, val localName: String)

data class XMLTypeData(
    val name: String = "",
    val realName: String,
    val attributes: Map<String, Pattern> = emptyMap(),
    val nodes: List<Pattern> = emptyList(),
    val isSOAP: Boolean = false,
    val namespaceUri: String? = null,
    val attributeWildcards: List<XMLAttributeWildcard> = emptyList(),
    val isSOAPHeader: Boolean = false,
    val attributeNamespaceUris: Map<String, String?> = emptyMap(),
    val wsdlTypeNamespace: String? = null,
    val wsdlTypeName: String? = null,
    val wsdlBaseTypeNamespace: String? = null,
    val wsdlBaseTypeName: String? = null,
) {
    fun hasType(): Boolean = attributes.containsKey(TYPE_ATTRIBUTE_NAME)
    fun hasBeenDereferenced(): Boolean = hasType() && nodes.isNotEmpty()

    fun isConcrete(): Boolean {
        return !hasType() || hasBeenDereferenced()
    }

    fun getAttributeValue(name: String): String? =
        (attributes[name] as ExactValuePattern?)?.pattern?.toStringLiteral()

    fun attributeNamespaceUri(attributeName: String): String? =
        attributeNamespaceUris[withoutOptionality(attributeName)]

    fun isEmpty(): Boolean {
        return name.isEmpty() && attributes.isEmpty() && nodes.isEmpty()
    }

    fun toGherkinString(additionalIndent: String = "", indent: String = ""): String {
        val attributeText = attributes.entries.joinToString(" ") { (key, value) -> "$key=\"$value\"" }.let { if(it.isNotEmpty()) " $it" else ""}

        return when {
            nodes.isEmpty() -> {
                return "$indent<$realName$attributeText/>"
            }
            nodes.size == 1 && nodes.first() !is XMLPattern && nodes.first() !is XMLWildcardPattern -> {
                val bodyText = nodes.first().pattern.toString()
                "$indent<$realName$attributeText>$bodyText</$realName>"
            }
            else -> {
                val childNodeText = nodes.flatMap {
                    when (it) {
                        is XMLPattern -> listOf(it.toGherkinString(additionalIndent, indent + additionalIndent))
                        is XMLWildcardPattern -> (it.generate(Resolver()) as XMLNode).childNodes.map { generated ->
                            XMLPattern(generated as XMLNode).toGherkinString(additionalIndent, indent + additionalIndent)
                        }
                        else -> throw ContractException("Expected an xml node: $it")
                    }
                }.joinToString("\n")

                if (childNodeText.isBlank())
                    return "$indent<$realName$attributeText/>"

                "$indent<$realName$attributeText>\n$childNodeText\n$indent</$realName>"
            }
        }
    }

    fun toGherkinishNode(): XMLNode {
        val childXMLNodes = nodes.map {
            when(it) {
                is XMLPattern -> it.toGherkinXMLNode()
                is XMLWildcardPattern -> null
                else -> StringValue(it.pattern.toString())
            }
        }.filterNotNull()

        return XMLNode(realName, attributes.mapValues { StringValue(it.value.pattern.toString()) }, childXMLNodes)
    }

    fun isOptionalNode(): Boolean {
        return attributes[OCCURS_ATTRIBUTE_NAME].let {
            it is ExactValuePattern && it.pattern.toStringLiteral() == OPTIONAL_ATTRIBUTE_VALUE
        }
    }

    fun isMultipleNode(): Boolean {
        return attributes[OCCURS_ATTRIBUTE_NAME].let {
            it is ExactValuePattern && it.pattern.toStringLiteral() == MULTIPLE_ATTRIBUTE_VALUE
        }
    }

    fun getNodeOccurrence(): NodeOccurrence {
        val attributeType = (attributes[OCCURS_ATTRIBUTE_NAME]) as ExactValuePattern?

        return when(attributeType?.pattern?.toStringLiteral()) {
            "optional" -> NodeOccurrence.Optional
            "multiple" -> NodeOccurrence.Multiple
            else -> NodeOccurrence.Once
        }
    }

    fun isNillable(): Boolean {
        return attributes[NILLABLE_ATTRIBUTE_NAME].let {
            it is ExactValuePattern && it.pattern.toStringLiteral().lowercase() == "true"
        }
    }

    internal fun xsiTypeName(): WSDLTypeName? {
        val attribute = attributes.keys.firstOrNull { attributeName ->
            attributeName.substringAfter(":") == "type" &&
                    (attributeNamespaceUri(attributeName) == XML_SCHEMA_INSTANCE_NAMESPACE ||
                            attributeName.substringBefore(":", "") == "xsi")
        } ?: return null

        val value = (attributes.getValue(attribute) as? ExactValuePattern)?.pattern?.toStringLiteral() ?: return null
        val prefix = value.namespacePrefix()
        val namespace = when {
            prefix.isBlank() -> namespaceUri.orEmpty()
            prefix == "xs" || prefix == "xsd" -> XML_SCHEMA_NAMESPACE
            else -> attributes["xmlns:$prefix"]?.let { (it as? ExactValuePattern)?.pattern?.toStringLiteral() }.orEmpty()
        }

        return WSDLTypeName(namespace, value.localName())
    }

    internal fun wsdlTypeName(): WSDLTypeName? {
        val namespace = wsdlTypeNamespace ?: return null
        val name = wsdlTypeName ?: return null
        return WSDLTypeName(namespace, name)
    }

    internal fun namespaceAttributesForXSIType(typeNamespace: String, typePrefix: String): Map<String, Pattern> {
        val xsiNamespaceAttribute = "xmlns:xsi" to ExactValuePattern(StringValue(XML_SCHEMA_INSTANCE_NAMESPACE))
        val typeNamespaceAttribute = when {
            typePrefix.isBlank() -> null
            prefixForNamespace(typeNamespace) != null -> null
            else -> "xmlns:$typePrefix" to ExactValuePattern(StringValue(typeNamespace))
        }

        return listOfNotNull(xsiNamespaceAttribute, typeNamespaceAttribute).toMap()
    }

    internal fun prefixForNamespace(namespace: String): String? {
        return attributes.entries.firstNotNullOfOrNull { (attributeName, pattern) ->
            if (!attributeName.startsWith("xmlns:")) return@firstNotNullOfOrNull null

            val attributeValue = (pattern as? ExactValuePattern)?.pattern?.toStringLiteral()
            attributeName.removePrefix("xmlns:").takeIf { attributeValue == namespace }
        }
    }

    internal fun prefixForElementNamespace(namespace: String): String? {
        val prefix = realName.namespacePrefix()
        return prefix.takeIf { it.isNotBlank() && namespaceUri == namespace }
    }

    internal fun availableNamespacePrefix(): String {
        val prefixesInUse = attributes.keys
            .filter { it.startsWith("xmlns:") }
            .map { it.removePrefix("xmlns:") }
            .toSet()

        return (listOf("tns") + (1..100).map { index -> "ns$index" })
            .first { prefix -> prefix !in prefixesInUse }
    }
}
