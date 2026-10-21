package io.specmatic.core.pattern.regex

/** Splits assertion-dependent alternatives while preserving the complete consuming context. */
internal class RegexLookaroundBranches {
    fun split(source: String, scanned: RegexScanner.Result.Scanned): List<String> {
        val alternatives = scanned.tokens.filterIsInstance<RegexToken.Alternation>()
        val topLevel = alternatives.filter { scanned.innermostGroup(it.span) == null }
        if (topLevel.isNotEmpty()) return replaceBody(source, RegexSpan(0, source.length), topLevel)

        val assertions = scanned.groups.filterIsInstance<RegexGroup.Lookaround>()
        val owners = alternatives.mapNotNull { scanned.innermostGroup(it.span) }.filterIsInstance<RegexGroup.Regular>().distinct()
        val owner = owners.firstOrNull { group -> assertions.any { group.body.contains(it.span) } } ?: return listOf(source)
        val separators = alternatives.filter { scanned.innermostGroup(it.span) == owner }
        return replaceBody(source, owner.body, separators)
    }

    private fun replaceBody(source: String, body: RegexSpan, separators: List<RegexToken.Alternation>): List<String> {
        val prefix = source.substring(0, body.start)
        val suffix = source.substring(body.end)
        return buildList {
            var start = body.start
            for (separator in separators) {
                add(prefix + source.substring(start, separator.span.start) + suffix)
                start = separator.span.end
            }
            add(prefix + source.substring(start, body.end) + suffix)
        }
    }
}
