package io.specmatic.core.wsdl.parser

import io.specmatic.core.ScenarioInfo
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.NodeOccurrence
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.value.FullyQualifiedName
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.xmlNode
import io.specmatic.core.utilities.capitalizeFirstChar
import io.specmatic.core.wsdl.SOAPVersion
import io.specmatic.core.wsdl.parser.message.*
import io.specmatic.core.wsdl.parser.operation.SOAPOperationTypeInfo
import io.specmatic.core.wsdl.payload.EmptyHTTPBodyPayload
import io.specmatic.core.wsdl.payload.EmptySOAPPayload
import io.specmatic.core.wsdl.payload.RequestHeaders
import io.specmatic.core.wsdl.payload.SoapPayloadType
import java.net.URI

const val TYPE_NODE_NAME = "SPECMATIC_TYPE"
private const val XML_SCHEMA_NAMESPACE = "http://www.w3.org/2001/XMLSchema"

private data class QualifiedMessageName(
    val qualification: NamespaceQualification?,
    val nodeName: String,
)

private data class SOAPHeadersInfo(
    val headers: RequestHeaders,
    val types: Map<String, Pattern>,
)

private data class SOAPHeaderAccumulator(
    val headers: List<RequestHeaders.Header>,
    val types: Map<String, Pattern>,
)

private data class SOAPHeaderInfo(
    val header: RequestHeaders.Header,
    val types: Map<String, Pattern>,
)

class SOAP11Parser(private val wsdl: WSDL): SOAPParser {
    override fun convertToGherkin(url: String): String {
        val operationsTypeInfo = operationsTypeInfo(url)

        val featureName = wsdl.getServiceName()

        val featureHeading = "Feature: $featureName"

        val indent = "    "
        val gherkinScenarios = operationsTypeInfo.flatMap { operationTypeInfo ->
            operationTypeInfo.expandedVariants().map { it.toGherkinScenario(indent, indent) }
        }

        return listOf(featureHeading).plus(gherkinScenarios).joinToString("\n\n")
    }

    override fun toScenarioInfos(url: String, specmaticConfig: SpecmaticConfig): List<ScenarioInfo> {
        return operationsTypeInfo(url).map {
            it.toScenarioInfo(preferEscapedSoapAction = specmaticConfig.getEscapeSoapAction())
        }
    }

    private fun operationsTypeInfo(url: String): List<SOAPOperationTypeInfo> {
        val portType = wsdl.getPortType()
        val soapVersion = wsdl.getSOAPVersion()

        return wsdl.operations.map {
            parseOperation(it, url, wsdl, portType, soapVersion)
        }
    }

    private fun headerRequired(
        portType: XMLNode,
        operationName: String,
        soapMessageType: SOAPMessageType,
        bindingPartName: String
    ): NodeOccurrence? {
        val portOperation = portType.findByNodeNameAndAttributeOrNull("operation", "name", operationName) ?: return null
        val portMessage = portOperation.findFirstChildByName(soapMessageType.messageTypeName) ?: return null
        val portHeader = portMessage.findByNodeNameAndAttributeOrNull("header", "part", bindingPartName) ?: return null
        return when (portHeader.attributes["required"]?.toStringLiteral()) {
            "true" -> NodeOccurrence.Once
            "false" -> NodeOccurrence.Optional
            else -> null
        }
    }

    private fun parseOperation(
        bindingOperationNode: XMLNode,
        url: String,
        wsdl: WSDL,
        portType: XMLNode,
        soapVersion: SOAPVersion
    ): SOAPOperationTypeInfo {
        val operationName = bindingOperationNode.getAttributeValue("name")

        val soapAction = bindingOperationNode.getAttributeValueAtPath("operation", "soapAction")

        val portOperationNode = portType.findNodeByNameAttribute(operationName)

        val requestTypeInfo = parsePayloadTypes(
            portOperationNode,
            operationName,
            SOAPMessageType.Input,
            wsdl,
            emptyMap()
        )

        val responseTypeInfo = parsePayloadTypes(
            portOperationNode,
            operationName,
            SOAPMessageType.Output,
            wsdl,
            requestTypeInfo.types
        )

        val path = URI(url).path

        val requestHeadersInfo = parseSoapHeaders(
            bindingOperationNode = bindingOperationNode,
            portType = portType,
            operationName = operationName,
            soapMessageType = SOAPMessageType.Input,
            existingTypes = responseTypeInfo.types
        )

        val responseHeadersInfo = parseSoapHeaders(
            bindingOperationNode = bindingOperationNode,
            portType = portType,
            operationName = operationName,
            soapMessageType = SOAPMessageType.Output,
            existingTypes = requestHeadersInfo.types
        )

        return SOAPOperationTypeInfo(
            path = path,
            operationName = operationName,
            soapAction = soapAction,
            soapVersion = soapVersion,
            types = responseHeadersInfo.types.mapKeys { it.key.trim() },
            requestPayload = requestTypeInfo.soapPayload,
            requestHeaders = requestHeadersInfo.headers,
            responsePayload = responseTypeInfo.soapPayload,
            responseHeaders = responseHeadersInfo.headers
        )
    }

    private fun parseSoapHeaders(
        bindingOperationNode: XMLNode,
        portType: XMLNode,
        operationName: String,
        soapMessageType: SOAPMessageType,
        existingTypes: Map<String, Pattern>
    ): SOAPHeadersInfo {
        val soapHeaders = bindingOperationNode
            .findFirstChildByName(soapMessageType.messageTypeName)
            ?.findChildrenByName("header")
            .orEmpty()

        val parsedHeaders = soapHeaders.fold(SOAPHeaderAccumulator(emptyList(), existingTypes)) { accumulator, soapHeader ->
            val parsedHeader = parseSoapHeader(
                soapHeader = soapHeader,
                portType = portType,
                operationName = operationName,
                soapMessageType = soapMessageType,
                existingTypes = accumulator.types
            )

            accumulator.copy(
                headers = accumulator.headers.plus(parsedHeader.header),
                types = parsedHeader.types
            )
        }

        return SOAPHeadersInfo(
            headers = RequestHeaders.fromHeaders(parsedHeaders.headers),
            types = parsedHeaders.types
        )
    }

    private fun parseSoapHeader(
        soapHeader: XMLNode,
        portType: XMLNode,
        operationName: String,
        soapMessageType: SOAPMessageType,
        existingTypes: Map<String, Pattern>
    ): SOAPHeaderInfo {
        val bindingMessageRef = soapHeader.attributes["message"]?.toStringLiteral()
            ?: throw ContractException("SOAP header in operation $operationName does not have a message attribute.")
        val bindingPartName = soapHeader.attributes["part"]?.toStringLiteral()
            ?: throw ContractException("SOAP header in operation $operationName does not have a part attribute.")
        val bindingNamespace = soapHeader.attributes["namespace"]?.toStringLiteral()
            ?: soapHeader.resolveNamespace(bindingMessageRef)
        val bindingOccurrence = bindingOccurrence(soapHeader)
        val headerRequired = headerRequired(portType, operationName, soapMessageType, bindingPartName)
        val occurrence = bindingOccurrence ?: headerRequired ?: NodeOccurrence.Once

        val messageName = bindingMessageRef.substringAfter(":")
        val prefix = bindingMessageRef.substringBefore(":")
        val messageNode = wsdl.findMessageNode(FullyQualifiedName(prefix, bindingNamespace, messageName))
        val partNode = messageNode.findByNodeNameAndAttribute("part", "name", bindingPartName)

        return when {
            partNode.attributes.containsKey("element") -> parseElementReferencedHeader(
                partNode = partNode,
                bindingPartName = bindingPartName,
                soapMessageType = soapMessageType,
                operationName = operationName,
                existingTypes = existingTypes,
                occurrence = occurrence
            )
            partNode.attributes.containsKey("type") -> parseTypeReferencedHeader(
                partNode = partNode,
                bindingPartName = bindingPartName,
                occurrence = occurrence,
                existingTypes = existingTypes
            )
            else -> throw ContractException(
                "Part node $bindingPartName of SOAP header message $messageName should contain either an element attribute or a type attribute."
            )
        }
    }

    private fun bindingOccurrence(soapHeader: XMLNode): NodeOccurrence? {
        return soapHeader.attributes["minOccurs"]?.toStringLiteral().let {
            when (it) {
                "0" -> NodeOccurrence.Optional
                "1" -> NodeOccurrence.Once
                null -> null
                else -> {
                    if (it.toIntOrNull() != null) NodeOccurrence.Multiple else null
                }
            }
        }
    }

    private fun parseTypeReferencedHeader(
        partNode: XMLNode,
        bindingPartName: String,
        occurrence: NodeOccurrence,
        existingTypes: Map<String, Pattern>
    ): SOAPHeaderInfo {
        val type = elementTypeValue(partNode)
        val header = RequestHeaders.primitiveHeader(bindingPartName, type.toStringLiteral(), occurrence)
        return SOAPHeaderInfo(header = header, types = existingTypes)
    }

    private fun parseElementReferencedHeader(
        partNode: XMLNode,
        bindingPartName: String,
        soapMessageType: SOAPMessageType,
        operationName: String,
        existingTypes: Map<String, Pattern>,
        occurrence: NodeOccurrence
    ): SOAPHeaderInfo {
        val fullyQualifiedName = partNode.fullyQualifiedNameFromAttribute("element")
        val topLevelElement = wsdl.getSOAPElement(fullyQualifiedName)
        val specmaticTypeName =
            "${operationName.replace(":", "_")}_SOAPHeader_${soapMessageType.messageTypeName.capitalizeFirstChar()}_$bindingPartName"
        val typeInfo = topLevelElement.deriveSpecmaticTypes(specmaticTypeName, existingTypes, emptySet())
            .withWSDLTypeLookupIndex(fullyQualifiedName.namespace)
        val headerNode = RequestHeaders.withOccurrence(typeInfo.nodes.first() as XMLNode, occurrence)
        val namespaces = wsdl.getNamespaces(typeInfo)

        return SOAPHeaderInfo(
            header = RequestHeaders.Header(headerNode, namespaces),
            types = typeInfo.types
        )
    }

    private fun parsePayloadTypes(
        portOperationNode: XMLNode,
        operationName: String,
        soapMessageType: SOAPMessageType,
        wsdl: WSDL,
        existingTypes: Map<String, Pattern>
    ): SoapPayloadType {
        val messageTypeNode = portOperationNode.findFirstChildByName(soapMessageType.messageTypeName)
            ?: return SoapPayloadType(existingTypes, EmptyHTTPBodyPayload())

        val fullyQualifiedMessageName = messageTypeNode.fullyQualifiedNameFromAttribute("message")
        val messageNode = wsdl.findMessageNode(fullyQualifiedMessageName)
        val partNode = messageNode.firstNode()
            ?: return SoapPayloadType(existingTypes, EmptySOAPPayload(soapMessageType))

        return when {
            partNode.attributes.containsKey("element") -> {
                val fullyQualifiedTypeName = partNode.fullyQualifiedNameFromAttribute("element")
                parseElementReferencedPayload(fullyQualifiedTypeName, soapMessageType, existingTypes, operationName)
            }

            partNode.attributes.containsKey("type") -> {
                val fullyQualifiedTypeName = partNode.fullyQualifiedNameFromAttribute("type")
                val partName = partNode.getAttributeValue(
                    "name",
                    "Part node of message named ${fullyQualifiedMessageName.localName} does not have a name.",
                )
                parseTypeReferencedPayload(
                    fullyQualifiedMessageName = fullyQualifiedMessageName,
                    partName = partName,
                    fullyQualifiedTypeName = fullyQualifiedTypeName,
                    soapMessageType = soapMessageType,
                    existingTypes = existingTypes,
                    operationName = operationName,
                )
            }

            else -> throw ContractException(
                "Part node of message named ${fullyQualifiedMessageName.localName} should contain either an element attribute or a type attribute."
            )
        }
    }

    private fun parseElementReferencedPayload(
        fullyQualifiedName: FullyQualifiedName,
        soapMessageType: SOAPMessageType,
        existingTypes: Map<String, Pattern>,
        operationName: String,
    ): SoapPayloadType {
        val topLevelElement = wsdl.getSOAPElement(fullyQualifiedName)
        val specmaticTypeName = "${operationName.replace(":", "_")}_SOAPPayload_${soapMessageType.messageTypeName.capitalizeFirstChar()}"
        val typeInfo = topLevelElement.deriveSpecmaticTypes(specmaticTypeName, existingTypes, emptySet())
            .withWSDLTypeLookupIndex(fullyQualifiedName.namespace)
        val namespaces = wsdl.getNamespaces(typeInfo)
        val nodeNameForSOAPBody = (typeInfo.nodes.first() as XMLNode).realName
        val soapPayload = topLevelElement.getSOAPPayload(soapMessageType, nodeNameForSOAPBody, specmaticTypeName, namespaces, typeInfo)

        return SoapPayloadType(typeInfo.types, soapPayload)
    }

    private fun parseTypeReferencedPayload(
        fullyQualifiedMessageName: FullyQualifiedName,
        partName: String,
        fullyQualifiedTypeName: FullyQualifiedName,
        soapMessageType: SOAPMessageType,
        existingTypes: Map<String, Pattern>,
        operationName: String,
    ): SoapPayloadType {
        val qualifiedMessageName = qualifyMessageName(fullyQualifiedMessageName)
        val topLevelNode = xmlNode("element", mapOf("name" to qualifiedMessageName.nodeName)) {
            parentNamespaces(wsdl.allNamespaces())

            xmlNode("complexType") {
                xmlNode("sequence") {
                    xmlNode("element", mapOf("name" to partName, "type" to fullyQualifiedTypeName.qName))
                }
            }
        }

        val topLevelElement = ComplexElement(
            wsdlTypeReference = fullyQualifiedTypeName.qName,
            element = topLevelNode,
            wsdl = wsdl,
            namespaceQualification = qualifiedMessageName.qualification,
        )

        val specmaticTypeName = "${operationName.replace(":", "_")}${soapMessageType.messageTypeName.capitalizeFirstChar()} "
        val typeInfo = topLevelElement.deriveSpecmaticTypes(specmaticTypeName, existingTypes, emptySet())
            .withWSDLTypeLookupIndex(fullyQualifiedTypeName.namespace)
        val namespaces = wsdl.getNamespaces(typeInfo)
        val nodeNameForSOAPBody = (typeInfo.nodes.first() as XMLNode).realName
        val soapPayload = topLevelElement.getSOAPPayload(soapMessageType, nodeNameForSOAPBody, specmaticTypeName, namespaces, typeInfo)

        return SoapPayloadType(typeInfo.types, soapPayload)
    }

    private fun WSDLTypeInfo.withWSDLTypeLookupIndex(namespace: String): WSDLTypeInfo {
        val indexableTypes = wsdl.typeNodesNeededForWSDLTypeLookup(namespace)
        if (indexableTypes.isEmpty()) {
            return withoutWSDLTypeMetadata()
        }

        val types = indexableTypes.registerAsWSDLLookupTypes(namespace, this.types)
        return copy(types = types)
    }

    private fun WSDLTypeInfo.withoutWSDLTypeMetadata(): WSDLTypeInfo {
        return copy(
            members = members.map { it.withoutWSDLTypeMetadata() },
            types = types.filterValues { it.isReusableWSDLType() }
                .mapValues { (_, pattern) -> pattern.withoutWSDLTypeMetadata() },
            wsdlTypeNamespace = null,
            wsdlTypeName = null,
            wsdlBaseTypeNamespace = null,
            wsdlBaseTypeName = null,
        )
    }

    private fun Pattern.withoutWSDLTypeMetadata(): Pattern {
        return when (this) {
            is XMLPattern -> copy(pattern = pattern.copy(
                nodes = pattern.nodes.map { it.withoutWSDLTypeMetadata() },
                wsdlTypeNamespace = null,
                wsdlTypeName = null,
                wsdlBaseTypeNamespace = null,
                wsdlBaseTypeName = null,
            ))
            else -> this
        }
    }

    private fun Pattern.isReusableWSDLType(): Boolean {
        return when (this) {
            is XMLPattern -> pattern.name == TYPE_NODE_NAME
            else -> true
        }
    }

    private fun WSDL.typeNodesNeededForWSDLTypeLookup(namespace: String): List<XMLNode> {
        val namedTypes = namedTypeNodes(namespace)
        return namedTypes.takeIf { types -> types.any { it.hasActionableDerivation() } }.orEmpty()
    }

    private fun List<XMLNode>.registerAsWSDLLookupTypes(
        namespace: String,
        existingTypes: Map<String, Pattern>
    ): Map<String, Pattern> {
        return fold(existingTypes) { accumulatedTypes, typeNode ->
            val name = typeNode.getAttributeValue("name")
            val schemaTypeName = FullyQualifiedName(typeNode.prefixFor(namespace), namespace, name)
            val schemaSpecmaticTypeName = specmaticTypeName(schemaTypeName.qName)

            if (schemaSpecmaticTypeName in accumulatedTypes) {
                accumulatedTypes
            } else {
                val schemaElement = when (typeNode.name) {
                    "complexType" -> ComplexElement(schemaTypeName.qName, typeNode, wsdl)
                    "simpleType" -> SimpleElement(schemaTypeName.qName, typeNode, wsdl, simpleTypeNode = typeNode)
                    else -> throw ContractException("Named schema node ${typeNode.name} cannot be used as an xsi:type target")
                }

                val schemaTypeInfo = schemaElement.deriveSpecmaticTypes(
                    schemaSpecmaticTypeName,
                    accumulatedTypes,
                    accumulatedTypes.keys
                )

                accumulatedTypes.plus(schemaTypeInfo.types)
            }
        }
    }

    private fun XMLNode.prefixFor(namespace: String): String {
        return namespaces.entries.firstOrNull { it.value == namespace }?.key
            ?: wsdl.getSchemaNamespacePrefix(namespace)
    }

    private fun XMLNode.hasActionableDerivation(): Boolean {
        return derivationNode()?.hasBaseOutsideXMLSchemaNamespace() == true
    }

    private fun XMLNode.derivationNode(): XMLNode? {
        val directRestriction = findFirstChildByName("restriction")
        val complexContent = findFirstChildByName("complexContent")
        val simpleContent = findFirstChildByName("simpleContent")

        return directRestriction
            ?: complexContent?.findFirstChildByName("extension")
            ?: complexContent?.findFirstChildByName("restriction")
            ?: simpleContent?.findFirstChildByName("extension")
            ?: simpleContent?.findFirstChildByName("restriction")
    }

    private fun XMLNode.hasBaseOutsideXMLSchemaNamespace(): Boolean {
        val baseType = attributes["base"]?.toStringLiteral() ?: return false
        return resolveNamespace(baseType) != XML_SCHEMA_NAMESPACE
    }

    private fun qualifyMessageName(fullyQualifiedMessageName: FullyQualifiedName): QualifiedMessageName {
        return when {
            fullyQualifiedMessageName.prefix.isNotBlank() -> {
                val qualification = QualificationWithoutSchema(
                    namespacePrefix = listOf(wsdl.getSchemaNamespacePrefix(fullyQualifiedMessageName.namespace)),
                    nodeName = fullyQualifiedMessageName.qName,
                )
                QualifiedMessageName(qualification = qualification, nodeName = qualification.nodeName)
            }

            else -> QualifiedMessageName(qualification = null, nodeName = fullyQualifiedMessageName.localName)
        }
    }
}

fun hasSimpleTypeAttribute(element: XMLNode): Boolean = fromTypeAttribute(element)?.let { type -> isPrimitiveType(element) } == true
