package io.specmatic.core.wsdl.payload

import io.specmatic.core.pattern.Pattern

interface SOAPPayload {
    fun toPattern(headers: RequestHeaders): Pattern
}
