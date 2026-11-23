package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.pattern.*
import io.specmatic.core.value.*
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.core.wsdl.withWSDLNamespaceRequirements
import io.specmatic.core.wsdl.parser.WSDLTypeInfo
import io.specmatic.core.wsdl.payload.SOAPPayload
import io.specmatic.core.wsdl.payload.SimpleTypedSOAPPayload

internal fun XMLNode.isAnyTypeElement(): Boolean {
    if (name != "element") return false
    val type = attributes["type"]?.toStringLiteral()
    if (type != null) return type.localName() == "anyType" && resolveNamespace(type) == primitiveNamespace
    return !attributes.containsKey("ref") && childNodes.filterIsInstance<XMLNode>().none { it.name in setOf("simpleType", "complexType") }
}

internal class AnyTypeElement(val element: XMLNode, private val wsdl: WSDL) : WSDLElement {
    override fun deriveSpecmaticTypes(specmaticTypeName: String, existingTypes: Map<String, Pattern>, typeStack: Set<String>): WSDLTypeInfo {
        val name = element.fullyQualifiedName(wsdl)
        val node = XMLNode(name.qName, deriveSpecmaticAttributes(element), emptyList())
        val pattern = XMLPattern(node).plusNamespaceUri(name.namespace).let {
            it.copy(pattern = it.pattern.copy(anyTypeContent = wsdl.anyTypeContent))
        }
        return WSDLTypeInfo(nodes = listOf(node), members = listOf(pattern), types = existingTypes,
            namespacePrefixes = setOfNotNull(name.prefix.takeIf(String::isNotBlank)))
    }

    override fun getSOAPPayload(nodeNameForSOAPBody: String, specmaticTypeName: String, namespaces: Map<String, String>, typeInfo: WSDLTypeInfo): SOAPPayload =
        SimpleTypedSOAPPayload(typeInfo.nodes.single() as XMLNode, namespaces, typeInfo.members.single())
}

/** Lazy declarations avoid loading unrelated types or recursing while compiling anyType. */
internal fun anyTypeContent(wsdl: WSDL): XMLAnyTypeContent {
    val elements = mutableMapOf<WSDLTypeName, Lazy<Pair<Pattern, Map<String, Pattern>>>>()
    val attributes = mutableMapOf<WSDLTypeName, Lazy<Pattern>>()
    for ((namespace, schema) in wsdl.schemas) {
        for (declaration in schema.childNodes.filterIsInstance<XMLNode>()) {
            val name = declaration.attributes["name"]?.toStringLiteral() ?: continue
            val key = WSDLTypeName(namespace, name)
            when (declaration.name) {
                "element" -> elements[key] = lazy {
                    if (declaration.attributes["abstract"]?.toStringLiteral() in setOf("true", "1"))
                        throw ContractException("Abstract element {$namespace}$name cannot appear in anyType content")
                    val prefix = if (namespace.isBlank()) "" else wsdl.getSchemaNamespacePrefix(namespace)
                    val element = wsdl.getSOAPElement(FullyQualifiedName(prefix, namespace, name))
                    val info = element.deriveSpecmaticTypes("anyType_{$namespace}$name", emptyMap(), emptySet())
                    val version = wsdl.getSOAPVersion()
                    Pair(info.effectiveMembers.single().withWSDLNamespaceRequirements(version),
                        info.types.mapValues { (_, pattern) -> pattern.withWSDLNamespaceRequirements(version) })
                }
                "attribute" -> attributes[key] = lazy { AttributeElement(declaration.addSchema(schema), wsdl).type.exactMatchElseType() }
            }
        }
    }
    return XMLAnyTypeContent(elements, attributes, wsdl.schemas.mapValues { (_, schema) -> schema.toStringLiteral() })
}
