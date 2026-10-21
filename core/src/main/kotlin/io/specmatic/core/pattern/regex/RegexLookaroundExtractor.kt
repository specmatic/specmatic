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
    fun extract(source: String, groups: List<RegexGroup>): RegexLookaroundExtraction {
        val lookarounds = groups.filterIsInstance<RegexGroup.Lookaround>().sortedBy { it.span.start }
        val markers = ('\uE000'..'\uF8FF').filterNot { it in source }.take(lookarounds.size)
        if (markers.size != lookarounds.size) {
            return RegexLookaroundExtraction.Unsupported("No assertion markers available")
        }

        val assertions = lookarounds.zip(markers) { group, marker ->
            MarkedAssertion(
                marker = marker,
                span = group.span,
                lookaround = group.opening,
                body = group.body.text(source),
            )
        }

        return RegexLookaroundExtraction.Extracted(markedExpression(source, assertions), assertions)
    }

    private fun markedExpression(source: String, assertions: List<MarkedAssertion>): String = buildString {
        var position = 0

        assertions.forEach { assertion ->
            append(source, position, assertion.span.start)
            append(assertion.marker)
            position = assertion.span.end
        }

        append(source, position, source.length)
    }
}
