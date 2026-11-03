package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.wsdl.parser.message.OCCURS_ATTRIBUTE_NAME
import java.math.BigInteger

/** A once-only sequence adds no language boundary; a repeated or unordered group does. */
internal fun xmlCompatibilitySequence(members: List<Pattern>): List<Pattern> = members.flatMap { member ->
    when {
        member is XMLSequencePattern -> xmlCompatibilitySequence(member.members)
        member is XMLParticlePattern && member.occurrences.isZero -> emptyList()
        member is XMLParticlePattern && member.occurrences == XMLOccurrenceRange(1, 1) && member.member is XMLSequencePattern ->
            xmlCompatibilitySequence(member.member.members)
        else -> listOf(member)
    }
}

/** Compare the languages of differently structured groups without materializing their repetitions. */
internal fun xmlGroupEncompasses(
    bigger: Pattern,
    smaller: Pattern,
    thisResolver: Resolver,
    otherResolver: Resolver,
    typeStack: TypeStack
): Result = XMLGroupInclusion(thisResolver, otherResolver, typeStack).compare(bigger, smaller)

private sealed interface XMLContent {
    data object None : XMLContent
    data object Empty : XMLContent
    data class Element(val pattern: Pattern) : XMLContent
    data class Sequence(val members: List<XMLContent>) : XMLContent
    data class Choice(val alternatives: Set<XMLContent>) : XMLContent
    data class All(val members: List<XMLContent>) : XMLContent
    data class Repetition(val member: XMLContent, val range: XMLOccurrenceRange) : XMLContent
}

private class XMLGroupInclusion(
    private val thisResolver: Resolver,
    private val otherResolver: Resolver,
    private val typeStack: TypeStack
) {
    private data class ElementComparison(val encompassing: Pattern, val encompassed: Pattern)
    private data class ContentComparison(val encompassing: XMLContent, val encompassed: XMLContent)
    private data class ContentBounds(val minimum: BigInteger, val maximum: BigInteger?)

    private val elementComparisons = mutableMapOf<ElementComparison, Boolean>()

    fun compare(bigger: Pattern, smaller: Pattern): Result {
        val biggerContent = content(bigger)
        val smallerContent = content(smaller)
        if (smallerContent == XMLContent.None) return Result.Success()
        val biggerBounds = bounds(biggerContent)
        val smallerBounds = bounds(smallerContent)
        if (biggerBounds.minimum > smallerBounds.minimum ||
            (biggerBounds.maximum != null && (smallerBounds.maximum == null || biggerBounds.maximum < smallerBounds.maximum)))
            return Result.Failure("XML group occurrence bounds do not encompass the other group's content length")
        val pending = ArrayDeque<ContentComparison>()
        val visited = mutableSetOf<ContentComparison>()
        pending.add(ContentComparison(biggerContent, smallerContent))
        while (pending.isNotEmpty()) {
            if (Thread.currentThread().isInterrupted) throw ContractException("XML compatibility comparison interrupted")
            val comparison = pending.removeFirst()
            if (!visited.add(comparison)) continue
            val mine = comparison.encompassing
            val other = comparison.encompassed
            if (mine == other && allElements(mine).all { elementEncompasses(it, it) }) continue
            if (nullable(other) && !nullable(mine))
                return Result.Failure("XML group does not encompass empty content allowed by the other group")
            for (element in partitionedElements(firstElements(other), firstElements(mine))) {
                val myRemainder = derivative(mine, element, encompassing = true)
                if (myRemainder == XMLContent.None)
                    return Result.Failure("XML group does not encompass a child allowed by the other group: ${element.typeName}")
                pending.add(ContentComparison(myRemainder, derivative(other, element, encompassing = false)))
            }
        }
        return Result.Success()
    }

    private fun content(pattern: Pattern): XMLContent = when (pattern) {
        is XMLParticlePattern -> repetition(content(pattern.member), pattern.occurrences)
        is XMLSequencePattern -> sequence(pattern.members.map(::content))
        is XMLAllPattern -> all(pattern.members.map(::content))
        is XMLChoiceGroupPattern -> repetition(if (pattern.choices.isEmpty()) XMLContent.Empty else choice(pattern.choices.map { sequence(it.map(::content)) }), XMLOccurrenceRange(pattern.minOccurs, pattern.maxOccurs))
        is XMLWildcardPattern -> repetition(XMLContent.Element(pattern.copy(minOccurs = 1, maxOccurs = 1)), XMLOccurrenceRange(pattern.minOccurs, pattern.maxOccurs))
        is XMLPattern -> repetition(
            XMLContent.Element(pattern.copy(pattern = pattern.pattern.copy(attributes = pattern.pattern.attributes - OCCURS_ATTRIBUTE_NAME))),
            pattern.pattern.occurrenceRange()
        )
        is ListPattern -> repetition(content(pattern.pattern), XMLOccurrenceRange(0, null))
        is AnyPattern -> choice(pattern.pattern.map(::content))
        EmptyStringPattern -> XMLContent.Empty
        else -> XMLContent.Element(pattern)
    }

    private fun elementEncompasses(bigger: Pattern, smaller: Pattern): Boolean =
        elementComparisons.getOrPut(ElementComparison(bigger, smaller)) {
            bigger.encompasses(smaller, thisResolver, otherResolver, typeStack).isSuccess()
        }

    private fun allElements(content: XMLContent): Set<Pattern> = when (content) {
        XMLContent.None, XMLContent.Empty -> emptySet()
        is XMLContent.Element -> setOf(content.pattern)
        is XMLContent.Sequence -> content.members.flatMap(::allElements).toSet()
        is XMLContent.All -> content.members.flatMap(::allElements).toSet()
        is XMLContent.Choice -> content.alternatives.flatMap(::allElements).toSet()
        is XMLContent.Repetition -> allElements(content.member)
    }

    private fun partitionedElements(smaller: Set<Pattern>, bigger: Set<Pattern>): Set<Pattern> {
        val boundaries = (smaller + bigger).filterIsInstance<XMLWildcardPattern>().flatMap { wildcard ->
            when (val constraint = wildcard.namespaceConstraint) {
                AnyXMLNamespace -> emptySet()
                is XMLNamespaceList -> constraint.namespaces
                is XMLNotNamespaceList -> constraint.excludedNamespaces
            }
        }.toSet()
        return smaller.flatMap { element ->
            if (element !is XMLWildcardPattern) listOf(element)
            else {
                val cells = boundaries.filter { element.namespaceConstraint.allows(it) }
                    .map { XMLNamespaceList(setOf(it)) }.toMutableList<XMLNamespaceConstraint>()
                if (element.namespaceConstraint !is XMLNamespaceList)
                    cells.add(XMLNotNamespaceList(boundaries))
                cells.map { element.copy(namespaceConstraint = it) }
            }
        }.toSet()
    }

    private fun bounds(content: XMLContent): ContentBounds = when (content) {
        XMLContent.None, XMLContent.Empty -> ContentBounds(BigInteger.ZERO, BigInteger.ZERO)
        is XMLContent.Element -> ContentBounds(BigInteger.ONE, BigInteger.ONE)
        is XMLContent.Sequence -> combinedBounds(content.members)
        is XMLContent.All -> combinedBounds(content.members)
        is XMLContent.Choice -> {
            val alternatives = content.alternatives.map(::bounds)
            ContentBounds(alternatives.minOf { it.minimum }, if (alternatives.any { it.maximum == null }) null else alternatives.maxOf { it.maximum!! })
        }
        is XMLContent.Repetition -> {
            val member = bounds(content.member)
            ContentBounds(member.minimum * content.range.minimum,
                if (member.maximum == null || content.range.maximum == null) null else member.maximum * content.range.maximum)
        }
    }

    private fun combinedBounds(members: List<XMLContent>): ContentBounds {
        val bounds = members.map(::bounds)
        return ContentBounds(bounds.fold(BigInteger.ZERO) { sum, member -> sum + member.minimum },
            if (bounds.any { it.maximum == null }) null else bounds.fold(BigInteger.ZERO) { sum, member -> sum + member.maximum!! })
    }

    private fun nullable(content: XMLContent): Boolean = when (content) {
        XMLContent.None, is XMLContent.Element -> false
        XMLContent.Empty -> true
        is XMLContent.Sequence -> content.members.all(::nullable)
        is XMLContent.All -> content.members.all(::nullable)
        is XMLContent.Choice -> content.alternatives.any(::nullable)
        is XMLContent.Repetition -> content.range.allowsZero || nullable(content.member)
    }

    private fun firstElements(content: XMLContent): Set<Pattern> = when (content) {
        XMLContent.None, XMLContent.Empty -> emptySet()
        is XMLContent.Element -> setOf(content.pattern)
        is XMLContent.Choice -> content.alternatives.flatMap(::firstElements).toSet()
        is XMLContent.All -> content.members.flatMap(::firstElements).toSet()
        is XMLContent.Repetition -> firstElements(content.member)
        is XMLContent.Sequence -> buildSet {
            for (member in content.members) {
                addAll(firstElements(member))
                if (!nullable(member)) break
            }
        }
    }

    private fun derivative(content: XMLContent, element: Pattern, encompassing: Boolean): XMLContent = when (content) {
        XMLContent.None, XMLContent.Empty -> XMLContent.None
        is XMLContent.Element -> {
            val accepts = if (!encompassing) {
                content.pattern == element || (content.pattern is XMLWildcardPattern && element is XMLWildcardPattern &&
                    content.pattern.encompasses(element, otherResolver, otherResolver, typeStack).isSuccess())
            } else elementEncompasses(content.pattern, element)
            if (accepts) XMLContent.Empty else XMLContent.None
        }
        is XMLContent.Choice -> choice(content.alternatives.map { derivative(it, element, encompassing) })
        is XMLContent.Sequence -> {
            val alternatives = mutableListOf<XMLContent>()
            for ((index, member) in content.members.withIndex()) {
                alternatives.add(sequence(listOf(derivative(member, element, encompassing)) + content.members.drop(index + 1)))
                if (!nullable(member)) break
            }
            choice(alternatives)
        }
        is XMLContent.All -> choice(content.members.mapIndexed { index, member ->
            sequence(listOf(derivative(member, element, encompassing), all(content.members.filterIndexed { i, _ -> i != index })))
        })
        is XMLContent.Repetition -> sequence(listOf(
            derivative(content.member, element, encompassing),
            repetition(content.member, content.range.afterOne())
        ))
    }

    private data class RepeatedTerm(val member: XMLContent, val range: XMLOccurrenceRange)

    private fun repeatedTerm(content: XMLContent): RepeatedTerm = when (content) {
        is XMLContent.Repetition -> RepeatedTerm(content.member, content.range)
        else -> RepeatedTerm(content, XMLOccurrenceRange(1, 1))
    }

    private fun mergeAdjacentRepetitions(members: List<XMLContent>): List<XMLContent> {
        val merged = mutableListOf<XMLContent>()
        for (member in members) {
            val previous = merged.lastOrNull()?.let(::repeatedTerm)
            val current = repeatedTerm(member)
            if (previous != null && previous.member == current.member) {
                val maximum = if (previous.range.maximum == null || current.range.maximum == null) null
                    else previous.range.maximum + current.range.maximum
                merged[merged.lastIndex] = repetition(current.member, XMLOccurrenceRange(previous.range.minimum + current.range.minimum, maximum))
            } else merged.add(member)
        }
        return merged
    }

    private fun sequence(members: List<XMLContent>): XMLContent {
        if (XMLContent.None in members) return XMLContent.None
        val merged = mergeAdjacentRepetitions(members.filter { it != XMLContent.Empty })
        val flattened = mergeAdjacentRepetitions(merged.flatMap { if (it is XMLContent.Sequence) it.members else listOf(it) })
        if (flattened.isEmpty()) return XMLContent.Empty
        if (flattened.size == 1) return flattened.single()

        // Factor a repeated finite word before comparing counts, rather than expanding its repetitions.
        val prefixLengths = IntArray(flattened.size)
        for (index in 1 until flattened.size) {
            var length = prefixLengths[index - 1]
            while (length > 0 && flattened[index] != flattened[length]) length = prefixLengths[length - 1]
            if (flattened[index] == flattened[length]) length++
            prefixLengths[index] = length
        }
        val period = flattened.size - prefixLengths.last()
        if (period < flattened.size && flattened.size % period == 0) {
            val count = flattened.size / period
            return repetition(sequence(flattened.take(period)), XMLOccurrenceRange(count, count))
        }
        return XMLContent.Sequence(flattened)
    }

    private fun choice(alternatives: Collection<XMLContent>): XMLContent {
        val flattened = alternatives.flatMap { if (it is XMLContent.Choice) it.alternatives else listOf(it) }.filter { it != XMLContent.None }.toSet()
        return when (flattened.size) {
            0 -> XMLContent.None
            1 -> flattened.single()
            else -> XMLContent.Choice(flattened)
        }
    }

    private fun all(members: List<XMLContent>): XMLContent {
        val nonempty = members.filter { it != XMLContent.Empty }
        return when (nonempty.size) {
            0 -> XMLContent.Empty
            1 -> nonempty.single()
            else -> XMLContent.All(nonempty)
        }
    }

    private fun repetition(member: XMLContent, range: XMLOccurrenceRange): XMLContent {
        if (range.isZero || member == XMLContent.Empty) return XMLContent.Empty
        if (member == XMLContent.None) return if (range.allowsZero) XMLContent.Empty else XMLContent.None
        if (range == XMLOccurrenceRange(1, 1)) return member
        if (member is XMLContent.Repetition) {
            val inner = member.range
            val adjacentIntervalsOverlap = if (inner.maximum == null)
                range.minimum.signum() > 0 || inner.minimum <= BigInteger.ONE
            else range.minimum * (inner.maximum - inner.minimum) + BigInteger.ONE >= inner.minimum
            if (range.minimum == range.maximum || adjacentIntervalsOverlap) {
                val maximum = if (range.maximum == null || inner.maximum == null) null else range.maximum * inner.maximum
                return repetition(member.member, XMLOccurrenceRange(range.minimum * inner.minimum, maximum))
            }
        }
        return XMLContent.Repetition(member, if (nullable(member)) range.copy(minimum = BigInteger.ZERO) else range)
    }
}
