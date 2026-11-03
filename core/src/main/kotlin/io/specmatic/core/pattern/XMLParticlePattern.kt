package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.pattern.fold.IndexedListPatternCase
import io.specmatic.core.pattern.fold.PatternVisitor
import io.specmatic.core.value.Value
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.fold.Item

/** An occurrence range applies to the complete member, including a multi-node group. */
data class XMLParticlePattern(
    val member: Pattern,
    val occurrences: XMLOccurrenceRange = XMLOccurrenceRange(1, 1)
) : Pattern, XMLChildGenerationPattern {
    override val typeAlias: String? get() = member.typeAlias
    override val pattern: Any get() = member
    override val typeName: String get() = "xml-particle"

    override fun <C, R> accept(visitor: PatternVisitor<C, R>, context: C): R = visitor.indexedList(
        IndexedListPatternCase(this, context, listOf(Item(0, member))) { items -> copy(member = items.single().value) }
    )

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        val values = when (sampleData) {
            null -> emptyList()
            is XMLNode -> sampleData.childNodes
            else -> return Result.Failure("Expected XML particle, got ${sampleData.displayableType()}")
        }
        val result = matches(values, resolver)
        return if (result.result.isSuccess() && result.remainder.isNotEmpty())
            Result.Failure("Unexpected XML values after occurrence range $occurrences") else result.result
    }

    override fun matches(sampleData: List<Value>, resolver: Resolver): ConsumeResult<Value, Value> =
        matchCandidates(sampleData, resolver).let { candidates ->
            candidates.firstOrNull { it.result.isSuccess() } ?: candidates.first()
        }

    internal fun matchCandidates(values: List<Value>, resolver: Resolver): List<ConsumeResult<Value, Value>> =
        xmlRepetitionCandidates(this, occurrences, values) { remaining ->
            if (member is XMLPattern) {
                val result = remaining.firstOrNull()?.let { member.matches(it, resolver) }
                    ?: Result.Failure("Required XML element ${member.pattern.name} is absent")
                val value = remaining.firstOrNull()
                val elementFailure: ProvisionalError<Value>? = if (result is Result.Failure && value is XMLNode && value.name.substringAfter(":") == member.pattern.name)
                    ProvisionalError(result, member, value) else null
                listOf(ConsumeResult(result, if (result.isSuccess()) remaining.subList(1, remaining.size) else remaining, elementFailure))
            } else xmlMatchCandidates(member, remaining, resolver)
        }

    override fun generate(resolver: Resolver): Value = generateXMLNodes(resolver, XMLGenerationState()).asContainer()

    private fun canOmitRecursiveMember(resolver: Resolver): Boolean {
        val reference = (member as? XMLPattern)?.referredType ?: return false
        return occurrences.allowsZero && resolver.hasCycle(DeferredPattern(withPatternDelimiters(reference)))
    }

    override fun generateXMLNodes(resolver: Resolver, state: XMLGenerationState): GeneratedNodes {
        if (emitsNoXMLNodes() || canOmitRecursiveMember(resolver)) return GeneratedNodes.none(state)
        return (0 until state.decisions.numberOfXMLNodesFor(occurrences)).fold(GeneratedNodes.none(state)) { generated, _ ->
            generated.followedBy(generateXMLNodesFrom(member, resolver, generated.nextState))
        }
    }

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> =
        if (emitsNoXMLNodes() || canOmitRecursiveMember(resolver)) sequenceOf(HasValue(XMLSequencePattern(emptyList()))) else member.newBasedOn(row, resolver).flatMap { variant ->
            occurrences.positiveCounts().asSequence().map { count -> variant.ifValue { copy(member = it, occurrences = XMLOccurrenceRange(count, count)) } }
        }

    override fun newBasedOn(resolver: Resolver): Sequence<Pattern> =
        if (emitsNoXMLNodes() || canOmitRecursiveMember(resolver)) sequenceOf(XMLSequencePattern(emptyList())) else member.newBasedOn(resolver).flatMap { variant ->
            occurrences.positiveCounts().asSequence().map { count -> copy(member = variant, occurrences = XMLOccurrenceRange(count, count)) }
        }

    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> =
        if (emitsNoXMLNodes()) emptySequence() else occurrences.negativeCounts().asSequence().map { count -> HasValue<Pattern>(copy(occurrences = XMLOccurrenceRange(count, count))) } +
            member.negativeBasedOn(row, resolver, config).map { negative -> negative.ifValue<Pattern> { copy(member = it) } }

    override fun parse(value: String, resolver: Resolver): Value = member.parse(value, resolver)

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value = member.listOf(valueList, resolver)

    override fun encompasses(otherPattern: Pattern, thisResolver: Resolver, otherResolver: Resolver, typeStack: TypeStack): Result {
        val other = otherPattern as? XMLParticlePattern ?: XMLParticlePattern(otherPattern)
        if (occurrences.encompasses(other.occurrences)) {
            if (other.occurrences.isZero) return Result.Success()
            // A sequence's container comparison is not a comparison with one emitted element.
            if (member::class == other.member::class) {
                val structural = member.encompasses(other.member, thisResolver, otherResolver, typeStack)
                if (structural.isSuccess()) return structural
            }
        }
        return xmlGroupEncompasses(this, other, thisResolver, otherResolver, typeStack)
    }
}

/** Empty groups and zero-occurrence declarations contribute no nodes, regardless of their bounds. */
internal fun Pattern.emitsNoXMLNodes(): Boolean = when (this) {
    is XMLParticlePattern -> occurrences.isZero || member.emitsNoXMLNodes()
    is XMLSequencePattern -> members.all { it.emitsNoXMLNodes() }
    is XMLAllPattern -> members.all { it.emitsNoXMLNodes() }
    is XMLChoiceGroupPattern -> maxOccurs == 0 || choices.all { branch -> branch.all { it.emitsNoXMLNodes() } }
    is XMLWildcardPattern -> maxOccurs == 0
    else -> false
}
