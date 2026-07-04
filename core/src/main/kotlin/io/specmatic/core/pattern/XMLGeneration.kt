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

data class GeneratedNodes(
    val nodes: List<XMLValue>,
    val nextState: XMLGenerationState
) {
    fun followedBy(other: GeneratedNodes): GeneratedNodes =
        copy(nodes = nodes.plus(other.nodes), nextState = other.nextState)

    fun asContainer(): XMLNode =
        XMLNode.container(nodes)

    fun asSingleXMLNode(): XMLNode =
        nodes.single() as XMLNode

    companion object {
        fun none(state: XMLGenerationState): GeneratedNodes =
            GeneratedNodes(emptyList(), state)

        fun fromGeneratedValue(value: Value, state: XMLGenerationState): GeneratedNodes =
            GeneratedNodes(listOf(toXMLValue(value)), state)

        fun fromGeneratedContainer(value: Value, state: XMLGenerationState): GeneratedNodes =
            GeneratedNodes((value as XMLNode).childNodes, state)

        fun fromGeneratedValues(values: List<Value>, state: XMLGenerationState): GeneratedNodes =
            GeneratedNodes(values.map(::toXMLValue), state)

        private fun toXMLValue(value: Value): XMLValue =
            when (value) {
                is XMLValue -> value
                else -> StringValue(value.toStringLiteral())
            }
    }
}

interface XMLGenerativePattern {
    fun generateXMLNodes(resolver: Resolver, state: XMLGenerationState): GeneratedNodes

    fun generateXML(resolver: Resolver, decisions: XMLGenerationDecisions): Value =
        generateXMLNodes(resolver, XMLGenerationState(decisions)).asContainer()
}

fun generateXMLNodesFrom(pattern: Pattern, resolver: Resolver, state: XMLGenerationState): GeneratedNodes =
    when (pattern) {
        is XMLGenerativePattern -> pattern.generateXMLNodes(resolver, state)
        else -> GeneratedNodes.fromGeneratedValue(pattern.generate(resolver), state)
    }
