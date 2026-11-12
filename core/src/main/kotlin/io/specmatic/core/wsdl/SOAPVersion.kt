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

    val envelopeNamespace: String
        get() = when (this) {
            SOAP11 -> "http://schemas.xmlsoap.org/soap/envelope/"
            SOAP12 -> "http://www.w3.org/2003/05/soap-envelope"
        }

    fun header(soapPayload: SOAPPayload): String? {
        return when (soapPayload) {
            !is EmptyHTTPBodyPayload -> contentType
            else -> null
        }
    }
}
