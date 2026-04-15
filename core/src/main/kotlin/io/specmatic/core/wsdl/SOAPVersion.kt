package io.specmatic.core.wsdl

import io.specmatic.core.wsdl.payload.EmptyHTTPBodyPayload
import io.specmatic.core.wsdl.payload.SOAPPayload

enum class SOAPVersion {
    SOAP12 {
        override val contentType: String = "application/soap+xml"
    },

    SOAP11 {
        override val contentType: String = "text/xml"
    };

    abstract val contentType: String

    fun header(soapPayload: SOAPPayload): String? {
        return when (soapPayload) {
            !is EmptyHTTPBodyPayload -> contentType
            else -> null
        }
    }
}