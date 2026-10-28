package io.specmatic.core.pattern.regex

internal class RegexLookaroundBranches {
    private data class SplitPoint(val body: RegexSpan, val separators: List<RegexToken.Alternation>)

    fun split(source: String, scanned: RegexScanner.Result.Scanned): List<String> {
        val point = splitPoint(source, scanned) ?: return listOf(source)
        return alternativeSpans(point).map { alternative ->
            source.replaceRange(point.body.start, point.body.end, alternative.text(source))
        }
    }

    // (a(?=b)b|c) - Split: one alternation group contains all conditional lookarounds
    // ((?=a)a|b)((?=c)c|d) - Don't split: lookarounds belong to independent sibling alternation groups
    private fun splitPoint(source: String, scanned: RegexScanner.Result.Scanned): SplitPoint? {
        val lookarounds = scanned.groups.filterIsInstance<RegexGroup.Lookaround>()
        if (lookarounds.isEmpty()) return null
        val alternativesByGroup = scanned.tokens.filterIsInstance<RegexToken.Alternation>().groupBy {
            scanned.innermostGroup(it.span)
        }

        // Top-level alternatives are always mutually exclusive.
        alternativesByGroup[null]?.let {
            return SplitPoint(RegexSpan(0, source.length), it)
        }

        val groupsWithAlternatives = alternativesByGroup.keys.filterIsInstance<RegexGroup.Regular>()
        val relevantAssertions = lookarounds.filter {
            isRelevantToSplit(source, it, groupsWithAlternatives, scanned)
        }


        if (relevantAssertions.isEmpty()) return null
        val group = outermostCommonGroup(groupsWithAlternatives, relevantAssertions) ?: return null
        return SplitPoint(group.body, alternativesByGroup.getValue(group))
    }

    private fun isRelevantToSplit(
        source: String,
        assertion: RegexGroup.Lookaround,
        groups: List<RegexGroup.Regular>,
        scanned: RegexScanner.Result.Scanned,
    ): Boolean {
        val insideAlternativeGroup = groups.any { it.body.contains(assertion.span) }
        val directlyOptional = scanned.quantifierAfter(assertion)?.permitsZero(source) == true
        return insideAlternativeGroup && !directlyOptional
    }

    private fun outermostCommonGroup(groups: List<RegexGroup.Regular>, assertions: List<RegexGroup.Lookaround>): RegexGroup.Regular? {
        return groups
            .filter { group -> assertions.all { group.body.contains(it.span) } }
            .minByOrNull { it.span.start }
    }

    private fun alternativeSpans(point: SplitPoint): List<RegexSpan> = buildList {
        var start = point.body.start
        point.separators.forEach { separator ->
            add(RegexSpan(start, separator.span.start))
            start = separator.span.end
        }
        add(RegexSpan(start, point.body.end))
    }
}
