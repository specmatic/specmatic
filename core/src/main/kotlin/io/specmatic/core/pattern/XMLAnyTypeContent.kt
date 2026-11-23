package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.localName

/**
 * Mixed content with lax assessment of declarations available in the WSDL.
 * Undeclared elements are accepted, with lax assessment continuing through descendants.
 * Declaration validation has the same supported subset as the WSDL pattern compiler;
 * this is not a second, complete XSD validator. xsi:type/nil on the anyType element
 * itself, and xsi:type on an undeclared wildcard child, are currently unsupported.
 * Generation deliberately chooses empty content.
 */
class XMLAnyTypeContent(
    private val elements: Map<WSDLTypeName, Lazy<Pair<Pattern, Map<String, Pattern>>>>,
    private val attributes: Map<WSDLTypeName, Lazy<Pattern>>,
    private val schemaIdentity: Map<String, String>,
) {
    fun hasSameDeclarationsAs(other: XMLAnyTypeContent): Boolean = schemaIdentity == other.schemaIdentity

    fun matches(node: XMLNode, resolver: Resolver): Result {
        val attributeResults = node.attributes.filterKeys { it != "xmlns" && !it.startsWith("xmlns:") }.map { (name, value) ->
            val namespace = node.attributeNamespaceUri(name).orEmpty()
            if (namespace == XML_SCHEMA_INSTANCE_NAMESPACE && name.localName() in setOf("type", "nil")) {
                Result.Failure("xsi:${name.localName()} on an anyType element is not supported")
            } else {
                try {
                    attributes[WSDLTypeName(namespace, name.localName())]?.value?.let { pattern ->
                        pattern.matches(pattern.parse(value.toStringLiteral(), resolver), resolver)
                    } ?: Result.Success()
                } catch (e: ContractException) {
                    e.failure()
                }
            }
        }
        val childResults = node.childNodes.filterIsInstance<XMLNode>().map { child ->
            val declaration = elements[WSDLTypeName(child.elementNamespaceUriOrNull().orEmpty(), child.name)]
            if (declaration == null) {
                return@map if (child.attributes.keys.any { it.localName() == "type" && child.attributeNamespaceUri(it) == XML_SCHEMA_INSTANCE_NAMESPACE })
                    Result.Failure("xsi:type on an undeclared anyType child is not supported")
                else matches(child, resolver)
            }
            try {
                val (pattern, types) = declaration.value
                pattern.matches(child, resolver.copy(newPatterns = resolver.newPatterns + types))
            } catch (e: ContractException) {
                e.failure()
            }
        }
        return Result.fromResults(attributeResults + childResults)
    }
}
