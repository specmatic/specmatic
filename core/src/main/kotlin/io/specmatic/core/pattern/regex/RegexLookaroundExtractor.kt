package io.specmatic.core.pattern.regex

internal data class MarkedAssertion(
    val marker: Char,
    val body: String,
    val span: RegexSpan,
    val lookaround: RegexToken.GroupStart.Lookaround,
)

internal sealed interface RegexLookaroundExtraction {
    data class Unsupported(val reason: String) : RegexLookaroundExtraction
    data class Extracted(val expression: String, val assertions: List<MarkedAssertion>) : RegexLookaroundExtraction
}

internal class RegexLookaroundExtractor {
    private sealed interface Replacement {
        val span: RegexSpan
        data class Omitted(override val span: RegexSpan) : Replacement
        data class Required(val assertion: MarkedAssertion) : Replacement {
            override val span: RegexSpan get() = assertion.span
        }
    }

    fun extract(source: String, scanned: RegexScanner.Result.Scanned): RegexLookaroundExtraction {
        val lookarounds = scanned.groups.filterIsInstance<RegexGroup.Lookaround>().sortedBy { it.span.start }
        val markers = ('\uE000'..'\uF8FF').filterNot { it in source }.take(lookarounds.size)
        if (markers.size != lookarounds.size) {
            return RegexLookaroundExtraction.Unsupported("No assertion markers available")
        }

        val replacements = lookarounds.zip(markers) { group, marker ->
            replacement(source, group, scanned.quantifierAfter(group), marker)
        }
        val assertions = replacements.filterIsInstance<Replacement.Required>().map { it.assertion }
        return RegexLookaroundExtraction.Extracted(markedExpression(source, replacements), assertions)
    }

    private fun replacement(source: String, group: RegexGroup.Lookaround, quantifier: RegexToken.Quantifier?, marker: Char): Replacement {
        val span = RegexSpan(group.span.start, quantifier?.span?.end ?: group.span.end)
        // A zero minimum may skip the whole check; positive repetitions check the same position once.
        if (quantifier?.permitsZero(source) == true) return Replacement.Omitted(span)
        return Replacement.Required(MarkedAssertion(marker, group.body.text(source), span, group.opening))
    }

    private fun markedExpression(source: String, replacements: List<Replacement>): String = buildString {
        var position = 0

        replacements.forEach { replacement ->
            append(source, position, replacement.span.start)
            if (replacement is Replacement.Required) append(replacement.assertion.marker)
            position = replacement.span.end
        }

        append(source, position, source.length)
    }
}
