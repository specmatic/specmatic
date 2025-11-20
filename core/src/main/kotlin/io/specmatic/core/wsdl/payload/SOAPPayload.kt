package io.specmatic.core.wsdl.payload

interface SOAPPayload {
    fun specmaticStatement(requestHeaders: RequestHeaders): List<String>
}
