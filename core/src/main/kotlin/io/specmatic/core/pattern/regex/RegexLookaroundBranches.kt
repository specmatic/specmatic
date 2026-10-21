package io.specmatic.core.pattern.regex

internal class RegexLookaroundBranches {
    private data class SplitPoint(val body: RegexSpan, val separators: List<RegexToken.Alternation>)

    fun split(source: String, scanned: RegexScanner.Result.Scanned): List<String> {
        val point = splitPoint(source, scanned) ?: return listOf(source)
        return alternativeSpans(point).map { alternative ->
            source.replaceRange(
                endIndex = point.body.end,
                startIndex = point.body.start,
                replacement = alternative.text(source)
            )
        }
    }

    private fun splitPoint(source: String, scanned: RegexScanner.Result.Scanned): SplitPoint? {
        val alternativesByGroup = scanned.tokens.filterIsInstance<RegexToken.Alternation>().groupBy {
            scanned.innermostGroup(it.span)
        }

        val topLevel = alternativesByGroup[null]
        if (topLevel != null) {
            return SplitPoint(RegexSpan(0, source.length), topLevel)
        }

        val assertions = scanned.groups.filterIsInstance<RegexGroup.Lookaround>()
        val owners = alternativesByGroup.keys.filterIsInstance<RegexGroup.Regular>()
        val owner = owners.firstOrNull { group -> assertions.any { group.body.contains(it.span) } } ?: return null
        return SplitPoint(owner.body, alternativesByGroup.getValue(owner))
    }

    private fun alternativeSpans(point: SplitPoint): List<RegexSpan> {
        return buildList {
            var start = point.body.start
            point.separators.forEach { separator ->
                add(RegexSpan(start, separator.span.start))
                start = separator.span.end
            }

            add(RegexSpan(start, point.body.end))
        }
    }
}
