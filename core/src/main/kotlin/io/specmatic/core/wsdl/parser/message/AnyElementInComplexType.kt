package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.XMLProcessContents
import io.specmatic.core.pattern.XMLWildcardPattern
import io.specmatic.core.pattern.xmlNamespaceConstraint
import io.specmatic.core.value.XMLNode
import io.specmatic.core.wsdl.parser.WSDLTypeInfo

internal class AnyElementInComplexType(private val anyNode: XMLNode) : ComplexTypeChild {
    override fun process(
        wsdlTypeInfos: List<WSDLTypeInfo>,
        existingTypes: Map<String, Pattern>,
        typeStack: Set<String>
    ): List<WSDLTypeInfo> {
        val wildcard = xmlWildcardPattern(anyNode)
        return wsdlTypeInfos.map { it.plus(WSDLTypeInfo(members = listOf(wildcard), types = existingTypes)) }
    }
}

internal fun xmlWildcardPattern(anyNode: XMLNode): Pattern {
    val targetNamespace = schemaTargetNamespace(anyNode)
    val range = elementOccurrenceRange(anyNode)

    return XMLWildcardPattern(
        namespaceConstraint = xmlNamespaceConstraint(anyNode.attributes["namespace"]?.toStringLiteral(), targetNamespace),
        processContents = XMLProcessContents.from(anyNode.attributes["processContents"]?.toStringLiteral()),
        targetNamespace = targetNamespace
    ).withOccurrenceRange(range)
}

internal fun schemaTargetNamespace(node: XMLNode): String? =
    node.schema?.attributes?.get("targetNamespace")?.toStringLiteral()
