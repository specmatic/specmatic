package io.specmatic.core.pattern.regex

internal data class RegexSpan(val start: Int, val end: Int) {
    fun text(source: String): String = source.substring(start, end)
    fun contains(other: RegexSpan): Boolean = start <= other.start && other.end <= end
}

internal enum class LookaroundPolarity { POSITIVE, NEGATIVE }
internal sealed interface RegexToken {
    val span: RegexSpan
    fun text(source: String): String = span.text(source)

    data class Escape(override val span: RegexSpan) : RegexToken
    data class Anchor(override val span: RegexSpan) : RegexToken
    data class GroupEnd(override val span: RegexSpan) : RegexToken
    data class Character(override val span: RegexSpan) : RegexToken
    data class Quantifier(override val span: RegexSpan) : RegexToken {
        fun permitsZero(source: String): Boolean {
            val text = text(source)
            return text.first() in "?*" || ZERO_MINIMUM.matches(text)
        }

        private companion object {
            val ZERO_MINIMUM = Regex("""\{0+(?:,\d*)?}\??""")
        }
    }
    data class Alternation(override val span: RegexSpan) : RegexToken
    data class QuotedLiteral(override val span: RegexSpan) : RegexToken
    data class CharacterClass(override val span: RegexSpan, val escapes: List<Escape>) : RegexToken

    sealed interface GroupStart : RegexToken {
        data class Regular(override val span: RegexSpan) : GroupStart
        data class Named(override val span: RegexSpan, val name: String) : GroupStart
        data class Special(override val span: RegexSpan) : GroupStart

        sealed interface Lookaround : GroupStart {
            val polarity: LookaroundPolarity
            data class Ahead(override val span: RegexSpan, override val polarity: LookaroundPolarity) : Lookaround
            data class Behind(override val span: RegexSpan, override val polarity: LookaroundPolarity) : Lookaround
        }
    }
}

internal sealed interface RegexGroup {
    val opening: RegexToken.GroupStart
    val closing: RegexToken.GroupEnd
    val span: RegexSpan get() = RegexSpan(opening.span.start, closing.span.end)
    val body: RegexSpan get() = RegexSpan(opening.span.end, closing.span.start)

    data class Regular(override val opening: RegexToken.GroupStart, override val closing: RegexToken.GroupEnd) : RegexGroup
    data class Lookaround(override val opening: RegexToken.GroupStart.Lookaround, override val closing: RegexToken.GroupEnd) : RegexGroup

    companion object {
        fun pair(opening: RegexToken.GroupStart, closing: RegexToken.GroupEnd): RegexGroup = when (opening) {
            is RegexToken.GroupStart.Lookaround -> Lookaround(opening, closing)
            else -> Regular(opening, closing)
        }
    }
}
