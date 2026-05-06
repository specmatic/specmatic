package io.specmatic.core.wsdl.payload

import io.specmatic.core.pattern.EmptyStringPattern
import io.specmatic.core.pattern.Pattern

class EmptyHTTPBodyPayload : SOAPPayload {
    override fun specmaticStatement(headers: RequestHeaders): List<String> {
        return emptyList()
    }

    override fun toPattern(headers: RequestHeaders): Pattern {
        return EmptyStringPattern
    }
}
