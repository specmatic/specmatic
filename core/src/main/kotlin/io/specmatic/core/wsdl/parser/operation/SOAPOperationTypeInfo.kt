package io.specmatic.core.wsdl.parser.operation

import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.value.XMLNode
import io.specmatic.core.wsdl.payload.RequestHeaders
import io.specmatic.core.wsdl.payload.SOAPPayload

data class SOAPOperationTypeInfo(val operationName: String, val request: SOAPRequest, val response: SOAPResponse, val types: SOAPTypes) {
    constructor(
        path: String,
        operationName: String,
        soapAction: String,
        types: Map<String, XMLPattern>,
        requestPayload: SOAPPayload,
        requestHeaders: RequestHeaders,
        responsePayload: SOAPPayload
    ) : this(operationName, SOAPRequest(path, operationName, soapAction, requestHeaders, requestPayload), SOAPResponse(responsePayload), SOAPTypes(types))

    fun toGherkinScenario(scenarioIndent: String = "", incrementalIndent: String = "  "): String {
        val titleStatement = listOf("Scenario: $operationName".prependIndent(scenarioIndent))

        val statementIndent = "$scenarioIndent$incrementalIndent"
        val bodyStatements =
            types.statements()
            .plus(request.statements())
            .plus(response.statements())
            .map { it.prependIndent(statementIndent) }

        return titleStatement.plus(bodyStatements).joinToString("\n")
    }
}
