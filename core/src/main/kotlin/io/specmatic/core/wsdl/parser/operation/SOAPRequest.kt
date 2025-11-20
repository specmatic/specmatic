package io.specmatic.core.wsdl.parser.operation

import io.specmatic.core.wsdl.payload.RequestHeaders
import io.specmatic.core.wsdl.payload.SOAPPayload

data class SOAPRequest(
    val path: String,
    val operationName: String,
    val soapAction: String,
    val requestHeaders: RequestHeaders,
    val requestPayload: SOAPPayload
) {
    fun statements(): List<String> {
        val pathStatement = listOf("When POST $path")
        val soapActionHeaderStatement = when {
            soapAction.isNotBlank() -> listOf(
                """And enum SoapAction (string) values "$soapAction",$soapAction""",
                """And request-header SOAPAction (SoapAction)"""
            )
            else -> emptyList()
        }

        val requestBodyStatement = requestPayload.specmaticStatement(requestHeaders)
        return pathStatement.plus(soapActionHeaderStatement).plus(requestBodyStatement)
    }
}