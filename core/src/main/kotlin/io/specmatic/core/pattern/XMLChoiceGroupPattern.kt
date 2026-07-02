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
    override val typeAlias: String? = null
) : Pattern, SequenceType, XMLChildGenerationPattern {
    override val pattern: Any
        get() = choices

    override val typeName: String = "xml-choice-group"

    override val memberList: MemberList
        get() = MemberList(
            choices.firstOrNull() ?: emptyList(),
            null
        )

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

        val maxReached = maxOccurs?.let { matchedCount >= it } ?: false
        if (maxReached) {
            return results
        }

        choices.forEach { alternative ->
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
        return XMLNode("", "", emptyMap(), generateXMLChildValues(resolver), "", emptyMap())
    }

    override fun generateXMLChildValues(resolver: Resolver): List<XMLValue> {
        return generateOccurrenceSequence(resolver).flatMap { alternative ->
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
        return representativeMaterializedSequences { choiceBranch ->
            listCombinations(
                choiceBranch.map { pattern ->
                    HasValue(pattern.newBasedOnXMLChoice(row, resolver))
                }
            ).map { it.value }
        }.map { HasValue(materializedSequencePattern(it)) }
    }

    override fun negativeBasedOn(
        row: Row,
        resolver: Resolver,
        config: NegativePatternConfiguration
    ): Sequence<ReturnValue<Pattern>> = emptySequence()

    override fun newBasedOn(resolver: Resolver): Sequence<Pattern> {
        return representativeMaterializedSequences { choiceBranch ->
            listCombinations(
                choiceBranch.map { pattern ->
                    HasValue(pattern.newBasedOnXMLChoice(resolver))
                }
            ).map { it.value }
        }.map(::materializedSequencePattern)
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

    private fun materializedSequencePattern(selectedBranches: List<List<Pattern>>): Pattern =
        XMLSequencePattern(selectedBranches.flatten())

    private fun representativeMaterializedSequences(
        examplesForChoiceBranch: (List<Pattern>) -> Sequence<List<Pattern>>
    ): Sequence<List<List<Pattern>>> {
        return representativeOccurrenceCounts().asSequence().flatMap { occurrenceCount ->
            representativeChoiceSelections(occurrenceCount).asSequence().flatMap { selectedBranches ->
                materializedVariantsFor(selectedBranches, examplesForChoiceBranch)
            }
        }
    }

    private fun representativeOccurrenceCounts(): List<Int> {
        val upperBound = maxOccurs ?: max(minOccurs, 2)
        return listOfNotNull(
            0.takeIf { minOccurs == 0 },
            minOccurs.takeIf { minOccurs > 0 },
            upperBound.takeIf { upperBound > minOccurs }
        ).distinct()
    }

    private fun representativeChoiceSelections(occurrenceCount: Int): List<List<List<Pattern>>> {
        if (occurrenceCount == 0) {
            return listOf(emptyList())
        }

        if (choices.isEmpty()) {
            return emptyList()
        }

        return when {
            occurrenceCount <= choices.size -> combinationsOfChoiceBranches(choices, occurrenceCount)
            else -> listOf(0.until(occurrenceCount).map { index -> choices[index % choices.size] })
        }
    }

    private fun combinationsOfChoiceBranches(
        remainingChoices: List<List<Pattern>>,
        count: Int
    ): List<List<List<Pattern>>> {
        if (count == 0) {
            return listOf(emptyList())
        }

        return remainingChoices.flatMapIndexed { index, choice ->
            combinationsOfChoiceBranches(remainingChoices.drop(index + 1), count - 1).map { rest ->
                listOf(choice) + rest
            }
        }
    }

    private fun materializedVariantsFor(
        selectedBranches: List<List<Pattern>>,
        examplesForChoiceBranch: (List<Pattern>) -> Sequence<List<Pattern>>
    ): Sequence<List<List<Pattern>>> {
        if (selectedBranches.isEmpty()) {
            return sequenceOf(emptyList())
        }

        val firstBranchExamples = examplesForChoiceBranch(selectedBranches.first())
        val remainingBranchExamples = materializedVariantsFor(selectedBranches.drop(1), examplesForChoiceBranch)

        return firstBranchExamples.flatMap { firstBranchPatterns ->
            remainingBranchExamples.map { remainingBranchPatterns ->
                listOf(firstBranchPatterns) + remainingBranchPatterns
            }
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
        return encompassesResolved(
            resolvedHop(otherPattern, otherResolver),
            otherPattern,
            thisResolver,
            otherResolver,
            typeStack
        )
    }

    private fun encompassesResolved(
        otherResolvedPattern: Pattern,
        otherPattern: Pattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        return when (otherResolvedPattern) {
            is XMLChoiceGroupPattern -> Result.fromResults(
                listOf(occurrenceRange.encompassesRange(otherResolvedPattern.occurrenceRange)) +
                    choices.encompassesEveryChoiceBranchFrom(
                        otherResolvedPattern.choices,
                        otherPattern,
                        thisResolver,
                        otherResolver,
                        typeStack
                    )
            )

            is XMLSequencePattern -> encompassesMaterializedSequence(
                otherResolvedPattern.members,
                thisResolver,
                otherResolver,
                typeStack
            )

            else -> Result.fromResults(
                listOf(occurrenceRange.encompassesRange(exactOccurrenceRange(1))) +
                    listOf(
                        choices.hasBranchThatEncompasses(
                            listOf(otherResolvedPattern),
                            otherPattern,
                            thisResolver,
                            otherResolver,
                            typeStack
                        )
                    )
            )
        }
    }

    private val occurrenceRange: XMLOccurrenceRange
        get() = XMLOccurrenceRange(minOccurs, maxOccurs)

    private fun exactOccurrenceRange(count: Int): XMLOccurrenceRange = XMLOccurrenceRange(count, count)

    private fun XMLOccurrenceRange.encompassesRange(other: XMLOccurrenceRange): Result {
        return when {
            encompasses(other) -> Success()
            else -> Failure("Choice occurrence range $this does not encompass $other")
        }
    }

    private fun List<List<Pattern>>.encompassesEveryChoiceBranchFrom(
        otherChoices: List<List<Pattern>>,
        otherPattern: Pattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): List<Result> {
        return otherChoices.map { otherChoice ->
            hasBranchThatEncompasses(otherChoice, otherPattern, thisResolver, otherResolver, typeStack)
        }
    }

    private fun List<List<Pattern>>.hasBranchThatEncompasses(
        otherSequence: List<Pattern>,
        otherPattern: Pattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        val successfulResult = asSequence()
            .map { candidateBranch ->
                candidateBranch.encompassesWholeSequence(otherSequence, thisResolver, otherResolver, typeStack)
            }.find { it is Success }

        return successfulResult ?: Failure("Choice group does not encompass ${otherPattern.typeName}")
    }

    private fun encompassesMaterializedSequence(
        otherSequence: List<Pattern>,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        return choices.consumeMaterializedSequence(otherSequence, 0, thisResolver, otherResolver, typeStack)
            ?: Failure("Choice group does not encompass xml-sequence")
    }

    private fun List<List<Pattern>>.consumeMaterializedSequence(
        remaining: List<Pattern>,
        matchedCount: Int,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result? {
        if (remaining.isEmpty()) {
            return occurrenceRange.encompassesRange(exactOccurrenceRange(matchedCount))
        }

        val maxReached = maxOccurs?.let { matchedCount >= it } ?: false
        if (maxReached) {
            return null
        }

        val results = mapNotNull { choice ->
            val consumedChoice = choice.consumeEncompassedPrefix(
                remaining,
                thisResolver,
                otherResolver,
                typeStack
            )
            val consumed = remaining.size - consumedChoice.remainder.size

            when {
                consumedChoice.result is Success && consumed > 0 ->
                    consumeMaterializedSequence(
                        consumedChoice.remainder,
                        matchedCount + 1,
                        thisResolver,
                        otherResolver,
                        typeStack
                    )

                else -> null
            }
        }

        return results.find { it is Success } ?: results.firstOrNull()
    }

    private fun List<Pattern>.encompassesWholeSequence(
        otherSequence: List<Pattern>,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        val result = consumeEncompassedPrefix(otherSequence, thisResolver, otherResolver, typeStack)

        return when {
            result.result is Failure -> result.result
            result.remainder.isNotEmpty() -> Failure("Choice branch length $size does not encompass ${otherSequence.size}")
            else -> Success()
        }
    }

    private fun List<Pattern>.consumeEncompassedPrefix(
        otherSequence: List<Pattern>,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): ConsumeResult<Pattern, Pattern> {
        return fold(ConsumeResult<Pattern, Pattern>(Success(), otherSequence)) { consumeResult, myPattern ->
            when (consumeResult.result) {
                is Failure -> consumeResult
                is Success -> myPattern.encompasses(
                    consumeResult.remainder,
                    thisResolver,
                    otherResolver,
                    "Choice branch length $size does not encompass ${otherSequence.size}",
                    typeStack
                )
            }
        }
    }
}

data class XMLSequencePattern(
    val members: List<Pattern>,
    override val typeAlias: String? = null
) : Pattern, SequenceType, XMLChildGenerationPattern {
    override val pattern: Any
        get() = members

    override val typeName: String = "xml-sequence"

    override val memberList: MemberList
        get() = MemberList(members, null)

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        return when (sampleData) {
            is XMLNode -> matches(sampleData.childNodes, resolver).result
            null -> matches(emptyList(), resolver).result
            else -> Failure("Expected XML but got ${sampleData.displayableType()}")
        }
    }

    override fun matches(sampleData: List<Value>, resolver: Resolver): ConsumeResult<Value, Value> {
        return members.fold(ConsumeResult<Value, Value>(Success(), sampleData)) { consumeResult, pattern ->
            when (consumeResult.result) {
                is Failure -> consumeResult
                is Success -> pattern.matches(consumeResult.remainder, resolver)
            }
        }
    }

    override fun generate(resolver: Resolver): Value {
        return XMLNode("", "", emptyMap(), generateXMLChildValues(resolver), "", emptyMap())
    }

    override fun generateXMLChildValues(resolver: Resolver): List<XMLValue> {
        return members.flatMap { pattern ->
            when (pattern) {
                is XMLChildGenerationPattern -> pattern.generateXMLChildValues(resolver)
                else -> {
                    val generated = pattern.generate(resolver)

                    when (generated) {
                        is XMLNode -> listOf(generated)
                        is XMLValue -> listOf(generated)
                        else -> listOf(StringValue(generated.toStringLiteral()))
                    }
                }
            }
        }
    }

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> =
        sequenceOf(HasValue(this))

    override fun negativeBasedOn(
        row: Row,
        resolver: Resolver,
        config: NegativePatternConfiguration
    ): Sequence<ReturnValue<Pattern>> = emptySequence()

    override fun newBasedOn(resolver: Resolver): Sequence<Pattern> = sequenceOf(this)

    override fun parse(value: String, resolver: Resolver): Value {
        return XMLPattern("<sequence>$value</sequence>").parse(value, resolver)
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
        val otherSequence = when (val otherResolvedPattern = resolvedHop(otherPattern, otherResolver)) {
            is XMLSequencePattern -> otherResolvedPattern.members
            is SequenceType -> otherResolvedPattern.memberList.patternList()
            else -> listOf(otherResolvedPattern)
        }

        val consumedSequence = members.fold(ConsumeResult<Pattern, Pattern>(Success(), otherSequence)) { consumeResult, member ->
            when (consumeResult.result) {
                is Failure -> consumeResult
                is Success -> member.encompasses(
                    consumeResult.remainder,
                    thisResolver,
                    otherResolver,
                    "XML sequence length ${members.size} does not encompass ${otherSequence.size}",
                    typeStack
                )
            }
        }

        return when {
            consumedSequence.result is Failure -> consumedSequence.result
            consumedSequence.remainder.isNotEmpty() ->
                Failure("XML sequence length ${members.size} does not encompass ${otherSequence.size}")

            else -> Success()
        }
    }
}
