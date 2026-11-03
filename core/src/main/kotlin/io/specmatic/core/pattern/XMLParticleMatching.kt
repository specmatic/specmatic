package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.value.Value
import io.specmatic.core.value.XMLValue
import io.specmatic.core.value.XMLNode

internal fun xmlMatchCandidates(pattern: Pattern, values: List<Value>, resolver: Resolver): List<ConsumeResult<Value, Value>> =
    when (pattern) {
        is XMLParticlePattern -> pattern.matchCandidates(values, resolver)
        is XMLPattern -> pattern.matchCandidates(values, resolver)
        is XMLSequencePattern -> xmlSequenceCandidates(pattern.members, values, resolver)
        is XMLChoiceGroupPattern -> pattern.matchCandidates(values, resolver)
        is XMLWildcardPattern -> pattern.matchCandidates(values.filterIsInstance<XMLValue>(), resolver).map { it.cast("xml") }
        else -> listOf(pattern.matches(values, resolver))
    }

internal fun xmlSequenceCandidates(members: List<Pattern>, values: List<Value>, resolver: Resolver): List<ConsumeResult<Value, Value>> {
    var candidates = listOf(ConsumeResult<Value, Value>(Result.Success(), values))
    for (member in members) {
        val next = candidates.flatMap { previous ->
            xmlMatchCandidates(member, previous.remainder, resolver).map { matched ->
                val unchanged = matched.remainder.size == previous.remainder.size
                val earlierError = previous.provisionalError
                if (unchanged && !matched.result.isSuccess() && earlierError != null && earlierError.matchesElementName() &&
                    !matched.provisionalError.matchesElementName()) {
                    matched.copy(result = earlierError.result, provisionalError = earlierError)
                } else if (matched.provisionalError == null && unchanged)
                    matched.copy(provisionalError = previous.provisionalError) else matched
            }
        }
        val successes = next.filter { it.result.isSuccess() }
        if (successes.isEmpty()) return next
        candidates = successes.distinctBy { it.remainder.size }
    }
    return candidates.sortedBy { it.remainder.size }
}

/** Empty matches satisfy the lower bound without adding epsilon transitions to the work queue. */
internal fun xmlRepetitionCandidates(
    owner: Pattern,
    range: XMLOccurrenceRange,
    values: List<Value>,
    matchMember: (List<Value>) -> List<ConsumeResult<Value, Value>>
): List<ConsumeResult<Value, Value>> {
    if (range.isZero) return listOf(ConsumeResult(Result.Success(), values))
    val nullable = matchMember(emptyList()).any { it.result.isSuccess() }
    var offsets = setOf(0)
    var count = 0
    val accepted = mutableSetOf<Int>()
    val errors = mutableMapOf<Int, ProvisionalError<Value>>()
    var failure: Result? = null
    var failureDetail: ProvisionalError<Value>? = null
    fun rememberError(offset: Int, error: ProvisionalError<Value>) {
        val previous = errors[offset]
        if (previous == null || (error.matchesElementName() && !previous.matchesElementName())) errors[offset] = error
    }
    while (offsets.isNotEmpty()) {
        if (range.minimumReached(count) || nullable) accepted.addAll(offsets)
        if (range.maximumReached(count)) break
        val next = mutableSetOf<Int>()
        for (offset in offsets) {
            for (matched in matchMember(values.subList(offset, values.size))) {
                val end = values.size - matched.remainder.size
                if (matched.result.isSuccess()) {
                    if (end > offset) next.add(end)
                    matched.provisionalError?.let { rememberError(end, it) }
                } else {
                    val detail = matched.provisionalError ?: values.getOrNull(offset)?.let { value ->
                        ProvisionalError(matched.result as Result.Failure, owner, value)
                    }
                    if (failure == null || (detail.matchesElementName() && !failureDetail.matchesElementName())) {
                        failure = matched.result
                        failureDetail = detail
                    }
                    detail?.let { rememberError(offset, it) }
                }
            }
        }
        offsets = next
        count++
    }
    return accepted.sortedDescending().map { offset ->
        ConsumeResult<Value, Value>(Result.Success(), values.subList(offset, values.size), errors[offset])
    }.ifEmpty { listOf(ConsumeResult((failure ?: Result.Failure("Expected XML occurrence range $range")).breadCrumb("occurs $range"), values, failureDetail)) }
}

private fun ProvisionalError<Value>?.matchesElementName(): Boolean {
    val element = this?.type as? XMLPattern ?: return false
    val value = this.value as? XMLNode ?: return false
    return value.name.substringAfter(":") == element.pattern.name
}
