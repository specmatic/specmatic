package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.Result.Failure
import io.specmatic.core.Result.Success
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.XMLValue
import kotlin.math.max

data class XMLChoiceGroupPattern(
    val choices: List<List<Pattern>>,
    val minOccurs: Int = 1,
    val maxOccurs: Int? = 1,
    val concreteSequence: List<List<Pattern>>? = null,
    override val typeAlias: String? = null
) : Pattern, SequenceType {
    override val pattern: Any
        get() = concreteSequence ?: choices

    override val typeName: String = "xml-choice-group"

    override val memberList: MemberList
        get() = MemberList(concreteSequence?.flatten() ?: choices.firstOrNull() ?: emptyList(), null)

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        return when (sampleData) {
            is XMLNode -> matches(sampleData.childNodes, resolver).result
            null -> matches(emptyList(), resolver).result
            else -> Failure("Expected XML but got ${sampleData.displayableType()}")
        }
    }

    override fun matches(sampleData: List<Value>, resolver: Resolver): ConsumeResult<Value, Value> {
        val xmlValues = sampleData.filterIsInstance<XMLValue>()
        if (xmlValues.size != sampleData.size) {
            return ConsumeResult(Failure("XMLChoiceGroupPattern can only match XML values"), sampleData)
        }

        return matchOccurrences(xmlValues, resolver, 0).minByOrNull { it.remainder.size }
            ?: ConsumeResult(Failure("Choice group did not match"), sampleData)
    }

    private fun matchOccurrences(
        remaining: List<XMLValue>,
        resolver: Resolver,
        matchedCount: Int
    ): List<ConsumeResult<Value, Value>> {
        val results = mutableListOf<ConsumeResult<Value, Value>>()

        if (matchedCount >= minOccurs) {
            results += ConsumeResult(Success(), remaining)
        }

        val maxReached = concreteSequence?.let { matchedCount >= it.size } ?: maxOccurs?.let { matchedCount >= it } ?: false
        if (maxReached) {
            return results
        }

        val alternatives = concreteSequence?.let { listOf(it[matchedCount]) } ?: choices

        alternatives.forEach { alternative ->
            val matchedAlternative = matchAlternative(alternative, remaining, resolver)
            when (matchedAlternative.result) {
                is Success -> {
                    val consumed = remaining.size - matchedAlternative.remainder.size
                    if (consumed > 0) {
                        results += matchOccurrences(
                            matchedAlternative.remainder.filterIsInstance<XMLValue>(),
                            resolver,
                            matchedCount + 1
                        )
                    }
                }

                is Failure -> {
                    if (matchedCount < minOccurs && results.none { it.result is Success }) {
                        results += matchedAlternative
                    }
                }
            }
        }

        return results
    }

    private fun matchAlternative(
        alternative: List<Pattern>,
        remaining: List<XMLValue>,
        resolver: Resolver
    ): ConsumeResult<Value, Value> {
        return alternative.fold(ConsumeResult<Value, Value>(Success(), remaining)) { consumeResult, pattern ->
            when (consumeResult.result) {
                is Failure -> consumeResult
                else -> pattern.matches(consumeResult.remainder, resolver)
            }
        }
    }

    override fun generate(resolver: Resolver): Value {
        val occurrences = concreteSequence ?: generateOccurrenceSequence(resolver)
        val generatedNodes = occurrences.flatMap { alternative ->
            alternative.flatMap { pattern ->
                when (val generated = pattern.generate(resolver)) {
                    is XMLNode -> listOf(generated)
                    is XMLValue -> listOf(generated)
                    else -> listOf(StringValue(generated.toStringLiteral()))
                }
            }
        }

        return XMLNode("", "", emptyMap(), generatedNodes, "", emptyMap())
    }

    private fun generateOccurrenceSequence(resolver: Resolver): List<List<Pattern>> {
        val upperBound = maxOccurs ?: max(minOccurs, 2)
        val count = when {
            upperBound <= minOccurs -> minOccurs
            else -> (minOccurs..upperBound).random()
        }

        return 0.until(count).map { choices.random() }
    }

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> {
        if (concreteSequence != null) {
            return sequenceOf(HasValue(this))
        }

        return concreteSequences { alternative ->
            listCombinations(
                alternative.map { pattern ->
                    HasValue(
                        resolver.withCyclePrevention(pattern) { cyclePreventedResolver ->
                            pattern.newBasedOn(row, cyclePreventedResolver).map { it.value as Pattern? }
                        }
                    )
                }
            ).map { it.value }
        }.map { HasValue(copy(concreteSequence = it)) }
    }

    override fun negativeBasedOn(
        row: Row,
        resolver: Resolver,
        config: NegativePatternConfiguration
    ): Sequence<ReturnValue<Pattern>> = emptySequence()

    override fun newBasedOn(resolver: Resolver): Sequence<Pattern> {
        if (concreteSequence != null) {
            return sequenceOf(this)
        }

        return concreteSequences { alternative ->
            newBasedOn(alternative, resolver)
        }.map { copy(concreteSequence = it) }
    }

    private fun concreteSequences(
        expandAlternative: (List<Pattern>) -> Sequence<List<Pattern>>
    ): Sequence<List<List<Pattern>>> {
        val counts = occurrenceCounts()

        return counts.asSequence().flatMap { count ->
            branchSelections(count).asSequence().flatMap { selection ->
                expandSelections(selection, expandAlternative)
            }
        }
    }

    private fun occurrenceCounts(): List<Int> {
        val upperBound = maxOccurs ?: max(minOccurs, 2)
        return (minOccurs..upperBound).toList()
    }

    private fun branchSelections(count: Int): List<List<List<Pattern>>> {
        if (count == 0) {
            return listOf(emptyList())
        }

        return branchSelections(count - 1).flatMap { partial ->
            choices.map { choice -> partial + listOf(choice) }
        }
    }

    private fun expandSelections(
        selection: List<List<Pattern>>,
        expandAlternative: (List<Pattern>) -> Sequence<List<Pattern>>
    ): Sequence<List<List<Pattern>>> {
        if (selection.isEmpty()) {
            return sequenceOf(emptyList())
        }

        val head = expandAlternative(selection.first())
        val tail = expandSelections(selection.drop(1), expandAlternative)

        return head.flatMap { headPatterns ->
            tail.map { tailPatterns -> listOf(headPatterns) + tailPatterns }
        }
    }

    override fun parse(value: String, resolver: Resolver): Value {
        return XMLPattern("<choice>$value</choice>").parse(value, resolver)
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        return XMLNode("", "", emptyMap(), valueList.map { it as XMLValue }, "", emptyMap())
    }

    override fun encompasses(
        otherPattern: Pattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        val patterns = newBasedOn(thisResolver).take(thisResolver.maxTestRequestCombinations).toList()
        if (patterns.isEmpty()) {
            return Failure("Choice group had no valid expansions")
        }

        return patterns.asSequence().map { pattern ->
            pattern.encompasses(otherPattern, thisResolver, otherResolver, typeStack)
        }.find { it is Success } ?: Failure("Choice group does not encompass ${otherPattern.typeName}")
    }
}
