package io.specmatic.core.wsdl.parser.operation

import io.specmatic.core.wsdl.payload.RequestHeaders
import io.specmatic.core.wsdl.payload.SOAPPayload

data class SOAPRequest(
    val path: String,
    val operationName: String,
    val soapAction: String,
    val requestHeaders: RequestHeaders,
    val requestPayload: SOAPPayload
)
