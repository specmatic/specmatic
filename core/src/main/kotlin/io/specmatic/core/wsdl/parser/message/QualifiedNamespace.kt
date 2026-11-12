package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.localName
import io.specmatic.core.wsdl.parser.WSDL

class QualifiedNamespace(
    val element: XMLNode,
    val schema: XMLNode,
    private val wsdlTypeReference: String,
    val wsdl: WSDL
) :
    NamespaceQualification {

    private val namespace: String
        get() = schema.attributes["targetNamespace"]?.toStringLiteral()
            ?: element.fullyQualifiedNameFromQName(wsdlTypeReference).namespace

    private val prefix: String
        get() = wsdl.mapNamespaceToPrefix(namespace).ifBlank { wsdl.getSchemaNamespacePrefix(namespace) }

    override val namespacePrefix: List<String>
        get() = listOf(prefix)

    override val nodeName: String
        get() = "$prefix:${element.getAttributeValue("name").localName()}"
}
