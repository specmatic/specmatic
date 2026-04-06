package io.specmatic.core.wsdl.payload

import io.specmatic.core.pattern.EmptyStringPattern
import io.specmatic.core.pattern.Pattern

class EmptyHTTPBodyPayload : SOAPPayload {
    override fun specmaticStatement(requestHeaders: RequestHeaders): List<String> {
        return emptyList()
    }

    override fun toPattern(requestHeaders: RequestHeaders): Pattern {
        return EmptyStringPattern
    }
}
