package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.XMLValue

interface XMLGenerationDecisions {
    fun includeOptionalXMLNode(): Boolean

    fun includeRepeatedOptionalXMLType(): Boolean = false

    fun numberOfMultipleXMLNodes(): Int = 1

    fun numberOfXMLNodesFor(minOccurs: Int, maxOccurs: Int?): Int {
        val maximumOccurrences = maxOccurs ?: Int.MAX_VALUE
        val requiredOccurrences = minOccurs.coerceAtMost(maximumOccurrences)

        if (requiredOccurrences > 0) return requiredOccurrences
        if (maximumOccurrences == 0) return 0

        return if (includeOptionalXMLNode()) 1 else 0
    }

    fun chooseXMLChoiceBranch(choiceCount: Int): Int = kotlin.random.Random.nextInt(choiceCount)
}

object RandomXMLGenerationDecisions : XMLGenerationDecisions {
    override fun includeOptionalXMLNode(): Boolean = kotlin.random.Random.nextBoolean()

    override fun includeRepeatedOptionalXMLType(): Boolean = kotlin.random.Random.nextBoolean()
}

data class XMLGenerationState(
    val decisions: XMLGenerationDecisions = RandomXMLGenerationDecisions,
    private val optionalTypeKeysAlreadyGenerated: Set<String> = emptySet()
) {
    fun hasAlreadyGeneratedOptionalType(typeKey: String?): Boolean =
        typeKey != null && optionalTypeKeysAlreadyGenerated.contains(typeKey)

    fun shouldSkipRepeatedOptionalType(typeKey: String?): Boolean =
        hasAlreadyGeneratedOptionalType(typeKey) && !decisions.includeRepeatedOptionalXMLType()

    fun afterGeneratingOptionalType(typeKey: String?): XMLGenerationState =
        when (typeKey) {
            null -> this
            else -> copy(optionalTypeKeysAlreadyGenerated = optionalTypeKeysAlreadyGenerated.plus(typeKey))
        }
}

data class GeneratedXMLValue(
    val value: Value,
    val nextState: XMLGenerationState
) {
    fun asSingleGeneratedNode(): GeneratedXMLNodes =
        GeneratedXMLNodes.fromValue(value, nextState)

    fun asGeneratedChildNodes(): GeneratedXMLNodes =
        GeneratedXMLNodes((value as XMLNode).childNodes, nextState)
}

data class GeneratedXMLNodes(
    val nodes: List<XMLValue>,
    val nextState: XMLGenerationState
) {
    fun followedBy(other: GeneratedXMLNodes): GeneratedXMLNodes =
        copy(nodes = nodes.plus(other.nodes), nextState = other.nextState)

    companion object {
        fun none(state: XMLGenerationState): GeneratedXMLNodes =
            GeneratedXMLNodes(emptyList(), state)

        fun fromValue(value: Value, state: XMLGenerationState): GeneratedXMLNodes =
            GeneratedXMLNodes(listOf(toXMLValue(value)), state)

        fun fromValues(values: List<Value>, state: XMLGenerationState): GeneratedXMLNodes =
            GeneratedXMLNodes(values.map(::toXMLValue), state)

        private fun toXMLValue(value: Value): XMLValue =
            when (value) {
                is XMLValue -> value
                else -> StringValue(value.toStringLiteral())
            }
    }
}

interface XMLGenerativePattern {
    fun generateXMLValue(resolver: Resolver, state: XMLGenerationState): GeneratedXMLValue

    fun generate(resolver: Resolver, decisions: XMLGenerationDecisions): Value =
        generateXMLValue(resolver, XMLGenerationState(decisions)).value
}

fun generateXMLValueFrom(pattern: Pattern, resolver: Resolver, state: XMLGenerationState): GeneratedXMLValue =
    when (pattern) {
        is XMLGenerativePattern -> pattern.generateXMLValue(resolver, state)
        else -> GeneratedXMLValue(pattern.generate(resolver), state)
    }
