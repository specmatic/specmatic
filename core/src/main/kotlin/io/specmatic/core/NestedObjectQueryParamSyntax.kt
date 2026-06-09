package io.specmatic.core

import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class ObjectQuerySyntax(
    val root: ObjectQueryRoot,
    val propertyStyle: QueryPropertyStyle,
    val arrayIndexStyle: QueryArrayIndexStyle
)

enum class ObjectQueryRoot {
    ParameterNameWrapped,
    Unwrapped
}

enum class QueryPropertyStyle {
    Bracket,
    Dot
}

enum class QueryArrayIndexStyle {
    Bracket
}

sealed class QueryObjectPathToken {
    data class Property(val name: String) : QueryObjectPathToken()
    data class Index(val index: Int) : QueryObjectPathToken()
}

data class QueryObjectPath(val tokens: List<QueryObjectPathToken>)

sealed class NestedQuerySchema {
    data object Scalar : NestedQuerySchema()

    data class Object(
        val properties: Map<String, NestedQuerySchema>,
        val additionalProperties: NestedQuerySchema? = null,
        val allowsAnyAdditionalProperties: Boolean = false
    ) : NestedQuerySchema()

    data class Array(val itemSchema: NestedQuerySchema) : NestedQuerySchema()

    data class Ambiguous(val reason: String) : NestedQuerySchema()
}

object ObjectQueryKeyParser {
    fun parse(
        key: String,
        parameterName: String,
        schema: NestedQuerySchema.Object,
        syntax: ObjectQuerySyntax
    ): QueryObjectPath {
        return parseWithSchema(key, parameterName, schema, syntax).path
    }

    internal fun parseWithSchema(
        key: String,
        parameterName: String,
        schema: NestedQuerySchema.Object,
        syntax: ObjectQuerySyntax
    ): ParsedQueryObjectPath {
        val rawTokens = QueryObjectKeyTokenizer.tokenize(key, parameterName, syntax.root)
        val parserState = rawTokens.fold(QueryObjectPathParserState(schema = schema, tokens = emptyList())) { state, token ->
            parseToken(token, state, syntax)
        }

        return ParsedQueryObjectPath(QueryObjectPath(parserState.tokens), parserState.schema)
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
            else -> throw ContractException("Unknown query object property ${token.value} at ${state.displayPath()}")
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
        val isWrappedRootProperty = syntax.root == ObjectQueryRoot.ParameterNameWrapped && state.tokens.isEmpty()
        val validToken = when (token) {
            is RawQueryObjectPathToken.BareProperty -> syntax.root == ObjectQueryRoot.Unwrapped && state.tokens.isEmpty()
            is RawQueryObjectPathToken.Bracket -> syntax.propertyStyle == QueryPropertyStyle.Bracket || isWrappedRootProperty
            is RawQueryObjectPathToken.DotProperty -> syntax.propertyStyle == QueryPropertyStyle.Dot && !isWrappedRootProperty
        }

        if (!validToken) {
            throw ContractException("Query object property ${token.value} does not match inferred ${syntax.propertyStyle.name.lowercase()} property syntax")
        }
    }

    private fun parseArrayToken(
        token: RawQueryObjectPathToken,
        schema: NestedQuerySchema.Array,
        state: QueryObjectPathParserState
    ): QueryObjectPathParserState {
        val index = when (token) {
            is RawQueryObjectPathToken.Bracket -> token.value.toIntOrNull()
            else -> null
        }

        if (index == null || index < 0) {
            throw ContractException("Expected an array index at ${state.displayPath()}, but found ${token.value}")
        }

        return state.copy(
            schema = schema.itemSchema,
            tokens = state.tokens + QueryObjectPathToken.Index(index)
        )
    }
}

data class ParsedQueryObjectPath(
    val path: QueryObjectPath,
    val terminalSchema: NestedQuerySchema
)

object ObjectQueryKeySerializer {
    fun serialize(path: QueryObjectPath, parameterName: String, syntax: ObjectQuerySyntax): String {
        val first = path.tokens.firstOrNull() as? QueryObjectPathToken.Property
            ?: throw ContractException("Query object path must start with a property")

        val start = when (syntax.root) {
            ObjectQueryRoot.Unwrapped -> first.name
            ObjectQueryRoot.ParameterNameWrapped -> "$parameterName[${first.name}]"
        }

        return path.tokens.drop(1).fold(start) { key, token ->
            when (token) {
                is QueryObjectPathToken.Index -> "$key[${token.index}]"
                is QueryObjectPathToken.Property -> appendProperty(key, token.name, syntax.propertyStyle)
            }
        }
    }

    private fun appendProperty(key: String, propertyName: String, propertyStyle: QueryPropertyStyle): String {
        return when (propertyStyle) {
            QueryPropertyStyle.Bracket -> "$key[$propertyName]"
            QueryPropertyStyle.Dot -> "$key.$propertyName"
        }
    }
}

sealed class NestedQuerySyntaxInferenceResult {
    data object SyntaxNotRequired : NestedQuerySyntaxInferenceResult()
    data class SyntaxInferred(val syntax: ObjectQuerySyntax) : NestedQuerySyntaxInferenceResult()
    data class Failure(val messages: List<String>) : NestedQuerySyntaxInferenceResult()
}

data class NestedQueryParameterExamples(
    val example: String? = null,
    val examples: List<String> = emptyList()
) {
    fun examplesInPrecedenceOrder(): List<String> {
        return examples + listOfNotNull(example)
    }
}

object NestedObjectQuerySyntaxInference {
    fun infer(
        parameterName: String,
        schema: NestedQuerySchema.Object,
        parameterExamples: NestedQueryParameterExamples
    ): NestedQuerySyntaxInferenceResult {
        return infer(parameterName, schema, parameterExamples.examplesInPrecedenceOrder())
    }

    fun infer(
        parameterName: String,
        schema: NestedQuerySchema.Object,
        examples: List<String>
    ): NestedQuerySyntaxInferenceResult {
        val requiredBranches = schema.requiredSyntaxBranches()
        if (requiredBranches.isEmpty()) return NestedQuerySyntaxInferenceResult.SyntaxNotRequired

        if (examples.isEmpty()) {
            return NestedQuerySyntaxInferenceResult.Failure(listOf(missingExampleMessage(parameterName)))
        }

        val keysByExample = examples.map(::queryKeysFrom)
        val firstCompleteExample = keysByExample.asSequence()
            .map { keys -> candidatesFor(parameterName, schema, keys) }
            .firstOrNull { candidates -> candidates.any { candidate -> candidate.missingBranches(requiredBranches).isEmpty() } }

        if (firstCompleteExample != null) {
            val candidatesWithCoverage = firstCompleteExample.filter { candidate ->
                candidate.missingBranches(requiredBranches).isEmpty()
            }

            return when {
                candidatesWithCoverage.size == 1 -> NestedQuerySyntaxInferenceResult.SyntaxInferred(candidatesWithCoverage.single().syntax)
                else -> {
                    logger.log("ERROR: ${ambiguousSyntaxMessage(parameterName)} Assuming dot property notation.")
                    NestedQuerySyntaxInferenceResult.SyntaxInferred(candidatesWithCoverage.preferDotPropertySyntax().syntax)
                }
            }
        }

        val firstParseableExample = keysByExample.asSequence()
            .map { keys -> candidatesFor(parameterName, schema, keys) }
            .firstOrNull(List<CandidateParse>::isNotEmpty)

        return if (firstParseableExample != null) {
            firstParseableExample.preferDotPropertySyntax().missingBranches(requiredBranches).map { branch ->
                missingBranchMessage(parameterName, branch.displayName)
            }.forEach { warning -> logger.log("ERROR: $warning Assuming dot property notation.") }

            NestedQuerySyntaxInferenceResult.SyntaxInferred(firstParseableExample.preferDotPropertySyntax().syntax)
        } else {
            NestedQuerySyntaxInferenceResult.Failure(listOf("Query parameter $parameterName has nested query keys that could not be parsed with any supported syntax."))
        }
    }

    private fun candidatesFor(
        parameterName: String,
        schema: NestedQuerySchema.Object,
        keys: List<String>
    ): List<CandidateParse> {
        return syntaxCandidates().mapNotNull { syntax ->
            parseAll(parameterName, schema, syntax, keys)?.let { CandidateParse(syntax, it) }
        }
    }

    private fun parseAll(
        parameterName: String,
        schema: NestedQuerySchema.Object,
        syntax: ObjectQuerySyntax,
        keys: List<String>
    ): List<ParsedQueryObjectPath>? {
        val relevantKeys = keys.filter { key -> key.couldBelongTo(parameterName, schema) }
        if (relevantKeys.isEmpty()) return null

        return runCatching {
            relevantKeys.map { key -> ObjectQueryKeyParser.parseWithSchema(key, parameterName, schema, syntax) }
        }.getOrNull()
    }

    private fun String.couldBelongTo(parameterName: String, schema: NestedQuerySchema.Object): Boolean {
        if (startsWith("$parameterName[")) return true

        return schema.properties.keys.any { propertyName ->
            this == propertyName || startsWith("$propertyName.") || startsWith("$propertyName[")
        }
    }

    private fun syntaxCandidates(): List<ObjectQuerySyntax> {
        return ObjectQueryRoot.entries.flatMap { root ->
            QueryPropertyStyle.entries.map { propertyStyle ->
                ObjectQuerySyntax(root, propertyStyle, QueryArrayIndexStyle.Bracket)
            }
        }
    }

    private fun queryKeysFrom(example: String): List<String> {
        return example.split("&")
            .map { it.substringBefore("=") }
            .map { key -> URLDecoder.decode(key, StandardCharsets.UTF_8) }
            .filter(String::isNotBlank)
    }

    private fun missingExampleMessage(parameterName: String): String {
        return "Query parameter $parameterName contains nested object or array properties, but no example demonstrates how nested query keys should be serialized."
    }

    private fun missingBranchMessage(parameterName: String, branch: String): String {
        return "Query parameter $parameterName contains nested branch $branch, but no example includes a leaf under that branch."
    }

    private fun conflictingSyntaxMessage(parameterName: String): String {
        return "Query parameter $parameterName has conflicting nested query syntaxes across examples."
    }

    private fun ambiguousSyntaxMessage(parameterName: String): String {
        return "Query parameter $parameterName has ambiguous nested query syntax across examples."
    }
}

private fun List<CandidateParse>.preferDotPropertySyntax(): CandidateParse {
    return firstOrNull { it.syntax.propertyStyle == QueryPropertyStyle.Dot } ?: first()
}

private fun List<ObjectQuerySyntax>.preferDotPropertySyntax(): ObjectQuerySyntax {
    return firstOrNull { it.propertyStyle == QueryPropertyStyle.Dot } ?: first()
}

private fun NestedQuerySchema.Object.requiredSyntaxBranches(): Set<NestedQuerySyntaxBranch> {
    return requiredSyntaxBranches(prefix = emptyList(), displayPrefix = "")
}

private fun NestedQuerySchema.requiredSyntaxBranches(
    prefix: List<NestedQuerySyntaxBranchToken>,
    displayPrefix: String
): Set<NestedQuerySyntaxBranch> {
    return when (this) {
        is NestedQuerySchema.Scalar -> emptySet()
        is NestedQuerySchema.Ambiguous -> setOf(NestedQuerySyntaxBranch(prefix, displayPrefix))
        is NestedQuerySchema.Array -> {
            val branchPrefix = prefix + NestedQuerySyntaxBranchToken.AnyIndex
            val branchDisplayName = "$displayPrefix[]"
            setOf(NestedQuerySyntaxBranch(branchPrefix, branchDisplayName)) +
                itemSchema.requiredSyntaxBranches(branchPrefix, branchDisplayName)
        }
        is NestedQuerySchema.Object -> {
            val declaredPropertyBranches = properties.flatMap { (propertyName, propertySchema) ->
                val propertyPrefix = prefix + NestedQuerySyntaxBranchToken.Property(propertyName)
                val propertyDisplayName = displayName(displayPrefix, propertyName)
                propertySchema.requiredBranchesAt(propertyPrefix, propertyDisplayName)
            }

            val additionalPropertyBranches = additionalProperties?.let { additionalPropertiesSchema ->
                val propertyPrefix = prefix + NestedQuerySyntaxBranchToken.AnyProperty
                val propertyDisplayName = displayName(displayPrefix, "<additionalProperty>")
                additionalPropertiesSchema.requiredBranchesAt(propertyPrefix, propertyDisplayName)
            }.orEmpty()

            (declaredPropertyBranches + additionalPropertyBranches).toSet()
        }
    }
}

private fun NestedQuerySchema.requiredBranchesAt(
    prefix: List<NestedQuerySyntaxBranchToken>,
    displayName: String
): Set<NestedQuerySyntaxBranch> {
    return when (this) {
        is NestedQuerySchema.Scalar -> emptySet()
        is NestedQuerySchema.Array -> {
            val arrayBranchPrefix = prefix + NestedQuerySyntaxBranchToken.AnyIndex
            val arrayBranchDisplayName = "$displayName[]"
            setOf(NestedQuerySyntaxBranch(arrayBranchPrefix, arrayBranchDisplayName)) +
                itemSchema.requiredSyntaxBranches(arrayBranchPrefix, arrayBranchDisplayName)
        }
        is NestedQuerySchema.Object -> setOf(NestedQuerySyntaxBranch(prefix, displayName)) +
            requiredSyntaxBranches(prefix, displayName)
        is NestedQuerySchema.Ambiguous -> setOf(NestedQuerySyntaxBranch(prefix, displayName))
    }
}

private fun displayName(prefix: String, token: String): String {
    return if (prefix.isBlank()) token else "$prefix.$token"
}

private sealed class NestedQuerySyntaxBranchToken {
    data class Property(val name: String) : NestedQuerySyntaxBranchToken()
    data object AnyProperty : NestedQuerySyntaxBranchToken()
    data object AnyIndex : NestedQuerySyntaxBranchToken()
}

private data class NestedQuerySyntaxBranch(
    val tokens: List<NestedQuerySyntaxBranchToken>,
    val displayName: String
)

private data class CandidateParse(val syntax: ObjectQuerySyntax, val paths: List<ParsedQueryObjectPath>) {
    fun missingBranches(requiredBranches: Set<NestedQuerySyntaxBranch>): Set<NestedQuerySyntaxBranch> {
        return requiredBranches.filterNot { branch ->
            paths.any { parsedPath -> parsedPath.covers(branch) }
        }.toSet()
    }
}

private fun ParsedQueryObjectPath.covers(branch: NestedQuerySyntaxBranch): Boolean {
    return terminalSchema is NestedQuerySchema.Scalar && path.tokens.matches(branch.tokens)
}

private fun List<QueryObjectPathToken>.matches(branchTokens: List<NestedQuerySyntaxBranchToken>): Boolean {
    if (size < branchTokens.size) return false

    return branchTokens.zip(take(branchTokens.size)).all { (branchToken, pathToken) ->
        when (branchToken) {
            is NestedQuerySyntaxBranchToken.Property -> pathToken is QueryObjectPathToken.Property && pathToken.name == branchToken.name
            NestedQuerySyntaxBranchToken.AnyProperty -> pathToken is QueryObjectPathToken.Property
            NestedQuerySyntaxBranchToken.AnyIndex -> pathToken is QueryObjectPathToken.Index
        }
    }
}

private data class QueryObjectPathParserState(
    val schema: NestedQuerySchema,
    val tokens: List<QueryObjectPathToken>
) {
    fun displayPath(): String {
        if (tokens.isEmpty()) return "<root>"

        return tokens.joinToString(".") { token ->
            when (token) {
                is QueryObjectPathToken.Property -> token.name
                is QueryObjectPathToken.Index -> "[${token.index}]"
            }
        }
    }
}

private sealed class RawQueryObjectPathToken {
    abstract val value: String

    data class BareProperty(override val value: String) : RawQueryObjectPathToken()
    data class Bracket(override val value: String) : RawQueryObjectPathToken()
    data class DotProperty(override val value: String) : RawQueryObjectPathToken()
}

private object QueryObjectKeyTokenizer {
    fun tokenize(key: String, parameterName: String, root: ObjectQueryRoot): List<RawQueryObjectPathToken> {
        return when (root) {
            ObjectQueryRoot.Unwrapped -> tokenizeRemainder(key, allowBareStart = true)
            ObjectQueryRoot.ParameterNameWrapped -> {
                if (!key.startsWith(parameterName)) {
                    throw ContractException("Expected query key $key to be wrapped by parameter $parameterName")
                }

                tokenizeRemainder(key.removePrefix(parameterName), allowBareStart = false)
            }
        }
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
