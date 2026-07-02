package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.Result.Failure
import io.specmatic.core.Result.Success
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.utilities.parseXML
import io.specmatic.core.value.Value
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.XMLValue
import io.specmatic.core.value.toXMLNode

data class XMLSubstitutionGroupPattern(
    val headElementName: String,
    val candidates: List<Pattern>,
    override val typeAlias: String? = null
) : Pattern, SequenceType, XMLChildGenerationPattern {
    override val pattern: Any
        get() = candidates

    override val typeName: String = "xml-substitution-group"

    override val memberList: MemberList
        get() = MemberList(candidates, null)

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        return when (sampleData) {
            is XMLNode -> matchCandidates(listOf(sampleData), resolver).result
            else -> xmlTypeMismatch(sampleData)
        }
    }

    override fun matches(sampleData: List<Value>, resolver: Resolver): ConsumeResult<Value, Value> {
        val xmlValues = sampleData.filterIsInstance<XMLValue>()
        if (xmlValues.size != sampleData.size) {
            return ConsumeResult(Failure("XMLSubstitutionGroupPattern can only match XML values"), sampleData)
        }

        return matchCandidates(xmlValues, resolver)
    }

    private fun matchCandidates(xmlValues: List<XMLValue>, resolver: Resolver): ConsumeResult<Value, Value> {
        val candidateMatches = candidates.map { candidate ->
            CandidateMatch(candidate, candidate.matches(xmlValues, resolver))
        }
        val successfulMatch = candidateMatches
            .map(CandidateMatch::match)
            .filter { it.result is Success }
            .minByOrNull { it.remainder.size }
        if (successfulMatch != null) {
            return successfulMatch
        }

        val actualNode = xmlValues.firstOrNull() as? XMLNode
        if (actualNode != null) {
            val matchingNameFailure = candidateMatches
                .firstOrNull { it.matchesElementName(actualNode) }
                ?.match

            if (matchingNameFailure != null) {
                return matchingNameFailure
            }
        }

        return ConsumeResult(substitutionGroupMismatch(actualNode), xmlValues)
    }

    override fun generate(resolver: Resolver): Value {
        val candidate = candidates.firstOrNull()
            ?: throw ContractException("Cannot generate XML for substitutionGroup $headElementName because it has no candidates.")
        return candidate.generate(resolver)
    }

    override fun generateXMLChildValues(resolver: Resolver): List<XMLValue> {
        return generatedValueAsXMLChildValues(generate(resolver))
    }

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> =
        candidates.asSequence().flatMap { it.newBasedOn(row, resolver) }

    override fun negativeBasedOn(
        row: Row,
        resolver: Resolver,
        config: NegativePatternConfiguration
    ): Sequence<ReturnValue<Pattern>> =
        candidates.asSequence()
            .flatMap { it.negativeBasedOn(row, resolver, config) }

    override fun newBasedOn(resolver: Resolver): Sequence<Pattern> =
        candidates.asSequence().flatMap { it.newBasedOn(resolver) }

    override fun parse(value: String, resolver: Resolver): Value {
        return toXMLNode(parseXML(value))
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
        val otherResolvedPattern = resolvedHop(otherPattern, otherResolver)
        return when (otherResolvedPattern) {
            is XMLSubstitutionGroupPattern -> Result.fromResults(
                otherResolvedPattern.candidates.map { otherCandidate ->
                    candidates.hasCandidateThatEncompasses(otherCandidate, thisResolver, otherResolver, typeStack)
                }
            )

            else -> candidates.hasCandidateThatEncompasses(otherResolvedPattern, thisResolver, otherResolver, typeStack)
        }
    }

    private fun List<Pattern>.hasCandidateThatEncompasses(
        otherCandidate: Pattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        return asSequence()
            .map { candidate -> candidate.encompasses(otherCandidate, thisResolver, otherResolver, typeStack) }
            .find { it is Success }
            ?: Failure("substitutionGroup $headElementName does not encompass ${otherCandidate.typeName}")
    }

    private fun substitutionGroupMismatch(actualValue: XMLValue? = null): Failure {
        val actual = when (actualValue) {
            is XMLNode -> actualValue.realName
            null -> "nothing"
            else -> actualValue.displayableType()
        }

        return Failure(
            "Expected one of the substitutionGroup members for $headElementName: ${candidateDisplayNames()}, but got $actual."
        )
    }

    private fun xmlTypeMismatch(actualValue: Value?): Failure =
        Failure("Expected XML but got ${actualValue?.displayableType() ?: "nothing"}")

    private fun candidateDisplayNames(): String =
        candidateElementNames().joinToString(", ").ifBlank { "<none>" }

    private fun candidateElementNames(): Set<String> =
        candidates.flatMap(::elementNames).toSet()

    private data class CandidateMatch(
        val candidate: Pattern,
        val match: ConsumeResult<Value, Value>
    )

    private fun CandidateMatch.matchesElementName(node: XMLNode): Boolean {
        return elementNames(candidate).any { candidateName ->
            candidateName == node.name || candidateName == node.realName
        }
    }

    private fun elementNames(candidate: Pattern): List<String> {
        return when (candidate) {
            is XMLPattern -> listOf(candidate.pattern.name, candidate.pattern.realName)
            is AnyPattern -> candidate.pattern.flatMap(::elementNames)
            is XMLSequencePattern -> candidate.members.flatMap(::elementNames)
            else -> listOf(candidate.typeName)
        }
    }
}
