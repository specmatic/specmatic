package io.specmatic.core.pattern.regex

internal class RegexLookaroundSupport(private val source: String, scanned: RegexScanner.Result.Scanned) {
    private val tokens = scanned.tokens
    private val assertions = scanned.groups.filterIsInstance<RegexGroup.Lookaround>()
    private val enclosingGroups = assertions.flatMap { scanned.enclosingGroups(it.span) }.toSet()
    private val alternativeOwners = tokens.filterIsInstance<RegexToken.Alternation>().map { scanned.innermostGroup(it.span) }.toSet()

    fun unsupportedReason(): String? {
        // Flag handling is outside scope: (?i)(?=a)a would need case-insensitive assertion evaluation.
        val specialGroups = tokens.filterIsInstance<RegexToken.GroupStart.Special>()
        if (specialGroups.any { source.getOrNull(it.span.end)?.isLetter() == true }) {
            return "Flag groups are outside flagless lookaround support"
        }

        return placementProblem()
    }

    private fun placementProblem(): String? {
        // A branch can omit a marker: (?=a)a|b.
        if (null in alternativeOwners) return "Top-level alternatives containing assertions are unsupported"
        return assertionPlacementProblem() ?: enclosingGroupProblem()
    }

    private fun assertionPlacementProblem(): String? {
        // Each assertion needs one marker; (?=a)? can omit it.
        if (assertions.any(::isOptionalOrRepeated)) {
            return "Only exact-once lookahead quantifiers are supported"
        }

        // Even (?<=a){1} is invalid ECMAScript; replacing it with a quantified marker would hide that.
        if (assertions.any { it.opening is RegexToken.GroupStart.Lookaround.Behind && quantifierAfter(it) != null }) {
            return "ECMAScript lookbehind cannot be quantified"
        }

        val anchors = tokens.filterIsInstance<RegexToken.Anchor>()
        // Body compilation has no input-boundary context: (?=a$).
        if (anchors.any { anchor -> assertions.any { it.body.contains(anchor.span) } }) {
            return "Anchors inside assertion bodies are unsupported"
        }

        return null
    }

    private fun enclosingGroupProblem(): String? {
        // Nested assertion evaluation is outside scope: (?=(?!a)b).
        if (enclosingGroups.any { it is RegexGroup.Lookaround }) return "Nested lookarounds are unsupported"
        // Containers can omit or repeat a marker: ((?=a)a)? or ((?=a)a)+.
        if (enclosingGroups.any(::isOptionalOrRepeated)) return "Assertions inside optional or repeated groups are unsupported"
        // An enclosing alternative can bypass the assertion: ((?=a)a|b).
        if (enclosingGroups.any { it in alternativeOwners }) return "Assertions inside alternative branches are unsupported"
        return null
    }

    private fun isOptionalOrRepeated(group: RegexGroup): Boolean {
        val quantifier = quantifierAfter(group) ?: return false
        // Count occurrences only: {1}? is still exact-once, regardless of inherited dk.brics interpretation.
        return quantifier.text(source).removeSuffix("?") !in listOf("{1}", "{1,1}")
    }

    private fun quantifierAfter(group: RegexGroup): RegexToken.Quantifier? {
        return tokens.firstOrNull { it.span.start == group.span.end } as? RegexToken.Quantifier
    }
}
