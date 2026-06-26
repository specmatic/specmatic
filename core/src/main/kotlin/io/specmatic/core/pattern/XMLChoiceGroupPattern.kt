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
        val matched = alternative.fold(ConsumeResult<Value, Value>(Success(), remaining)) { consumeResult, pattern ->
            when (consumeResult.result) {
                is Failure -> consumeResult
                else -> pattern.matches(consumeResult.remainder, resolver)
            }
        }

        return when (matched.result) {
            is Failure -> matched.copy(remainder = remaining)
            else -> matched
        }
    }

    override fun generate(resolver: Resolver): Value {
        val occurrences = concreteSequence ?: generateOccurrenceSequence(resolver)
        val generatedNodes = occurrences.flatMap { alternative ->
            alternative.flatMap { pattern ->
                if (pattern.hasXMLChoiceReferenceCycle(resolver)) {
                    return@flatMap emptyList()
                }

                val generated = when {
                    pattern is XMLPattern && pattern.hasTypeReference() -> pattern.generate(resolver)
                    else -> resolver.withCyclePrevention(
                        pattern.xmlChoiceCyclePreventionPattern(),
                        returnNullOnCycle = pattern.canReturnNullOnXMLChoiceCycle()
                    ) { cyclePreventedResolver ->
                        pattern.generate(cyclePreventedResolver)
                    } ?: return@flatMap emptyList()
                }

                when (generated) {
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

    private fun Pattern.xmlChoiceCyclePreventionPattern(): Pattern {
        val referredType = (this as? XMLPattern)?.referredType ?: return this
        return DeferredPattern(withPatternDelimiters(referredType))
    }

    private fun Pattern.hasXMLChoiceReferenceCycle(resolver: Resolver): Boolean {
        val cyclePreventionPattern = xmlChoiceCyclePreventionPattern()
        return cyclePreventionPattern != this && resolver.hasCycle(cyclePreventionPattern)
    }

    private fun Pattern.canReturnNullOnXMLChoiceCycle(): Boolean {
        return this is XMLPattern && (occurMultipleTimes() || pattern.getNodeOccurrence() == NodeOccurrence.Optional)
    }

    private fun XMLPattern.hasTypeReference(): Boolean = referredType != null

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> {
        if (concreteSequence != null) {
            return sequenceOf(HasValue(this))
        }

        return concreteSequences { alternative ->
            listCombinations(
                alternative.map { pattern ->
                    HasValue(pattern.newBasedOnXMLChoice(row, resolver))
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
            listCombinations(
                alternative.map { pattern ->
                    HasValue(pattern.newBasedOnXMLChoice(resolver))
                }
            ).map { it.value }
        }.map { copy(concreteSequence = it) }
    }

    private fun Pattern.newBasedOnXMLChoice(row: Row, resolver: Resolver): Sequence<Pattern?> {
        if (hasXMLChoiceReferenceCycle(resolver)) {
            return sequenceOf(null)
        }

        return resolver.withCyclePrevention(
            xmlChoiceCyclePreventionPattern(),
            returnNullOnCycle = canReturnNullOnXMLChoiceCycle()
        ) { cyclePreventedResolver ->
            newBasedOn(row, cyclePreventedResolver).map { it.value as Pattern? }
        } ?: sequenceOf(null)
    }

    private fun Pattern.newBasedOnXMLChoice(resolver: Resolver): Sequence<Pattern?> {
        if (hasXMLChoiceReferenceCycle(resolver)) {
            return sequenceOf(null)
        }

        return resolver.withCyclePrevention(
            xmlChoiceCyclePreventionPattern(),
            returnNullOnCycle = canReturnNullOnXMLChoiceCycle()
        ) { cyclePreventedResolver ->
            newBasedOn(cyclePreventedResolver).map { it as Pattern? }
        } ?: sequenceOf(null)
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
        val mySequences = limitedConcretePatternSequences(thisResolver)
        if (mySequences.isEmpty()) {
            return Failure("Choice group had no valid expansions")
        }

        val otherSequences = concretePatternSequencesFrom(otherPattern, otherResolver)

        val results = otherSequences.map { otherSequence ->
            mySequences.allows(otherSequence, otherPattern, thisResolver, otherResolver, typeStack)
        }

        return Result.fromResults(results)
    }

    private fun limitedConcretePatternSequences(resolver: Resolver): List<List<Pattern>> {
        return concretePatternSequences(resolver)
            .take(resolver.maxTestRequestCombinations)
            .toList()
    }

    private fun concretePatternSequencesFrom(pattern: Pattern, resolver: Resolver): List<List<Pattern>> {
        return when (val resolvedPattern = resolvedHop(pattern, resolver)) {
            is XMLChoiceGroupPattern -> resolvedPattern.limitedConcretePatternSequences(resolver)
            else -> listOf(listOf(resolvedPattern))
        }
    }

    private fun concretePatternSequences(resolver: Resolver): Sequence<List<Pattern>> {
        if (concreteSequence != null) {
            return sequenceOf(concreteSequence.flatten())
        }

        return newBasedOn(resolver)
            .filterIsInstance<XMLChoiceGroupPattern>()
            .mapNotNull { it.concreteSequence?.flatten() }
    }

    private fun List<List<Pattern>>.allows(
        otherSequence: List<Pattern>,
        otherPattern: Pattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        val successfulResult = asSequence()
            .map { mySequence ->
                sequenceAllows(mySequence, otherSequence, thisResolver, otherResolver, typeStack)
            }.find { it is Success }

        return successfulResult ?: Failure("Choice group does not encompass ${otherPattern.typeName}")
    }

    private fun sequenceAllows(
        mySequence: List<Pattern>,
        otherSequence: List<Pattern>,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        if (mySequence.size != otherSequence.size) {
            return Failure("Choice branch length ${mySequence.size} does not encompass ${otherSequence.size}")
        }

        val results = mySequence.mapIndexed { index, myPattern ->
            myPattern.encompasses(otherSequence[index], thisResolver, otherResolver, typeStack)
        }

        return Result.fromResults(results)
    }
}
