package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.log.logger
import io.specmatic.core.pattern.AnyPattern
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.TYPE_ATTRIBUTE_NAME
import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.FullyQualifiedName
import io.specmatic.core.value.namespacePrefix
import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.core.wsdl.parser.WSDLTypeInfo
import io.specmatic.core.wsdl.parser.isAbstractNamedComplexType
import io.specmatic.core.wsdl.payload.ComplexTypedSOAPPayload
import io.specmatic.core.wsdl.payload.SOAPPayload

data class ComplexElement(val wsdlTypeReference: String, val element: XMLNode, val wsdl: WSDL, val namespaceQualification: NamespaceQualification? = null): WSDLElement {
    override fun deriveSpecmaticTypes(specmaticTypeName: String, existingTypes: Map<String, Pattern>, typeStack: Set<String>): WSDLTypeInfo {
        val trimmedTypeName = specmaticTypeName.trim()
        if (specmaticTypeName in typeStack || trimmedTypeName in typeStack || specmaticTypeName in existingTypes || trimmedTypeName in existingTypes)
            return shallowRecursiveTypeReference(specmaticTypeName, existingTypes)

        val complexType = try {
            wsdl.getComplexTypeNode(element)
        } catch(e: ContractException) {
            logger.debug(e, "Error getting type for WSDL type \"$wsdlTypeReference\", ${element.oneLineDescription}")
            throw e
        }

        val attributes = complexType.getAttributes()
        val attributePatterns = attributePatternMap(attributes)
        val attributeNamespaceUris = attributeNamespaceMap(attributes)
        val attributeWildcards = complexType.getAttributeWildcards()

        val childTypeInfos = try {
            complexType.generateChildren(
                specmaticTypeName,
                existingTypes,
                typeStack.plus(specmaticTypeName)
            )
        } catch(e: ContractException) {
            logger.debug(e, "Error getting types for WSDL type \"$wsdlTypeReference\", ${element.oneLineDescription}")
            throw e
        }

        val qualification = namespaceQualification ?: wsdl.getQualification(element, wsdlTypeReference)

        val inPlaceNode = inPlaceNode(specmaticTypeName, qualification)

        val childTypes = childTypeInfos.fold(existingTypes) { accumulated, childTypeInfo ->
            accumulated.plus(childTypeInfo.types)
        }
        val wsdlType = element.typeMetadataName(wsdl, wsdlTypeReference)
        val wsdlTypeIsAbstract = complexType.complexType.isAbstractNamedComplexType()
        val childTypeInfosWithWSDLTypeMetadata = childTypeInfos.withWSDLTypeMetadata(wsdlType, wsdlTypeIsAbstract)
        val resolvedPattern: Pattern = when (childTypeInfos.size) {
            1 -> XMLPattern(childTypeInfosWithWSDLTypeMetadata.single().xmlTypeData.copy(
                attributes = attributePatterns,
                attributeWildcards = attributeWildcards,
                attributeNamespaceUris = attributeNamespaceUris,
            ))
            else -> AnyPattern(
                pattern = childTypeInfosWithWSDLTypeMetadata.map {
                    XMLPattern(it.xmlTypeData.copy(
                        attributes = attributePatterns,
                        attributeWildcards = attributeWildcards,
                        attributeNamespaceUris = attributeNamespaceUris,
                    ))
                },
                extensions = emptyMap()
            )
        }
        val types = childTypes.plus(specmaticTypeName to resolvedPattern)

        val namespaces = childTypeInfos.flatMap { it.namespacePrefixes }.toSet().plus(qualification.namespacePrefix)

        return WSDLTypeInfo(
            listOf(inPlaceNode),
            listOf(XMLPattern(inPlaceNode).withWSDLTypeMetadata(wsdlType)),
            types,
            namespaces,
            wsdlTypeNamespace = wsdlType?.namespace,
            wsdlTypeName = wsdlType?.localName,
            wsdlTypeIsAbstract = wsdlTypeIsAbstract,
        )
    }

    private fun shallowRecursiveTypeReference(specmaticTypeName: String, existingTypes: Map<String, Pattern>): WSDLTypeInfo {
        val qualification = namespaceQualification ?: wsdl.getQualification(element, wsdlTypeReference)
        val inPlaceNode = inPlaceNode(specmaticTypeName, qualification)

        return WSDLTypeInfo(
            nodes = listOf(inPlaceNode),
            members = listOf(XMLPattern(inPlaceNode)),
            types = existingTypes,
            namespacePrefixes = qualification.namespacePrefix.toSet()
        )
    }

    private fun inPlaceNode(specmaticTypeName: String, qualification: NamespaceQualification): XMLNode =
        toXMLNode(
            "<${qualification.nodeName} $TYPE_ATTRIBUTE_NAME=\"${specmaticTypeName.trim()}\"/>",
            namespaceMapFor(qualification.nodeName, wsdl)
        ).plusAttributes(deriveSpecmaticAttributes(element))

    internal fun generateChildren(
        parentTypeName: String,
        complexType: XMLNode,
        existingTypes: Map<String, Pattern>,
        typeStack: Set<String>
    ): List<WSDLTypeInfo> {
        return eliminateAnnotationsAndAttributes(complexType.childNodes.filterIsInstance<XMLNode>()).map {
            complexTypeChildNode(it, wsdl, parentTypeName)
        }.fold(listOf(WSDLTypeInfo())) { wsdlTypeInfos, child ->
            val knownTypes = wsdlTypeInfos.knownTypes(existingTypes)
            child.process(wsdlTypeInfos, knownTypes, typeStack.plus(knownTypes.keys))
        }
    }

    override fun getSOAPPayload(
        nodeNameForSOAPBody: String,
        specmaticTypeName: String,
        namespaces: Map<String, String>,
        typeInfo: WSDLTypeInfo
    ): SOAPPayload {
        val complexType = wsdl.getComplexTypeNode(element)

        return ComplexTypedSOAPPayload(nodeNameForSOAPBody, specmaticTypeName, namespaces, complexType.getAttributes())
    }
}

private fun List<WSDLTypeInfo>.withWSDLTypeMetadata(wsdlType: FullyQualifiedName?, wsdlTypeIsAbstract: Boolean): List<WSDLTypeInfo> =
    map { it.withTypeMetadata(wsdlType, wsdlTypeIsAbstract) }

private fun WSDLTypeInfo.withTypeMetadata(wsdlType: FullyQualifiedName?, wsdlTypeIsAbstract: Boolean): WSDLTypeInfo {
    return copy(
        wsdlTypeNamespace = wsdlType?.namespace,
        wsdlTypeName = wsdlType?.localName,
        wsdlTypeIsAbstract = wsdlTypeIsAbstract,
    )
}

private fun XMLPattern.withWSDLTypeMetadata(wsdlType: FullyQualifiedName?): XMLPattern =
    copy(
        pattern = pattern.copy(
            wsdlTypeNamespace = wsdlType?.namespace,
            wsdlTypeName = wsdlType?.localName,
        )
    )

private fun XMLNode.typeMetadataName(wsdl: WSDL, wsdlTypeReference: String): FullyQualifiedName? {
    return when {
        name == "complexType" -> namedTypeFullyQualifiedName(wsdl)
        attributes.containsKey("type") -> fullyQualifiedNameFromAttributeOrNull("type")
        else -> fullyQualifiedNameFromQNameOrNull(wsdlTypeReference)
    }
}

private fun XMLNode.fullyQualifiedNameFromAttributeOrNull(attributeName: String): FullyQualifiedName? =
    try {
        fullyQualifiedNameFromAttribute(attributeName)
    } catch (e: ContractException) {
        null
    }

private fun XMLNode.fullyQualifiedNameFromQNameOrNull(qName: String): FullyQualifiedName? =
    try {
        fullyQualifiedNameFromQName(qName)
    } catch (e: ContractException) {
        null
    }

internal fun XMLNode.namedTypeFullyQualifiedName(wsdl: WSDL): FullyQualifiedName {
    val namespace = schema?.attributes?.get("targetNamespace")?.toStringLiteral().orEmpty()
    val prefix = namespace.takeIf { it.isNotBlank() }?.let(wsdl::getSchemaNamespacePrefix).orEmpty()
    return FullyQualifiedName(prefix, namespace, getAttributeValue("name"))
}

data class ComplexType(val complexType: XMLNode, val wsdl: WSDL) {
    fun generateChildren(
        parentTypeName: String,
        existingTypes: Map<String, Pattern>,
        typeStack: Set<String>
    ): List<WSDLTypeInfo> {
        return generateChildren(parentTypeName, complexType, existingTypes, typeStack, wsdl)
    }

    fun getAttributes(): List<AttributeElement> {
        return attributesFrom(complexType, wsdl)
    }

    fun getAttributeWildcards() = attributeWildcardsFrom(complexType, wsdl)
}

internal fun generateChildren(
    parentTypeName: String,
    complexType: XMLNode,
    existingTypes: Map<String, Pattern>,
    typeStack: Set<String>,
    wsdl: WSDL
): List<WSDLTypeInfo> {
    return eliminateAnnotationsAndAttributes(complexType.childNodes.filterIsInstance<XMLNode>()).map {
        complexTypeChildNode(it, wsdl, parentTypeName)
    }.fold(listOf(WSDLTypeInfo())) { wsdlTypeInfos, child ->
        val knownTypes = wsdlTypeInfos.knownTypes(existingTypes)
        child.process(wsdlTypeInfos, knownTypes, typeStack.plus(knownTypes.keys))
    }
}

private fun List<WSDLTypeInfo>.knownTypes(existingTypes: Map<String, Pattern>): Map<String, Pattern> =
    fold(existingTypes) { accumulatedTypes, typeInfo ->
        accumulatedTypes.plus(typeInfo.types)
    }

private fun eliminateAnnotationsAndAttributes(childNodes: List<XMLNode>) =
    childNodes.filterNot { it.name == "annotation" || it.name == "attribute" || it.name == "attributeGroup" || it.name == "anyAttribute" }

fun complexTypeChildNode(child: XMLNode, wsdl: WSDL, parentTypeName: String): ComplexTypeChild {
    return when (child.name) {
        "element" -> ElementInComplexType(child, wsdl, parentTypeName)
        "any" -> AnyElementInComplexType(child)
        "sequence", "all" -> CollectionOfChildrenInComplexType(child, wsdl, parentTypeName)
        "choice" -> ChoiceOfChildrenInComplexType(child, wsdl, parentTypeName)
        "complexContent" -> ComplexTypeExtension(child, wsdl, parentTypeName)
        "simpleContent" -> SimpleContentDerivation(child, wsdl)
        else -> throw ContractException("Couldn't recognize child node $child")
    }
}

private fun namespaceMapFor(nodeName: String, wsdl: WSDL): Map<String, String> {
    val prefix = nodeName.namespacePrefix().takeIf { it.isNotBlank() } ?: return emptyMap()
    val namespace = wsdl.prefixToNamespace[prefix] ?: return emptyMap()
    return mapOf(prefix to namespace)
}

internal fun combineVariants(current: List<WSDLTypeInfo>, additions: List<WSDLTypeInfo>): List<WSDLTypeInfo> {
    return current.flatMap { currentVariant ->
        additions.map { addition -> currentVariant.plus(addition) }
    }
}
