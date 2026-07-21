package io.specmatic.core.wsdl.payload

import io.specmatic.core.pattern.EmptyStringPattern
import io.specmatic.core.pattern.Pattern

class EmptyHTTPBodyPayload : SOAPPayload {
    override fun toPattern(headers: RequestHeaders): Pattern {
        return EmptyStringPattern
    }
}
