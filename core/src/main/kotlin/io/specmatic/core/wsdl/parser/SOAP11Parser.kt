package io.specmatic.core.wsdl.parser

import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.NodeOccurrence
import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.value.FullyQualifiedName
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.xmlNode
import io.specmatic.core.utilities.capitalizeFirstChar
import io.specmatic.core.wsdl.parser.message.*
import io.specmatic.core.wsdl.parser.operation.SOAPOperationTypeInfo
import io.specmatic.core.wsdl.payload.EmptyHTTPBodyPayload
import io.specmatic.core.wsdl.payload.EmptySOAPPayload
import io.specmatic.core.wsdl.payload.RequestHeaders
import io.specmatic.core.wsdl.payload.SoapPayloadType
import java.net.URI

const val TYPE_NODE_NAME = "SPECMATIC_TYPE"

private data class QualifiedMessageName(
    val qualification: NamespaceQualification?,
    val nodeName: String,
)

class SOAP11Parser(private val wsdl: WSDL): SOAPParser {
    override fun convertToGherkin(url: String): String {
        val portType = wsdl.getPortType()

        val operationsTypeInfo = wsdl.operations.map {
            parseOperation(it, url, wsdl, portType)
        }

        val featureName = wsdl.getServiceName()

        val featureHeading = "Feature: $featureName"

        val indent = "    "
        val gherkinScenarios = operationsTypeInfo.map { it.toGherkinScenario(indent, indent) }

        return listOf(featureHeading).plus(gherkinScenarios).joinToString("\n\n")
    }

    private fun headerRequired(portType: XMLNode, operationName: String, bindingPartName: String): NodeOccurrence? {
        val portOperation = portType.findByNodeNameAndAttributeOrNull("operation", "name", operationName) ?: return null
        val portInput = portOperation.findFirstChildByName("input") ?: return null
        val portHeader = portInput.findByNodeNameAndAttributeOrNull("header", "part", bindingPartName) ?: return null
        return when (portHeader.attributes["required"]?.toStringLiteral()) {
            "true" -> NodeOccurrence.Once
            "false" -> NodeOccurrence.Optional
            else -> null
        }
    }

    private fun parseOperation(bindingOperationNode: XMLNode, url: String, wsdl: WSDL, portType: XMLNode): SOAPOperationTypeInfo {
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

        val soapHeaders = bindingOperationNode
            .findFirstChildByName("input")
            ?.findChildrenByName("header")
            .orEmpty()

        val requestHeaders: Map<String, RequestHeaders.HeaderDetails> =
            soapHeaders
                .mapNotNull { soapHeader ->
                    try {
                        val bindingMessageRef = soapHeader.attributes["message"]?.toStringLiteral() ?: return@mapNotNull null
                        val bindingPartName = soapHeader.attributes["part"]?.toStringLiteral() ?: return@mapNotNull null
                        val bindingNamespace = soapHeader.attributes["namespace"]?.toStringLiteral() ?: return@mapNotNull null
                        val bindingOccurrence = soapHeader.attributes["minOccurs"]?.toStringLiteral().let {
                            when (it) {
                                "0" -> NodeOccurrence.Optional
                                "1" -> NodeOccurrence.Once
                                null -> null
                                else -> {
                                    if (it.toIntOrNull() != null) NodeOccurrence.Multiple else null
                                }
                            }
                        }

                        val headerRequired = headerRequired(portType, operationName, bindingPartName)

                        val messageName = bindingMessageRef.substringAfter(":")
                        val prefix = bindingMessageRef.substringBefore(":")
                        val messageNode = wsdl.findMessageNode(FullyQualifiedName(prefix, bindingNamespace, messageName))

                        val partNode = messageNode.findByNodeNameAndAttribute("part", "name", bindingPartName)

                        val type = elementTypeValue(partNode)

                        val occurrence = bindingOccurrence ?: headerRequired ?: NodeOccurrence.Once

                        bindingPartName to RequestHeaders.HeaderDetails(type.toStringLiteral(), occurrence)
                    } catch (e: Throwable) {
                        throw e
                    }
                }.toMap()

        return SOAPOperationTypeInfo(
            path,
            operationName,
            soapAction,
            responseTypeInfo.types.mapKeys { it.key.trim() },
            requestTypeInfo.soapPayload,
            RequestHeaders(requestHeaders),
            responseTypeInfo.soapPayload
        )
    }

    private fun parsePayloadTypes(
        portOperationNode: XMLNode,
        operationName: String,
        soapMessageType: SOAPMessageType,
        wsdl: WSDL,
        existingTypes: Map<String, XMLPattern>
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
        existingTypes: Map<String, XMLPattern>,
        operationName: String,
    ): SoapPayloadType {
        val topLevelElement = wsdl.getSOAPElement(fullyQualifiedName)
        val specmaticTypeName = "${operationName.replace(":", "_")}_SOAPPayload_${soapMessageType.messageTypeName.capitalizeFirstChar()}"
        val typeInfo = topLevelElement.deriveSpecmaticTypes(specmaticTypeName, existingTypes, emptySet())
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
        existingTypes: Map<String, XMLPattern>,
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
        val namespaces = wsdl.getNamespaces(typeInfo)
        val nodeNameForSOAPBody = (typeInfo.nodes.first() as XMLNode).realName
        val soapPayload = topLevelElement.getSOAPPayload(soapMessageType, nodeNameForSOAPBody, specmaticTypeName, namespaces, typeInfo)

        return SoapPayloadType(typeInfo.types, soapPayload)
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
