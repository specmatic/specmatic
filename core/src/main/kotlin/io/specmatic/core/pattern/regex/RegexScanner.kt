package io.specmatic.core.pattern.regex

internal class RegexScanner {
    sealed interface Result {
        val tokens: List<RegexToken>

        data class Malformed(override val tokens: List<RegexToken>, val reason: String) : Result
        data class Scanned(override val tokens: List<RegexToken>, val groups: List<RegexGroup>) : Result {
            fun innermostGroup(span: RegexSpan): RegexGroup? = enclosingGroups(span).maxByOrNull { it.span.start }
            fun enclosingGroups(span: RegexSpan): List<RegexGroup> = groups.filter { it.body.contains(span) }
        }
    }

    fun scan(source: String): Result = matchGroups(lexicalTokens(source))

    private fun lexicalTokens(source: String): List<RegexToken> {
        val first = if (source.isEmpty()) null else tokenAt(source, 0)
        return generateSequence(first) { previous ->
            val next = previous.span.end
            if (next == source.length) null else tokenAt(source, next)
        }.toList()
    }

    private fun matchGroups(tokens: List<RegexToken>): Result {
        val groups = mutableListOf<RegexGroup>()
        val openings = ArrayDeque<RegexToken.GroupStart>()

        for (token in tokens) {
            when (token) {
                is RegexToken.GroupStart -> openings.addLast(token)
                is RegexToken.GroupEnd -> {
                    val opening = openings.removeLastOrNull()
                        ?: return Result.Malformed(tokens, "Unmatched closing group")
                    groups += RegexGroup.pair(opening, token)
                }
                else -> Unit
            }
        }

        if (openings.isNotEmpty()) return Result.Malformed(tokens, "Unclosed group")
        return Result.Scanned(tokens, groups.toList())
    }

    private fun tokenAt(source: String, start: Int): RegexToken {
        val character = source[start]
        if (character == '(') return groupOpening(source, start)
        if (character == '\\') return escapedToken(source, start)
        if (character == '[') return characterClass(source, start)

        val quantifier = QUANTIFIER.matchAt(source, start)
        if (quantifier != null) {
            return RegexToken.Quantifier(RegexSpan(start, quantifier.range.last + 1))
        }

        val span = RegexSpan(start, start + 1)
        return when (character) {
            ')' -> RegexToken.GroupEnd(span)
            '|' -> RegexToken.Alternation(span)
            '^', '$' -> RegexToken.Anchor(span)
            else -> RegexToken.Character(span)
        }
    }

    private fun escapedToken(source: String, start: Int): RegexToken {
        if (source.startsWith("\\Q", start)) {
            val closing = source.indexOf("\\E", start + 2)
            return RegexToken.QuotedLiteral(RegexSpan(start, if (closing < 0) source.length else closing + 2))
        }

        return RegexToken.Escape(RegexSpan(start, (start + 2).coerceAtMost(source.length)))
    }

    private fun characterClass(source: String, start: Int): RegexToken.CharacterClass {
        val match = requireNotNull(CHARACTER_CLASS.matchAt(source, start))
        val escapes = CLASS_ESCAPE.findAll(match.value).map { escape ->
            RegexToken.Escape(RegexSpan(start + escape.range.first, start + escape.range.last + 1))
        }.toList()

        return RegexToken.CharacterClass(RegexSpan(start, match.range.last + 1), escapes)
    }

    private fun groupOpening(source: String, start: Int): RegexToken.GroupStart {
        if (!source.startsWith("(?", start)) return RegexToken.GroupStart.Regular(RegexSpan(start, start + 1))
        val span = RegexSpan(start, (start + 3).coerceAtMost(source.length))
        return when (source.getOrNull(start + 2)) {
            ':' -> RegexToken.GroupStart.Regular(span)
            '=' -> RegexToken.GroupStart.Lookaround.Ahead(span, LookaroundPolarity.POSITIVE)
            '!' -> RegexToken.GroupStart.Lookaround.Ahead(span, LookaroundPolarity.NEGATIVE)
            '<' -> lookbehindOrNamedOpening(source, start)
            else -> RegexToken.GroupStart.Special(RegexSpan(start, start + 2))
        }
    }

    private fun lookbehindOrNamedOpening(source: String, start: Int): RegexToken.GroupStart {
        val span = RegexSpan(start, (start + 4).coerceAtMost(source.length))
        return when (source.getOrNull(start + 3)) {
            '=' -> RegexToken.GroupStart.Lookaround.Behind(span, LookaroundPolarity.POSITIVE)
            '!' -> RegexToken.GroupStart.Lookaround.Behind(span, LookaroundPolarity.NEGATIVE)
            else -> namedOpening(source, start)
        }
    }

    private fun namedOpening(source: String, start: Int): RegexToken.GroupStart {
        val closing = source.indexOf('>', start + 3)
        if (closing < 0) return RegexToken.GroupStart.Special(RegexSpan(start, start + 2))
        return RegexToken.GroupStart.Named(RegexSpan(start, closing + 1), source.substring(start + 3, closing))
    }

    private companion object {
        // Consume escaped pairs or non-delimiters. An absent closing ] is left for dk.brics to reject.
        val CHARACTER_CLASS = Regex("""\[(?:\\[\s\S]?|[^\\\]])*]?""")
        val QUANTIFIER = Regex("""(?:[?*+]|\{\d+(?:,\d*)?})\??""")
        val CLASS_ESCAPE = Regex("""\\[\s\S]?""")
    }
}
