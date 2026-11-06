package io.specmatic.core.pattern

import io.specmatic.core.value.StringValue
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.XMLValue
import io.specmatic.core.value.localName
import io.specmatic.core.value.namespacePrefix

/** Prefixes are serialization choices; declaration metadata supplies the required namespace. */
internal fun XMLTypeData.generatedXMLNode(
    attributes: Map<String, StringValue>,
    children: List<XMLValue>
): XMLNode {
    val namespace = requiredNamespaceUri
        ?: return XMLNode(realName, attributes, children, inheritNamespacesInChildren = true)
    val element = bindElementName(realName, namespace, attributes)
    val qualifiedAttributes = attributes.entries
        .filterNot { it.key == "xmlns" || it.key.startsWith("xmlns:") }
        .fold(element) { bound, (attributeName, value) ->
            val attributeNamespace = attributeNamespaceUri(attributeName)
            if (attributeNamespace.isNullOrEmpty()) bound else {
                val attribute = bindPrefixedName(attributeName, attributeNamespace, bound.attributes)
                bound.copy(attributes = (attribute.attributes - attributeName) + (attribute.name to value))
            }
        }
    return XMLNode(qualifiedAttributes.name, qualifiedAttributes.attributes, children, inheritNamespacesInChildren = true)
}

private data class BoundXMLName(val name: String, val attributes: Map<String, StringValue>)

private fun bindElementName(name: String, namespace: String, attributes: Map<String, StringValue>): BoundXMLName = when {
    namespace.isEmpty() -> BoundXMLName(name.localName(), attributes + ("xmlns" to StringValue("")))
    name.namespacePrefix().isEmpty() -> BoundXMLName(name.localName(), attributes + ("xmlns" to StringValue(namespace)))
    else -> bindPrefixedName(name, namespace, attributes)
}

private fun bindPrefixedName(name: String, namespace: String, attributes: Map<String, StringValue>): BoundXMLName {
    if (namespace == "http://www.w3.org/XML/1998/namespace")
        return BoundXMLName("xml:${name.localName()}", attributes)

    val preferred = name.namespacePrefix().ifEmpty { "ns" }
    val existingPrefix = attributes.entries.firstOrNull { (key, value) ->
        key.startsWith("xmlns:") && value.string == namespace
    }?.key?.removePrefix("xmlns:")
    val prefix = when {
        preferred !in setOf("xml", "xmlns") && attributes["xmlns:$preferred"]?.string.let { it == null || it == namespace } -> preferred
        existingPrefix != null -> existingPrefix
        else -> generateSequence(1) { it + 1 }.map { "ns$it" }.first { "xmlns:$it" !in attributes }
    }
    return BoundXMLName("$prefix:${name.localName()}", attributes + ("xmlns:$prefix" to StringValue(namespace)))
}
