package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.value.XMLValue

sealed interface XMLChildGenerationPattern : Pattern {
    fun generateXMLChildValues(resolver: Resolver): List<XMLValue>
}
