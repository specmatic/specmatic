package io.specmatic.core.pattern

import io.specmatic.core.value.localName
import io.specmatic.core.value.namespacePrefix
import io.specmatic.core.value.StringValue
import io.specmatic.core.wsdl.parser.message.*

internal const val XML_SCHEMA_INSTANCE_NAMESPACE = "http://www.w3.org/2001/XMLSchema-instance"
internal const val XML_SCHEMA_NAMESPACE = "http://www.w3.org/2001/XMLSchema"

data class WSDLTypeName(
    val namespace: String,
    val localName: String,
    val prefix: String? = null
) {
    fun generationKey(): String = "$namespace#$localName"

    fun displayNameForError(): String {
        return if (!prefix.isNullOrBlank()) {
            "$prefix:$localName"
        } else if (namespace.isNotBlank()) {
            "$localName (namespace: $namespace)"
        } else {
            localName
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WSDLTypeName) return false

        return namespace == other.namespace && localName == other.localName
    }

    override fun hashCode(): Int {
        var result = namespace.hashCode()
        result = 31 * result + localName.hashCode()
        return result
    }
}

enum class WSDLTypeDerivationMethod {
    Extension,
    Restriction
}

data class WSDLSubstitutionGroupMember(
    val elementName: WSDLTypeName,
    val typeName: WSDLTypeName,
    val headTypeName: WSDLTypeName? = null,
    val nillable: Boolean = false,
    val isAbstract: Boolean = false,
    val defaultValue: String? = null,
    val fixedValue: String? = null,
    val headBlocksSubstitution: Boolean = false,
    val headBlockedDerivationMethods: Set<WSDLTypeDerivationMethod> = emptySet()
)

enum class WSDLTypeSelectionMode {
    Polymorphic,
    CurrentTypeOnly
}

internal fun isXMLSchemaInstanceTypeAttribute(attributeName: String, namespaceUri: String?): Boolean =
    attributeName.localName() == "type" && namespaceUri == XML_SCHEMA_INSTANCE_NAMESPACE

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
    val wsdlBaseTypeDerivationMethod: WSDLTypeDerivationMethod? = null,
    val wsdlTypeIsAbstract: Boolean = false,
    val wsdlTypeSelectionMode: WSDLTypeSelectionMode = WSDLTypeSelectionMode.Polymorphic,
    val wsdlKnownTypeKeys: Map<WSDLTypeName, String> = emptyMap(),
    val wsdlCompatibleTypeKeys: Map<WSDLTypeName, String> = emptyMap(),
    val wsdlConcreteSubtypeKeys: Map<WSDLTypeName, String> = emptyMap(),
    val wsdlSubstitutionGroupMembers: Map<WSDLTypeName, WSDLSubstitutionGroupMember> = emptyMap(),
) {
    fun hasType(): Boolean = attributes.containsKey(TYPE_ATTRIBUTE_NAME)
    fun hasBeenDereferenced(): Boolean = hasType() && nodes.isNotEmpty()

    fun isConcrete(): Boolean {
        return !hasType() || hasBeenDereferenced()
    }

    fun getAttributeValue(name: String): String? =
        (attributes[name] as ExactValuePattern?)?.pattern?.toStringLiteral()

    fun attributeNamespaceUri(attributeName: String): String? =
        attributeNamespaceUris[withoutOptionality(attributeName)] ?: attributeNamespaceUriFromNamespaceDeclarations(attributeName)

    private fun attributeNamespaceUriFromNamespaceDeclarations(attributeName: String): String? {
        val prefix = attributeName.namespacePrefix()

        return when {
            prefix.isBlank() -> null
            prefix == "xml" -> "http://www.w3.org/XML/1998/namespace"
            else -> attributes["xmlns:$prefix"]?.let { (it as? ExactValuePattern)?.pattern?.toStringLiteral() }
        }
    }

    fun isEmpty(): Boolean {
        return name.isEmpty() && attributes.isEmpty() && nodes.isEmpty()
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

    fun withSubstitutionMemberElementDeclaration(member: WSDLSubstitutionGroupMember): XMLTypeData {
        val nillableAttribute = when {
            member.nillable -> mapOf(NILLABLE_ATTRIBUTE_NAME to ExactValuePattern(StringValue("true")))
            else -> emptyMap()
        }

        val nodesWithFixedValue = when {
            member.fixedValue != null && nodes.size == 1 && nodes.single() !is XMLPattern ->
                listOf(ExactValuePattern(StringValue(member.fixedValue)))

            else -> nodes
        }

        return copy(
            attributes = attributes + nillableAttribute,
            nodes = nodesWithFixedValue
        )
    }

    internal fun xsiTypeName(): WSDLTypeName? {
        val attribute = attributes.keys.firstOrNull { attributeName ->
            isXMLSchemaInstanceTypeAttribute(attributeName, attributeNamespaceUri(attributeName))
        } ?: return null

        val value = (attributes.getValue(attribute) as? ExactValuePattern)?.pattern?.toStringLiteral() ?: return null
        val prefix = value.namespacePrefix()
        val namespace = when {
            prefix.isBlank() -> namespaceUri.orEmpty()
            prefix == "xs" || prefix == "xsd" -> XML_SCHEMA_NAMESPACE
            else -> attributes["xmlns:$prefix"]?.let { (it as? ExactValuePattern)?.pattern?.toStringLiteral() }.orEmpty()
        }

        return WSDLTypeName(namespace, value.localName(), value.namespacePrefix().ifBlank { null })
    }

    internal fun wsdlTypeName(): WSDLTypeName? {
        val namespace = wsdlTypeNamespace ?: return null
        val name = wsdlTypeName ?: return null
        return wsdlKnownTypeKeys.keys.firstOrNull { it.namespace == namespace && it.localName == name }
            ?: WSDLTypeName(namespace, name)
    }

    internal fun namespaceAttributesForXSIType(
        typeNamespace: String,
        typePrefix: String,
        schemaInstancePrefix: String,
    ): Map<String, Pattern> {
        val xsiNamespaceAttribute = when {
            prefixForNamespace(XML_SCHEMA_INSTANCE_NAMESPACE) != null -> null
            else -> "xmlns:$schemaInstancePrefix" to ExactValuePattern(StringValue(XML_SCHEMA_INSTANCE_NAMESPACE))
        }
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

    internal fun availableNamespacePrefix(preferredPrefix: String = "tns"): String {
        val prefixesInUse = attributes.keys
            .filter { it.startsWith("xmlns:") }
            .map { it.removePrefix("xmlns:") }
            .toSet()

        return (sequenceOf(preferredPrefix) + generatedNamespacePrefixes())
            .first { prefix -> prefix !in prefixesInUse }
    }

    private fun generatedNamespacePrefixes(): Sequence<String> =
        generateSequence(1) { index -> index + 1 }.map { index -> "ns$index" }
}
