package io.specmatic.conversions

enum class SegmentType { TEXT, PLACEHOLDER }
data class TemplateSegment(val startIndex: Int, val endIndex: Int, val token: String, val type: SegmentType)

class TemplateTokenizer(private val regex: Regex) {
    fun tokenize(input: String): List<TemplateSegment> {
        val matches = regex.findAll(input).toList()
        val tokenization = matches.fold(Tokenization()) { state, match ->
            val tokens = literalBetween(input, state.cursor, match.range.first).plus(variableToken(match))
            state.append(tokens, match.range.last + 1)
        }

        return tokenization.tokens + trailingLiteral(input, tokenization.cursor)
    }

    private fun literalBetween(input: String, startIndex: Int, endExclusive: Int): List<TemplateSegment> {
        if (startIndex >= endExclusive) return emptyList()
        return listOf(literalToken(input, startIndex, endExclusive))
    }

    private fun trailingLiteral(input: String, cursor: Int): List<TemplateSegment> {
        if (cursor >= input.length) return emptyList()
        return listOf(literalToken(input, cursor, input.length))
    }

    private fun variableToken(match: MatchResult): TemplateSegment {
        val value = match.groups[1]?.value ?: match.value
        return TemplateSegment(startIndex = match.range.first, endIndex = match.range.last, type = SegmentType.PLACEHOLDER, token = value)
    }

    private fun literalToken(input: String, startIndex: Int, endExclusive: Int): TemplateSegment {
        val value = input.substring(startIndex, endExclusive)
        return TemplateSegment(startIndex = startIndex, endIndex = endExclusive - 1, type = SegmentType.TEXT, token = value)
    }

    private data class Tokenization(val tokens: List<TemplateSegment> = emptyList(), val cursor: Int = 0) {
        fun append(nextTokens: List<TemplateSegment>, nextCursor: Int): Tokenization {
            return Tokenization(tokens = tokens + nextTokens, cursor = nextCursor)
        }
    }

    companion object {
        val openApiPathRegex: Regex = Regex("\\{([^{}]+)}")
    }
}
