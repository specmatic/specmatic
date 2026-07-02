package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.XMLSubstitutionGroupPattern
import io.specmatic.core.value.FullyQualifiedName
import io.specmatic.core.value.XMLNode
import io.specmatic.core.wsdl.parser.SOAPMessageType
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.core.wsdl.parser.WSDLTypeInfo
import io.specmatic.core.wsdl.payload.SOAPPayload

data class ElementReference(val child: XMLNode, val wsdl: WSDL) : ChildElementType {
    override fun getWSDLElement(): Pair<String, WSDLElement> {
        val wsdlTypeReference = child.attributes.getValue("ref").toStringLiteral()
        val fullyQualifiedName = child.fullyQualifiedNameFromAttribute("ref")
        val specmaticTypeName = wsdlTypeReference.replace(':', '_')

        val otherRefAttributes = child.attributes.minus("ref")

        val resolvedChild = wsdl.getSOAPElement(fullyQualifiedName, child.schema, otherRefAttributes)
        val substitutionMembers = wsdl.getSubstitutionGroupMembers(fullyQualifiedName, child.schema)
        if (substitutionMembers.isEmpty()) {
            if (resolvedChild.isAbstractSubstitutionHead()) {
                throw ContractException("Expected substitutionGroup members for ${fullyQualifiedName.displayNameForError()}, but none were found.")
            }

            return Pair(specmaticTypeName, resolvedChild)
        }

        val substitutionGroup = SubstitutionGroupElement(
            head = SubstitutionCandidate(fullyQualifiedName, resolvedChild),
            substitutes = substitutionMembers.map { member ->
                val memberName = member.fullyQualifiedNameFromGlobalElement()
                val memberAttributes = member.attributes
                    .minus("substitutionGroup")
                    .plus(otherRefAttributes)
                SubstitutionCandidate(memberName, wsdl.getSOAPElement(memberName, member.schema, memberAttributes))
            }
        )

        return Pair(specmaticTypeName, substitutionGroup)
    }
}

private data class SubstitutionCandidate(
    val name: FullyQualifiedName,
    val element: WSDLElement
)

private data class SubstitutionGroupElement(
    val head: SubstitutionCandidate,
    val substitutes: List<SubstitutionCandidate>
) : WSDLElement {
    override fun deriveSpecmaticTypes(
        specmaticTypeName: String,
        existingTypes: Map<String, Pattern>,
        typeStack: Set<String>
    ): WSDLTypeInfo {
        val headTypeInfo = head.element.deriveSpecmaticTypes(specmaticTypeName, existingTypes, typeStack)
        val candidates = when {
            head.element.isAbstractSubstitutionHead() -> substitutes
            else -> listOf(head) + substitutes
        }

        if (candidates.isEmpty()) {
            throw ContractException("Expected substitutionGroup members for ${head.name.displayNameForError()}, but none were found.")
        }

        val candidateInfo = candidates.runningFold(
            CandidateTypeInfo(emptyList(), emptyMap(), emptySet())
        ) { accumulator, candidate ->
            val candidateTypeInfo = candidate.element.deriveSpecmaticTypes(
                candidate.name.qName.replace(':', '_'),
                existingTypes.plus(accumulator.types),
                typeStack
            )

            CandidateTypeInfo(
                patterns = accumulator.patterns.plus(candidateTypeInfo.effectiveMembers),
                types = accumulator.types.plus(candidateTypeInfo.types),
                namespacePrefixes = accumulator.namespacePrefixes.plus(candidateTypeInfo.namespacePrefixes)
            )
        }.last()

        return headTypeInfo.copy(
            members = listOf(XMLSubstitutionGroupPattern(head.name.displayNameForError(), candidateInfo.patterns)),
            types = headTypeInfo.types.plus(candidateInfo.types),
            namespacePrefixes = headTypeInfo.namespacePrefixes.plus(candidateInfo.namespacePrefixes),
        )
    }

    override fun getSOAPPayload(
        soapMessageType: SOAPMessageType,
        nodeNameForSOAPBody: String,
        specmaticTypeName: String,
        namespaces: Map<String, String>,
        typeInfo: WSDLTypeInfo
    ): SOAPPayload = head.element.getSOAPPayload(soapMessageType, nodeNameForSOAPBody, specmaticTypeName, namespaces, typeInfo)
}

private data class CandidateTypeInfo(
    val patterns: List<Pattern>,
    val types: Map<String, Pattern>,
    val namespacePrefixes: Set<String>,
)

private fun WSDLElement.isAbstractSubstitutionHead(): Boolean =
    when (this) {
        is ReferredType -> element.isAbstractGlobalElement()
        is SimpleElement -> element.isAbstractGlobalElement()
        else -> false
    }

private fun XMLNode.isAbstractGlobalElement(): Boolean =
    name == "element" && attributes["abstract"]?.toStringLiteral()?.lowercase() == "true"

private fun XMLNode.fullyQualifiedNameFromGlobalElement(): FullyQualifiedName {
    val namespace = schema?.attributes?.get("targetNamespace")?.toStringLiteral().orEmpty()
    val prefix = namespaces.entries.firstOrNull { (_, uri) -> uri == namespace }?.key.orEmpty()
    return FullyQualifiedName(prefix, namespace, getAttributeValue("name"))
}
