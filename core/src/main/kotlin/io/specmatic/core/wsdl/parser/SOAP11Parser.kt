package io.specmatic.core.wsdl.parser

import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.NodeOccurrence
import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.value.FullyQualifiedName
import io.specmatic.core.value.XMLNode
import io.specmatic.core.wsdl.parser.message.*
import io.specmatic.core.wsdl.parser.operation.SOAPOperationTypeInfo
import io.specmatic.core.wsdl.payload.RequestHeaders
import io.specmatic.core.wsdl.payload.SoapPayloadType
import java.net.URI

const val TYPE_NODE_NAME = "SPECMATIC_TYPE"

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
        var messageTypeInfoParser: MessageTypeInfoParser =
            MessageTypeInfoParserStart(wsdl, portOperationNode, soapMessageType, existingTypes, operationName)

        while (messageTypeInfoParser.soapPayloadType == null) {
            messageTypeInfoParser = messageTypeInfoParser.execute()
        }

        return messageTypeInfoParser.soapPayloadType
            ?: throw ContractException("Parsing of $operationName did not complete successfully.")
    }
}

fun hasSimpleTypeAttribute(element: XMLNode): Boolean = fromTypeAttribute(element)?.let { type -> isPrimitiveType(element) } == true
