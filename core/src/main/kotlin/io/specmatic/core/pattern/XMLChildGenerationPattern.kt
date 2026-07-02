package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.XMLValue

sealed interface XMLChildGenerationPattern : Pattern {
    fun generateXMLChildValues(resolver: Resolver): List<XMLValue>

    fun generatedValueAsXMLChildValues(generated: Value): List<XMLValue> {
        return when (generated) {
            is XMLNode, is XMLValue -> listOf(generated)
            else -> listOf(StringValue(generated.toStringLiteral()))
        }
    }

    fun generatedContainerChildValues(resolver: Resolver): List<XMLValue> {
        return (generate(resolver) as XMLNode).childNodes
    }
}
