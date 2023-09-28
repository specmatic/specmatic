package `in`.specmatic.core.wsdl.parser.operation

import `in`.specmatic.core.wsdl.payload.SOAPPayload

data class SOAPRequest(val path: String, val operationName: String, val soapAction: String, val requestPayload: SOAPPayload) {
    fun statements(): List<String> {
        val pathStatement = listOf("When POST $path")
        val soapActionHeaderStatement = when {
            soapAction.isNotBlank() -> listOf(
                """And enum SoapAction (string) values "$soapAction",$soapAction""",
                """And request-header SOAPAction (SoapAction)"""
            )
            else -> emptyList()
        }

        val requestBodyStatement = requestPayload.qontractStatement()
        return pathStatement.plus(soapActionHeaderStatement).plus(requestBodyStatement)
    }
}