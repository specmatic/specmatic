package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.XMLValue

sealed interface XMLChildGenerationPattern : Pattern {
    fun generateXMLNodes(resolver: Resolver, state: XMLGenerationState): GeneratedNodes

    fun generateXML(resolver: Resolver, decisions: XMLGenerationDecisions): Value =
        generateXMLNodes(resolver, XMLGenerationState(decisions)).asContainer()

    fun generateXMLChildValues(resolver: Resolver): List<XMLValue> =
        generateXMLNodes(resolver, XMLGenerationState()).nodes

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
