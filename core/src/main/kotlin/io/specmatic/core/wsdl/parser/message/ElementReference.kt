package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.WSDLSubstitutionGroupMember
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
        val substitutionGroupMembers = wsdl.substitutionGroupMembersFor(fullyQualifiedName)
        return Pair(specmaticTypeName, SubstitutionGroupElementReference(resolvedChild, substitutionGroupMembers))
    }
}

private data class SubstitutionGroupElementReference(
    private val delegate: WSDLElement,
    private val substitutionGroupMembers: List<WSDLSubstitutionGroupMember>
) : WSDLElement {
    override fun deriveSpecmaticTypes(
        specmaticTypeName: String,
        existingTypes: Map<String, Pattern>,
        typeStack: Set<String>
    ): WSDLTypeInfo {
        return delegate.deriveSpecmaticTypes(specmaticTypeName, existingTypes, typeStack)
            .withSubstitutionGroupMembers(substitutionGroupMembers)
    }

    override fun getSOAPPayload(
        soapMessageType: SOAPMessageType,
        nodeNameForSOAPBody: String,
        specmaticTypeName: String,
        namespaces: Map<String, String>,
        typeInfo: WSDLTypeInfo
    ): SOAPPayload {
        return delegate.getSOAPPayload(soapMessageType, nodeNameForSOAPBody, specmaticTypeName, namespaces, typeInfo)
    }
}
