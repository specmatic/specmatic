package io.specmatic.core.pattern

import io.specmatic.core.Result.Failure
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.XMLValue
import io.specmatic.core.value.localName
import io.specmatic.core.value.namespacePrefix
import javax.xml.XMLConstants

/** Validate once at the WSDL envelope boundary, including wildcard-skipped descendants. */
internal fun XMLNode.namespaceValidationFailure(): Failure? {
    val bindings = namespaces.toMutableMap()
    fun failure(message: String) = Failure("Invalid XML namespace declarations or names in $realName: $message")
    bindings.forEach { (prefix, uri) ->
        namespaceBindingFailure(prefix, uri)?.let { return failure(it) }
    }
    bindings[XMLConstants.XML_NS_PREFIX] = XMLConstants.XML_NS_URI

    // Restore bindings on leaving each element instead of copying the entire scope for each child.
    // Iterators keep the traversal stack proportional to depth, even for very wide payloads.
    val stack = ArrayDeque<NamespaceValidationScope>()
    var children: Iterator<XMLValue> = listOf(this).iterator()
    while (true) {
        if (!children.hasNext()) {
            if (stack.isEmpty()) return null
            val scope = stack.removeLast()
            scope.previousBindings.forEach { (prefix, uri) ->
                if (uri == null) bindings.remove(prefix) else bindings[prefix] = uri
            }
            children = scope.siblings
            continue
        }
        val node = children.next() as? XMLNode ?: continue
        val previousBindings = mutableMapOf<String, String?>()
        node.attributes.forEach { (name, value) ->
            val prefix = when {
                name == "xmlns" -> ""
                name.startsWith("xmlns:") -> name.removePrefix("xmlns:").also { prefix ->
                    if (prefix.isEmpty()) return failure("Invalid namespace declaration '$name'")
                }
                else -> return@forEach
            }
            namespaceBindingFailure(prefix, value.string)?.let { return failure(it) }
            previousBindings[prefix] = bindings.put(prefix, value.string)
        }
        namespaceNameFailure(node.realName, bindings)?.let { return failure(it) }
        val attributeNames = HashSet<Pair<String, String>>()
        node.attributes.keys.forEach { name ->
            if (name == "xmlns" || name.startsWith("xmlns:")) return@forEach
            namespaceNameFailure(name, bindings)?.let { return failure(it) }
            // Default namespaces apply to element names, never to unprefixed attributes.
            val prefix = name.namespacePrefix()
            val uri = if (prefix.isEmpty()) "" else bindings.getValue(prefix)
            if (!attributeNames.add(uri to name.localName()))
                return failure("Duplicate attribute {$uri}${name.localName()} on ${node.realName}")
        }
        stack.addLast(NamespaceValidationScope(children, previousBindings))
        children = node.childNodes.iterator()
    }
}

private data class NamespaceValidationScope(
    val siblings: Iterator<XMLValue>,
    val previousBindings: Map<String, String?>,
)

private fun namespaceBindingFailure(prefix: String, uri: String): String? = when {
    prefix.isNotEmpty() && !prefix.isNamespaceNCName() -> "Invalid namespace prefix '$prefix'"
    prefix == XMLConstants.XMLNS_ATTRIBUTE -> "The xmlns prefix is reserved"
    uri == XMLConstants.XMLNS_ATTRIBUTE_NS_URI -> "The xmlns namespace URI is reserved"
    (prefix == XMLConstants.XML_NS_PREFIX) != (uri == XMLConstants.XML_NS_URI) ->
        "The xml prefix must bind exclusively to ${XMLConstants.XML_NS_URI}"
    prefix.isNotEmpty() && uri.isEmpty() -> "Namespace prefix '$prefix' cannot have an empty binding"
    else -> null
}

private fun namespaceNameFailure(name: String, bindings: Map<String, String>): String? {
    val colon = name.indexOf(':')
    val prefix = if (colon < 0) "" else name.substring(0, colon)
    val local = if (colon < 0) name else name.substring(colon + 1)
    return when {
        !local.isNamespaceNCName() || (colon >= 0 && !prefix.isNamespaceNCName()) -> "Invalid namespace-qualified name '$name'"
        prefix == XMLConstants.XMLNS_ATTRIBUTE -> "The xmlns prefix cannot name an element or attribute"
        prefix.isNotEmpty() && bindings[prefix].isNullOrEmpty() -> "Unbound namespace prefix '$prefix' in '$name'"
        else -> null
    }
}

/** XML 1.0 (fifth edition) NameStartChar / NameChar, excluding colon for NCNames. */
private fun String.isNamespaceNCName(): Boolean {
    if (isEmpty()) return false
    var offset = 0
    while (offset < length) {
        val codePoint = codePointAt(offset)
        val start = codePoint == '_'.code || codePoint in 'A'.code..'Z'.code || codePoint in 'a'.code..'z'.code ||
            codePoint in 0xC0..0xD6 || codePoint in 0xD8..0xF6 || codePoint in 0xF8..0x2FF ||
            codePoint in 0x370..0x37D || codePoint in 0x37F..0x1FFF || codePoint in 0x200C..0x200D ||
            codePoint in 0x2070..0x218F || codePoint in 0x2C00..0x2FEF || codePoint in 0x3001..0xD7FF ||
            codePoint in 0xF900..0xFDCF || codePoint in 0xFDF0..0xFFFD || codePoint in 0x10000..0xEFFFF
        val continuation = codePoint == '-'.code || codePoint == '.'.code || codePoint in '0'.code..'9'.code ||
            codePoint == 0xB7 || codePoint in 0x300..0x36F || codePoint in 0x203F..0x2040
        if (!start && (offset == 0 || !continuation)) return false
        offset += Character.charCount(codePoint)
    }
    return true
}
