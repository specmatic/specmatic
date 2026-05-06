package io.specmatic.core.wsdl.payload

import io.specmatic.core.pattern.Pattern

interface SOAPPayload {
    fun specmaticStatement(headers: RequestHeaders): List<String>
    fun toPattern(headers: RequestHeaders): Pattern
}
