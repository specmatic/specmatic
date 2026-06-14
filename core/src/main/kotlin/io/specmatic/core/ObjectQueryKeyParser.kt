package io.specmatic.core

import io.specmatic.core.pattern.ContractException

object ObjectQueryKeyParser {
    fun parse(
        key: String,
        parameterName: String,
        schema: NestedQuerySchema.Object,
        syntax: ObjectQuerySyntax
    ): QueryObjectPath {
        return parseWithSchema(key, parameterName, schema, syntax)
    }

    internal fun parseWithSchema(
        key: String,
        parameterName: String,
        schema: NestedQuerySchema.Object,
        syntax: ObjectQuerySyntax
    ): QueryObjectPath {
        val rawTokens = QueryObjectKeyTokenizer.tokenize(key, parameterName, syntax.root)
        val parserState = rawTokens.fold(QueryObjectPathParserState(schema = schema, tokens = emptyList())) { state, token ->
            parseToken(token, state, syntax)
        }

        return QueryObjectPath(parserState.tokens)
    }

    private fun parseToken(
        token: RawQueryObjectPathToken,
        state: QueryObjectPathParserState,
        syntax: ObjectQuerySyntax
    ): QueryObjectPathParserState {
        return when (val schema = state.schema) {
            is NestedQuerySchema.Object -> parseObjectToken(token, schema, state, syntax)
            is NestedQuerySchema.Array -> parseArrayToken(token, schema, state)
            is NestedQuerySchema.Scalar -> throw ContractException("Unexpected nested query token ${token.value} after scalar query object value")
            is NestedQuerySchema.Ambiguous -> throw ContractException("Ambiguous query object schema at ${state.displayPath()}: ${schema.reason}")
        }
    }

    private fun parseObjectToken(
        token: RawQueryObjectPathToken,
        schema: NestedQuerySchema.Object,
        state: QueryObjectPathParserState,
        syntax: ObjectQuerySyntax
    ): QueryObjectPathParserState {
        validateObjectPropertyToken(token, state, syntax)

        val propertySchema = when {
            token.value in schema.properties -> schema.properties.getValue(token.value)
            schema.additionalProperties != null -> schema.additionalProperties
            schema.allowsAnyAdditionalProperties -> NestedQuerySchema.Scalar
            else -> throw ContractException("Unknown query object property \"${token.value}\" at ${state.displayPath()}")
        }

        return state.copy(
            schema = propertySchema,
            tokens = state.tokens + QueryObjectPathToken.Property(token.value)
        )
    }

    private fun validateObjectPropertyToken(
        token: RawQueryObjectPathToken,
        state: QueryObjectPathParserState,
        syntax: ObjectQuerySyntax
    ) {
        if (!token.isValidObjectProperty(syntax, isRootProperty = state.tokens.isEmpty())) {
            throw ContractException("Query object property ${token.value} does not match inferred ${syntax.propertyStyle.name.lowercase()} property syntax")
        }
    }

    private fun parseArrayToken(
        token: RawQueryObjectPathToken,
        schema: NestedQuerySchema.Array,
        state: QueryObjectPathParserState
    ): QueryObjectPathParserState {
        val index = token.arrayIndexOrNull()
        if (index == null || index < 0) {
            throw ContractException("Expected an array index at ${state.displayPath()}, but found ${token.value}")
        }

        return state.copy(
            schema = schema.itemSchema,
            tokens = state.tokens + QueryObjectPathToken.Index(index)
        )
    }
}

object ObjectQueryKeySerializer {
    fun serialize(path: QueryObjectPath, parameterName: String, syntax: ObjectQuerySyntax): String {
        return path.serialize(parameterName, syntax)
    }
}

private data class QueryObjectPathParserState(
    val schema: NestedQuerySchema,
    val tokens: List<QueryObjectPathToken>
) {
    fun displayPath(): String {
        if (tokens.isEmpty()) return "root"

        return tokens.joinToString(".") { token -> token.displaySegment() }
    }
}

private sealed class RawQueryObjectPathToken {
    abstract val value: String
    abstract fun isValidObjectProperty(syntax: ObjectQuerySyntax, isRootProperty: Boolean): Boolean
    open fun arrayIndexOrNull(): Int? = null

    data class BareProperty(override val value: String) : RawQueryObjectPathToken() {
        override fun isValidObjectProperty(syntax: ObjectQuerySyntax, isRootProperty: Boolean): Boolean {
            return syntax.root == ObjectQueryRoot.Unwrapped && isRootProperty
        }
    }

    data class Bracket(override val value: String) : RawQueryObjectPathToken() {
        override fun isValidObjectProperty(syntax: ObjectQuerySyntax, isRootProperty: Boolean): Boolean {
            val isBracketWrappedRootProperty = syntax.root == ObjectQueryRoot.ParameterNameWrapped && isRootProperty
            return syntax.propertyStyle == QueryPropertyStyle.Bracket || isBracketWrappedRootProperty
        }

        override fun arrayIndexOrNull(): Int? = value.toIntOrNull()
    }

    data class DotProperty(override val value: String) : RawQueryObjectPathToken() {
        override fun isValidObjectProperty(syntax: ObjectQuerySyntax, isRootProperty: Boolean): Boolean {
            val isBracketWrappedRootProperty = syntax.root == ObjectQueryRoot.ParameterNameWrapped && isRootProperty
            val isDotWrappedRootProperty = syntax.root == ObjectQueryRoot.ParameterNameDotWrapped && isRootProperty
            return (syntax.propertyStyle == QueryPropertyStyle.Dot && !isBracketWrappedRootProperty) || isDotWrappedRootProperty
        }
    }
}

private object QueryObjectKeyTokenizer {
    fun tokenize(key: String, parameterName: String, root: ObjectQueryRoot): List<RawQueryObjectPathToken> {
        return tokenizeRemainder(root.tokenizerRemainder(key, parameterName), root.allowBareStart)
    }

    private fun tokenizeRemainder(remainder: String, allowBareStart: Boolean): List<RawQueryObjectPathToken> {
        if (remainder.isEmpty()) throw ContractException("Query object key is empty")

        val bareStart = if (allowBareStart) readBareStart(remainder) else TokenReadResult.noToken(remainder)
        return readNextTokens(bareStart.remaining, listOfNotNull(bareStart.token))
    }

    private fun readBareStart(remainder: String): TokenReadResult {
        val end = nextSeparatorIndex(remainder, startIndex = 0)
        val tokenValue = remainder.substring(0, end)
        return if (tokenValue.isBlank()) {
            TokenReadResult.noToken(remainder)
        } else {
            TokenReadResult(RawQueryObjectPathToken.BareProperty(tokenValue), remainder.substring(end))
        }
    }

    private tailrec fun readNextTokens(
        remaining: String,
        tokens: List<RawQueryObjectPathToken>
    ): List<RawQueryObjectPathToken> {
        if (remaining.isEmpty()) return tokens

        val readResult = when (remaining.first()) {
            '[' -> readBracketToken(remaining)
            '.' -> readDotToken(remaining)
            else -> throw ContractException("Could not parse query object key segment $remaining")
        }

        return readNextTokens(readResult.remaining, tokens + requireNotNull(readResult.token))
    }

    private fun readBracketToken(remaining: String): TokenReadResult {
        val end = remaining.indexOf(']')
        if (end < 0) throw ContractException("Unclosed bracket in query object key segment $remaining")

        val value = remaining.substring(1, end)
        if (value.isBlank()) throw ContractException("Empty bracket token in query object key segment $remaining")

        return TokenReadResult(RawQueryObjectPathToken.Bracket(value), remaining.substring(end + 1))
    }

    private fun readDotToken(remaining: String): TokenReadResult {
        val end = nextSeparatorIndex(remaining, startIndex = 1)
        val value = remaining.substring(1, end)
        if (value.isBlank()) throw ContractException("Empty dot token in query object key segment $remaining")

        return TokenReadResult(RawQueryObjectPathToken.DotProperty(value), remaining.substring(end))
    }

    private fun nextSeparatorIndex(value: String, startIndex: Int): Int {
        val nextBracket = value.indexOf('[', startIndex).takeIf { it >= 0 } ?: value.length
        val nextDot = value.indexOf('.', startIndex).takeIf { it >= 0 } ?: value.length
        return minOf(nextBracket, nextDot)
    }
}

private data class TokenReadResult(
    val token: RawQueryObjectPathToken?,
    val remaining: String
) {
    companion object {
        fun noToken(remaining: String): TokenReadResult = TokenReadResult(null, remaining)
    }
}
