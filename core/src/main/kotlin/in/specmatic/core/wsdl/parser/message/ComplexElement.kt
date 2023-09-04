package `in`.specmatic.core.wsdl.parser.message

import `in`.specmatic.core.log.logger
import `in`.specmatic.core.pattern.ContractException
import `in`.specmatic.core.pattern.TYPE_ATTRIBUTE_NAME
import `in`.specmatic.core.pattern.XMLPattern
import `in`.specmatic.core.value.XMLNode
import `in`.specmatic.core.value.toXMLNode
import `in`.specmatic.core.wsdl.parser.SOAPMessageType
import `in`.specmatic.core.wsdl.parser.WSDL
import `in`.specmatic.core.wsdl.parser.WSDLTypeInfo
import `in`.specmatic.core.wsdl.payload.ComplexTypedSOAPPayload
import `in`.specmatic.core.wsdl.payload.SOAPPayload

data class ComplexElement(val wsdlTypeReference: String, val element: XMLNode, val wsdl: WSDL, val namespaceQualification: NamespaceQualification? = null): WSDLElement {
    override fun deriveSpecmaticTypes(qontractTypeName: String, existingTypes: Map<String, XMLPattern>, typeStack: Set<String>): WSDLTypeInfo {
        if(qontractTypeName in typeStack)
            return WSDLTypeInfo(types = existingTypes)

        val childTypeInfo = try {
            val complexType = wsdl.getComplexTypeNode(element)

            complexType.generateChildren(
                qontractTypeName,
                existingTypes,
                typeStack.plus(qontractTypeName)
            )
        } catch(e: ContractException) {
            logger.debug(e, "Error getting types for WSDL type \"$wsdlTypeReference\", ${element.oneLineDescription}")
            throw e
        }

        val qualification = namespaceQualification ?: wsdl.getQualification(element, wsdlTypeReference)

        val inPlaceNode = toXMLNode("<${qualification.nodeName} $TYPE_ATTRIBUTE_NAME=\"$qontractTypeName\"/>").let {
            it.copy(attributes = it.attributes.plus(deriveSpecmaticAttributes(element)))
        }

        val types = existingTypes
                        .plus(childTypeInfo.types)
                        .plus(qontractTypeName to XMLPattern(childTypeInfo.nodeTypeInfo))

        val namespaces = childTypeInfo.namespacePrefixes.plus(qualification.namespacePrefix)

        return WSDLTypeInfo(
            listOf(inPlaceNode),
            types,
            namespaces
        )
    }

    internal fun generateChildren(
        parentTypeName: String,
        complexType: XMLNode,
        existingTypes: Map<String, XMLPattern>,
        typeStack: Set<String>
    ): WSDLTypeInfo {
        return eliminateAnnotationsAndAttributes(complexType.childNodes.filterIsInstance<XMLNode>()).map {
            complexTypeChildNode(it, wsdl, parentTypeName)
        }.fold(WSDLTypeInfo()) { wsdlTypeInfo, child ->
            child.process(wsdlTypeInfo, existingTypes, typeStack)
        }
    }

    private fun eliminateAnnotationsAndAttributes(childNodes: List<XMLNode>) =
        childNodes.filterNot { it.name == "annotation" || it.name == "attribute" }

    override fun getSOAPPayload(
        soapMessageType: SOAPMessageType,
        nodeNameForSOAPBody: String,
        qontractTypeName: String,
        namespaces: Map<String, String>,
        typeInfo: WSDLTypeInfo
    ): SOAPPayload {
        val complexType = wsdl.getComplexTypeNode(element)

        return ComplexTypedSOAPPayload(soapMessageType, nodeNameForSOAPBody, qontractTypeName, namespaces, complexType.getAttributes())
    }
}

data class ComplexType(val complexType: XMLNode, val wsdl: WSDL) {
    fun generateChildren(
        parentTypeName: String,
        existingTypes: Map<String, XMLPattern>,
        typeStack: Set<String>
    ): WSDLTypeInfo {
        return generateChildren(parentTypeName, complexType, existingTypes, typeStack, wsdl)
    }

    fun getAttributes(): List<AttributeElement> {
        return complexType.childNodes.filterIsInstance<XMLNode>().filter {
            it.name == "attribute"
        }.map { AttributeElement(it) }
    }
}

internal fun generateChildren(
    parentTypeName: String,
    complexType: XMLNode,
    existingTypes: Map<String, XMLPattern>,
    typeStack: Set<String>,
    wsdl: WSDL
): WSDLTypeInfo {
    return eliminateAnnotationsAndAttributes(complexType.childNodes.filterIsInstance<XMLNode>()).map {
        complexTypeChildNode(it, wsdl, parentTypeName)
    }.fold(WSDLTypeInfo()) { wsdlTypeInfo, child ->
        child.process(wsdlTypeInfo, existingTypes, typeStack)
    }
}

private fun eliminateAnnotationsAndAttributes(childNodes: List<XMLNode>) =
    childNodes.filterNot { it.name == "annotation" || it.name == "attribute" }

fun complexTypeChildNode(child: XMLNode, wsdl: WSDL, parentTypeName: String): ComplexTypeChild {
    return when (child.name) {
        "element" -> ElementInComplexType(child, wsdl, parentTypeName)
        "sequence", "all" -> CollectionOfChildrenInComplexType(child, wsdl, parentTypeName)
        "complexContent" -> ComplexTypeExtension(child, wsdl, parentTypeName)
        "simpleContent" -> SimpleTypeExtension(child, wsdl)
        else -> throw ContractException("Couldn't recognize child node $child")
    }
}
