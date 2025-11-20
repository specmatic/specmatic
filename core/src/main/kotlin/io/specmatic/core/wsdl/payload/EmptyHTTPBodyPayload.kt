package io.specmatic.core.wsdl.payload

class EmptyHTTPBodyPayload : SOAPPayload {
    override fun specmaticStatement(requestHeaders: RequestHeaders): List<String> {
        return emptyList()
    }
}