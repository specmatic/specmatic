package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.XMLValue

data class XMLGenerationState(
    val decisions: XMLGenerationDecisions = RandomXMLGenerationDecisions,
    private val generatedOptionalTypeKeys: Set<String> = emptySet()
) {
    fun hasGeneratedOptionalType(typeKey: String?): Boolean =
        typeKey != null && generatedOptionalTypeKeys.contains(typeKey)

    fun withGeneratedOptionalType(typeKey: String?): XMLGenerationState =
        when (typeKey) {
            null -> this
            else -> copy(generatedOptionalTypeKeys = generatedOptionalTypeKeys.plus(typeKey))
        }
}

data class XMLGenerationResult(
    val value: Value,
    val state: XMLGenerationState
) {
    fun asGeneratedXMLValue(): XMLGeneratedNodes =
        XMLGeneratedNodes.fromValue(value, state)

    fun asGeneratedXMLChildNodes(): XMLGeneratedNodes =
        XMLGeneratedNodes((value as XMLNode).childNodes, state)
}

data class XMLGeneratedNodes(
    val nodes: List<XMLValue>,
    val state: XMLGenerationState
) {
    fun plus(other: XMLGeneratedNodes): XMLGeneratedNodes =
        copy(nodes = nodes.plus(other.nodes), state = other.state)

    companion object {
        fun fromValue(value: Value, state: XMLGenerationState): XMLGeneratedNodes =
            XMLGeneratedNodes(listOf(toXMLValue(value)), state)

        fun fromValues(values: List<Value>, state: XMLGenerationState): XMLGeneratedNodes =
            XMLGeneratedNodes(values.map(::toXMLValue), state)

        private fun toXMLValue(value: Value): XMLValue =
            when (value) {
                is XMLValue -> value
                else -> StringValue(value.toStringLiteral())
            }
    }
}

interface XMLGenerativePattern {
    fun generateXMLValue(resolver: Resolver, state: XMLGenerationState): XMLGenerationResult

    fun generate(resolver: Resolver, decisions: XMLGenerationDecisions): Value =
        generateXMLValue(resolver, XMLGenerationState(decisions)).value
}

fun generateXMLValueFor(pattern: Pattern, resolver: Resolver, state: XMLGenerationState): XMLGenerationResult =
    when (pattern) {
        is XMLGenerativePattern -> pattern.generateXMLValue(resolver, state)
        else -> XMLGenerationResult(pattern.generate(resolver), state)
    }
