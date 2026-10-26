package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.localName
import io.specmatic.core.wsdl.parser.WSDL

class QualifiedNamespace(
    val element: XMLNode,
    val schema: XMLNode,
    @Suppress("unused") private val wsdlTypeReference: String,
    val wsdl: WSDL
) :
    NamespaceQualification {

    override val namespacePrefix: List<String>
        get() = listOf(wsdl.mapNamespaceToPrefix(elementTargetNamespace()))

    override val nodeName: String
        get() {
            val localName = element.getAttributeValue("name").localName()
            return "${wsdl.mapNamespaceToPrefix(elementTargetNamespace())}:$localName"
        }

    private fun elementTargetNamespace(): String =
        schema.getAttributeValue(
            "targetNamespace",
            "Could not find targetNamespace attribute in schema for qualified element ${element.oneLineDescription}"
        )
}
