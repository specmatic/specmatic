package io.specmatic.core.wsdl.parser.operation

import io.specmatic.core.HttpHeadersPattern
import io.specmatic.core.HttpRequestPattern
import io.specmatic.core.HttpResponsePattern
import io.specmatic.core.ScenarioInfo
import io.specmatic.core.buildHttpPathPattern
import io.specmatic.core.pattern.AnyPattern
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.extractCombinedExtensions
import io.specmatic.core.pattern.withPatternDelimiters
import io.specmatic.core.value.StringValue
import io.specmatic.core.wsdl.SOAPVersion
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.SpecType
import io.specmatic.core.wsdl.payload.RequestHeaders
import io.specmatic.core.wsdl.payload.SOAPPayload

data class SOAPOperationTypeInfo(val operationName: String, val soapVersion: SOAPVersion, val request: SOAPRequest, val response: SOAPResponse, val types: SOAPTypes) {
    constructor(
        path: String,
        operationName: String,
        soapAction: String,
        soapVersion: SOAPVersion,
        types: Map<String, Pattern>,
        requestPayload: SOAPPayload,
        requestHeaders: RequestHeaders,
        responsePayload: SOAPPayload,
        responseHeaders: RequestHeaders = RequestHeaders()
    ) : this(
        operationName,
        soapVersion = soapVersion,
        request = SOAPRequest(path, operationName, soapAction, requestHeaders, requestPayload),
        response = SOAPResponse(responsePayload, responseHeaders),
        types = SOAPTypes(types)
    )

    fun expandedVariants(): List<SOAPOperationTypeInfo> {
        return types.expandedVariants().map { expandedTypes ->
            copy(types = expandedTypes)
        }
    }

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

    fun toScenarioInfo(
        protocol: SpecmaticProtocol = SpecmaticProtocol.SOAP,
        specType: SpecType = SpecType.WSDL,
        preferEscapedSoapAction: Boolean = false,
    ): ScenarioInfo {
        return ScenarioInfo(
            scenarioName = operationName,
            httpRequestPattern = HttpRequestPattern(
                headersPattern = HttpHeadersPattern(
                    pattern = soapActionHeaderPattern(request.soapAction),
                    preferEscapedSoapAction = preferEscapedSoapAction,
                    contentType = soapVersion.header(request.requestPayload)
                ),
                httpPathPattern = buildHttpPathPattern(request.path),
                method = "POST",
                body = request.requestPayload.toPattern(request.requestHeaders),
            ),
            httpResponsePattern = HttpResponsePattern(
                status = 200,
                headersPattern = HttpHeadersPattern(
                    contentType = soapVersion.header(response.responsePayload),
                ),
                body = response.responsePayload.toPattern(response.responseHeaders),
            ),
            patterns = types.types.mapKeys { (typeName, _) -> withPatternDelimiters(typeName) },
            isGherkinScenario = false,
            protocol = protocol,
            specType = specType,
        )
    }
}

private fun soapActionHeaderPattern(soapAction: String): Map<String, Pattern> {
    if (soapAction.isBlank()) return emptyMap()

    val exactValuePatterns = listOf(
        ExactValuePattern(StringValue("\"$soapAction\"")),
        ExactValuePattern(StringValue(soapAction)),
    )

    return mapOf(
        "SOAPAction" to AnyPattern(exactValuePatterns, extensions = exactValuePatterns.extractCombinedExtensions())
    )
}
