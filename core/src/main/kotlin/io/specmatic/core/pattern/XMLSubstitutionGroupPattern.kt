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
import io.specmatic.core.value.localName
import io.specmatic.core.value.toXMLNode

data class XMLSubstitutionGroupPattern(
    val headElementName: String,
    val candidates: List<Pattern>,
    val substitutionGroupMembers: List<WSDLSubstitutionGroupMember> = emptyList(),
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
        val actualNode = xmlValues.firstOrNull() as? XMLNode
        val invalidMember = invalidSubstitutionGroupMember(actualNode, resolver)
        if (invalidMember != null) {
            return ConsumeResult(invalidMember, xmlValues)
        }

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
        return generateXMLNodes(resolver, XMLGenerationState()).asContainer()
    }

    override fun generateXMLNodes(resolver: Resolver, state: XMLGenerationState): GeneratedNodes {
        val candidate = candidates.firstOrNull()
            ?: throw ContractException("Cannot generate XML for substitutionGroup $headElementName because it has no candidates.")
        return generateXMLNodesFrom(candidate, resolver, state)
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

    private fun invalidSubstitutionGroupMember(actualNode: XMLNode?, resolver: Resolver): Failure? {
        actualNode ?: return null
        val member = substitutionGroupMemberFor(actualNode) ?: return null

        if (member.headBlocksSubstitution) {
            return Failure("Invalid substitutionGroup member ${actualNode.realName}; substitution is blocked for $headElementName.")
        }

        if (member.isAbstract) {
            return Failure("Invalid substitutionGroup member ${actualNode.realName}; it is abstract and cannot be used as a concrete substitutionGroup member for $headElementName.")
        }

        val headTypeName = member.headTypeName ?: return null
        val candidate = candidateFor(actualNode) ?: return null
        if (candidate.usesBlockedDerivationToReach(headTypeName, member.headBlockedDerivationMethods, resolver)) {
            return Failure("Invalid substitutionGroup member ${actualNode.realName}; type ${member.typeName.displayNameForError()} uses a derivation blocked by $headElementName.")
        }

        return null
    }

    private fun substitutionGroupMemberFor(node: XMLNode): WSDLSubstitutionGroupMember? {
        val nodeTypeName = WSDLTypeName(node.elementNamespaceUriOrNull().orEmpty(), node.name)
        return substitutionGroupMembers.firstOrNull { member ->
            member.elementName == nodeTypeName || member.elementName.localName == node.name || member.elementName.localName == node.realName.localName()
        }
    }

    private fun substitutionGroupMismatch(actualValue: XMLValue? = null): Failure {
        val actual = when (actualValue) {
            is XMLNode -> actualValue.realName
            null -> "nothing"
            else -> actualValue.displayableType()
        }

        return Failure(
            "Expected one of the substitutionGroup members for $headElementName (${candidateDisplayNames()}), but got $actual."
        )
    }

    private fun xmlTypeMismatch(actualValue: Value?): Failure =
        Failure("Expected XML but got ${actualValue?.displayableType() ?: "nothing"}")

    private fun candidateDisplayNames(): String =
        candidateElementNames().joinToString(", ").ifBlank { "<none>" }

    private fun candidateElementNames(): Set<String> =
        candidates.flatMap(::elementDisplayNames).toSet()

    private data class CandidateMatch(
        val candidate: Pattern,
        val match: ConsumeResult<Value, Value>
    )

    private fun CandidateMatch.matchesElementName(node: XMLNode): Boolean {
        return elementNames(candidate).any { candidateName ->
            candidateName == node.name || candidateName == node.realName
        }
    }

    private fun candidateFor(node: XMLNode): Pattern? {
        return candidates.firstOrNull { candidate ->
            elementNames(candidate).any { candidateName ->
                candidateName == node.name || candidateName == node.realName
            }
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

    private fun elementDisplayNames(candidate: Pattern): List<String> {
        return when (candidate) {
            is XMLPattern -> listOf(candidate.pattern.realName)
            is AnyPattern -> candidate.pattern.flatMap(::elementDisplayNames)
            is XMLSequencePattern -> candidate.members.flatMap(::elementDisplayNames)
            else -> listOf(candidate.typeName)
        }
    }
}
