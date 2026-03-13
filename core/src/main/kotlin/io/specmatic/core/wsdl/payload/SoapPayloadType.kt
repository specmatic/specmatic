package io.specmatic.core.wsdl.payload

import io.specmatic.core.pattern.Pattern

data class SoapPayloadType(val types: Map<String, Pattern>, val soapPayload: SOAPPayload)
