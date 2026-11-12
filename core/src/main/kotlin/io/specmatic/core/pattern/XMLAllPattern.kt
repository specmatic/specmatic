package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.pattern.fold.IndexedListPatternCase
import io.specmatic.core.pattern.fold.PatternVisitor
import io.specmatic.core.value.Value
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.fold.Item

/** XSD 1.0 all members occur at most once, in any order. */
data class XMLAllPattern(val members: List<Pattern>, override val typeAlias: String? = null) : Pattern, XMLChildGenerationPattern {
    override val typeName: String get() = "xml-all"
    override val pattern: Any get() = members

    override fun <C, R> accept(visitor: PatternVisitor<C, R>, context: C): R = visitor.indexedList(
        IndexedListPatternCase(this, context, members.mapIndexed { i, member -> Item(i, member) }) { items -> copy(members = items.map { it.value }) }
    )

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        val values = when (sampleData) {
            is XMLNode -> sampleData.childNodes
            null -> emptyList()
            else -> return Result.Failure("Expected XML all group, got ${sampleData.displayableType()}")
        }
        val result = matches(values, resolver)
        return if (result.result.isSuccess() && result.remainder.isNotEmpty())
            result.provisionalError?.result ?: Result.Failure("Unexpected XML all member") else result.result
    }

    override fun matches(sampleData: List<Value>, resolver: Resolver): ConsumeResult<Value, Value> {
        fun consume(remainingMembers: List<Pattern>, values: List<Value>): ConsumeResult<Value, Value> {
            if (remainingMembers.isEmpty()) return ConsumeResult(Result.Success(), values)
            val matches = remainingMembers.map { it.matches(values, resolver) }
            val consumed = matches.mapIndexedNotNull { index, matched ->
                if (matched.result.isSuccess() && matched.remainder.size < values.size)
                    consume(remainingMembers.filterIndexed { i, _ -> i != index }, matched.remainder) else null
            }
            consumed.filter { it.result.isSuccess() }.minByOrNull { it.remainder.size }?.let { return it }
            if (remainingMembers.all { it.matches(emptyList(), resolver).result.isSuccess() })
                return ConsumeResult(Result.Success(), values, matches.firstNotNullOfOrNull { it.provisionalError })
            val failure = consumed.firstOrNull { !it.result.isSuccess() } ?: matches.firstOrNull { !it.result.isSuccess() }
            return failure?.copy(remainder = values)
                ?: ConsumeResult(Result.Failure("Required XML all member missing or invalid"), values)
        }
        return consume(members, sampleData)
    }

    override fun generateXMLNodes(resolver: Resolver, state: XMLGenerationState): GeneratedNodes =
        XMLSequencePattern(members).generateXMLNodes(resolver, state)

    override fun generate(resolver: Resolver): Value = generateXMLNodes(resolver, XMLGenerationState()).asContainer()

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> =
        XMLSequencePattern(members).newBasedOn(row, resolver).map { variant ->
            variant.ifValue { copy(members = (it as XMLSequencePattern).members) }
        }

    override fun newBasedOn(resolver: Resolver): Sequence<Pattern> =
        XMLSequencePattern(members).newBasedOn(resolver).map { copy(members = (it as XMLSequencePattern).members) }

    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> =
        XMLSequencePattern(members).negativeBasedOn(row, resolver, config).map { variant ->
            variant.ifValue { copy(members = (it as XMLSequencePattern).members) }
        }

    override fun parse(value: String, resolver: Resolver): Value = XMLSequencePattern(members).parse(value, resolver)

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value = XMLSequencePattern(members).listOf(valueList, resolver)

    override fun encompasses(otherPattern: Pattern, thisResolver: Resolver, otherResolver: Resolver, typeStack: TypeStack): Result {
        val structural = encompassMembers(otherPattern, thisResolver, otherResolver, typeStack)
        return if (structural.isSuccess()) structural else xmlGroupEncompasses(this, otherPattern, thisResolver, otherResolver, typeStack)
    }

    private fun encompassMembers(otherPattern: Pattern, thisResolver: Resolver, otherResolver: Resolver, typeStack: TypeStack): Result {
        val otherMembers = when (otherPattern) {
            is XMLAllPattern -> otherPattern.members
            is XMLSequencePattern -> xmlCompatibilitySequence(otherPattern.members)
            else -> return Result.Failure("Expected XML all or sequence group")
        }
        val unused = otherMembers.toMutableList()
        for (member in members) {
            val index = unused.indexOfFirst { member.encompasses(it, thisResolver, otherResolver, typeStack).isSuccess() }
            if (index >= 0) unused.removeAt(index)
            else if ((member as? XMLParticlePattern)?.occurrences?.allowsZero != true) return Result.Failure("XML all groups have incompatible members")
        }
        return if (unused.isEmpty()) Result.Success() else Result.Failure("XML all group has unexpected members")
    }
}
