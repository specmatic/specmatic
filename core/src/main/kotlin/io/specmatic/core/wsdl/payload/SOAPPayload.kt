package io.specmatic.core.wsdl.payload

import io.specmatic.core.pattern.Pattern

interface SOAPPayload {
    fun specmaticStatement(requestHeaders: RequestHeaders): List<String>
    fun toPattern(requestHeaders: RequestHeaders): Pattern
}
