package io.specmatic.core

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
    ParameterNameDotWrapped,
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
    fun schemaAt(path: QueryObjectPath): NestedQuerySchema? {
        return schemaAt(path.tokens)
    }

    private fun schemaAt(tokens: List<QueryObjectPathToken>): NestedQuerySchema? {
        if (tokens.isEmpty()) return this

        return when (this) {
            is Object -> childSchema(tokens.first())?.schemaAt(tokens.drop(1))
            is Array -> itemSchemaFor(tokens.first())?.schemaAt(tokens.drop(1))
            Scalar, is Ambiguous -> null
        }
    }

    private fun Object.childSchema(token: QueryObjectPathToken): NestedQuerySchema? {
        val property = token as? QueryObjectPathToken.Property ?: return null

        return properties[property.name]
            ?: additionalProperties
            ?: if (allowsAnyAdditionalProperties) Scalar else null
    }

    private fun Array.itemSchemaFor(token: QueryObjectPathToken): NestedQuerySchema? {
        if (token !is QueryObjectPathToken.Index) return null

        return itemSchema
    }

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
        val isBracketWrappedRootProperty = syntax.root == ObjectQueryRoot.ParameterNameWrapped && state.tokens.isEmpty()
        val isDotWrappedRootProperty = syntax.root == ObjectQueryRoot.ParameterNameDotWrapped && state.tokens.isEmpty()
        val validToken = when (token) {
            is RawQueryObjectPathToken.BareProperty -> syntax.root == ObjectQueryRoot.Unwrapped && state.tokens.isEmpty()
            is RawQueryObjectPathToken.Bracket -> syntax.propertyStyle == QueryPropertyStyle.Bracket || isBracketWrappedRootProperty
            is RawQueryObjectPathToken.DotProperty -> (syntax.propertyStyle == QueryPropertyStyle.Dot && !isBracketWrappedRootProperty) || isDotWrappedRootProperty
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

object ObjectQueryKeySerializer {
    fun serialize(path: QueryObjectPath, parameterName: String, syntax: ObjectQuerySyntax): String {
        val first = path.tokens.firstOrNull() as? QueryObjectPathToken.Property
            ?: throw ContractException("Query object path must start with a property")

        val start = when (syntax.root) {
            ObjectQueryRoot.Unwrapped -> first.name
            ObjectQueryRoot.ParameterNameWrapped -> "$parameterName[${first.name}]"
            ObjectQueryRoot.ParameterNameDotWrapped -> "$parameterName.${first.name}"
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
        return listOfNotNull(example) + examples
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

        val requiredGuidance = RequiredSyntaxGuidance(
            propertyStyle = schema.requiresNestedPropertyStyle()
        )

        var inferredGuidance = InferredSyntaxGuidance()
        for (keys in examples.map(::queryKeysFrom)) {
            val exampleGuidance = guidanceFrom(parameterName, schema, keys, requiredGuidance)

            when (exampleGuidance) {
                is SyntaxGuidanceResult.Conflict -> return NestedQuerySyntaxInferenceResult.Failure(listOf(exampleGuidance.message))
                SyntaxGuidanceResult.NoGuidance -> continue
                is SyntaxGuidanceResult.Guidance -> {
                    val mergeResult = inferredGuidance.with(exampleGuidance.guidance)
                    if (mergeResult is GuidanceMergeResult.Conflict) {
                        return NestedQuerySyntaxInferenceResult.Failure(listOf(mergeResult.message))
                    }

                    inferredGuidance = (mergeResult as GuidanceMergeResult.Merged).guidance
                    if (inferredGuidance.isComplete(requiredGuidance)) break
                }
            }
        }

        return if (inferredGuidance.hasAnyGuidance()) {
            NestedQuerySyntaxInferenceResult.SyntaxInferred(inferredGuidance.toSyntax())
        } else {
            NestedQuerySyntaxInferenceResult.Failure(listOf(missingExampleMessage(parameterName)))
        }
    }

    private fun guidanceFrom(
        parameterName: String,
        schema: NestedQuerySchema.Object,
        keys: List<String>,
        requiredGuidance: RequiredSyntaxGuidance
    ): SyntaxGuidanceResult {
        val relevantKeys = keys.filter { key -> key.couldBelongTo(parameterName, schema) }
        if (relevantKeys.isEmpty()) return SyntaxGuidanceResult.NoGuidance

        var exampleGuidance = InferredSyntaxGuidance()
        relevantKeys.forEach { key ->
            val keyGuidance = guidanceFromKey(parameterName, schema, key, requiredGuidance)
                ?: return SyntaxGuidanceResult.Conflict(unparseableNestedQueryKeyMessage(parameterName, key))
            val mergeResult = exampleGuidance.with(keyGuidance)
            if (mergeResult is GuidanceMergeResult.Conflict) return SyntaxGuidanceResult.Conflict(mergeResult.message)

            exampleGuidance = (mergeResult as GuidanceMergeResult.Merged).guidance
        }

        return if (exampleGuidance.hasAnyGuidance()) {
            SyntaxGuidanceResult.Guidance(exampleGuidance)
        } else {
            SyntaxGuidanceResult.NoGuidance
        }
    }

    private fun guidanceFromKey(
        parameterName: String,
        schema: NestedQuerySchema.Object,
        key: String,
        requiredGuidance: RequiredSyntaxGuidance
    ): InferredSyntaxGuidance? {
        val candidates = candidatesFor(parameterName, schema, listOf(key))
        if (candidates.isEmpty()) return null

        return InferredSyntaxGuidance(
            root = key.explicitRootGuidance(parameterName)?.let { InferredValue(it, key) },
            propertyStyle = candidates.singlePropertyStyleOrNull(requiredGuidance)?.let { InferredValue(it, key) },
            sawParseableExample = true
        )
    }

    private fun candidatesFor(
        parameterName: String,
        schema: NestedQuerySchema.Object,
        keys: List<String>
    ): List<CandidateParse> {
        return syntaxCandidates().mapNotNull { syntax ->
            if (canParseAll(parameterName, schema, syntax, keys)) CandidateParse(syntax) else null
        }
    }

    private fun canParseAll(
        parameterName: String,
        schema: NestedQuerySchema.Object,
        syntax: ObjectQuerySyntax,
        keys: List<String>
    ): Boolean {
        val relevantKeys = keys.filter { key -> key.couldBelongTo(parameterName, schema) }
        if (relevantKeys.isEmpty()) return false

        return runCatching {
            relevantKeys.forEach { key -> ObjectQueryKeyParser.parseWithSchema(key, parameterName, schema, syntax) }
        }.isSuccess
    }

    private fun String.couldBelongTo(parameterName: String, schema: NestedQuerySchema.Object): Boolean {
        if (startsWith("$parameterName[")) return true
        if (startsWith("$parameterName.")) return true

        return schema.properties.keys.any { propertyName ->
            this == propertyName || startsWith("$propertyName.") || startsWith("$propertyName[")
        }
    }

    private fun String.explicitRootGuidance(parameterName: String): ObjectQueryRoot? {
        return when {
            startsWith("$parameterName[") -> ObjectQueryRoot.ParameterNameWrapped
            startsWith("$parameterName.") -> ObjectQueryRoot.ParameterNameDotWrapped
            else -> null
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
        return "No example of query parameter $parameterName demonstrates how nested properties should be serialized as query parameters."
    }

    private fun unparseableNestedQueryKeyMessage(parameterName: String, key: String): String {
        return "Example of query parameter $parameterName contains nested query key \"$key\" that could not be parsed with any supported nested query syntax."
    }

}

private fun NestedQuerySchema.Object.requiredSyntaxBranches(): Set<NestedQuerySyntaxBranch> {
    return requiredSyntaxBranches(prefix = emptyList(), displayPrefix = "")
}

private data class RequiredSyntaxGuidance(
    val propertyStyle: Boolean
)

private data class InferredValue<T>(
    val value: T,
    val key: String
)

private data class InferredSyntaxGuidance(
    val root: InferredValue<ObjectQueryRoot>? = null,
    val propertyStyle: InferredValue<QueryPropertyStyle>? = null,
    val sawParseableExample: Boolean = false
) {
    fun with(other: InferredSyntaxGuidance): GuidanceMergeResult {
        val mergedRoot = mergeGuidance(root, other.root, ::conflictingRootStyleMessage)
        if (mergedRoot is MergeValueResult.Conflict) return GuidanceMergeResult.Conflict(mergedRoot.message)

        val mergedPropertyStyle = mergeGuidance(propertyStyle, other.propertyStyle, ::conflictingPropertyStyleMessage)
        if (mergedPropertyStyle is MergeValueResult.Conflict) return GuidanceMergeResult.Conflict(mergedPropertyStyle.message)

        return GuidanceMergeResult.Merged(
            copy(
                root = (mergedRoot as MergeValueResult.Merged).value,
                propertyStyle = (mergedPropertyStyle as MergeValueResult.Merged).value,
                sawParseableExample = sawParseableExample || other.sawParseableExample
            )
        )
    }

    fun isComplete(requiredGuidance: RequiredSyntaxGuidance): Boolean {
        return !requiredGuidance.propertyStyle || propertyStyle != null
    }

    fun hasAnyGuidance(): Boolean {
        return sawParseableExample || root != null || propertyStyle != null
    }

    fun toSyntax(): ObjectQuerySyntax {
        return ObjectQuerySyntax(
            root = root?.value ?: ObjectQueryRoot.Unwrapped,
            propertyStyle = propertyStyle?.value ?: QueryPropertyStyle.Dot,
            arrayIndexStyle = QueryArrayIndexStyle.Bracket
        )
    }
}

private sealed class SyntaxGuidanceResult {
    data object NoGuidance : SyntaxGuidanceResult()
    data class Guidance(val guidance: InferredSyntaxGuidance) : SyntaxGuidanceResult()
    data class Conflict(val message: String) : SyntaxGuidanceResult()
}

private sealed class GuidanceMergeResult {
    data class Merged(val guidance: InferredSyntaxGuidance) : GuidanceMergeResult()
    data class Conflict(val message: String) : GuidanceMergeResult()
}

private sealed class MergeValueResult<out T> {
    data class Merged<T>(val value: InferredValue<T>?) : MergeValueResult<T>()
    data class Conflict(val message: String) : MergeValueResult<Nothing>()
}

private fun <T> mergeGuidance(
    existing: InferredValue<T>?,
    candidate: InferredValue<T>?,
    conflictMessage: (InferredValue<T>, InferredValue<T>) -> String
): MergeValueResult<T> {
    return when {
        existing == null -> MergeValueResult.Merged(candidate)
        candidate == null || existing.value == candidate.value -> MergeValueResult.Merged(existing)
        else -> MergeValueResult.Conflict(conflictMessage(existing, candidate))
    }
}

private fun conflictingRootStyleMessage(existing: InferredValue<ObjectQueryRoot>, candidate: InferredValue<ObjectQueryRoot>): String {
    return "Examples use conflicting root serialization styles for nested query parameters: \"${existing.key}\" uses ${existing.value.displayName()}, but \"${candidate.key}\" uses ${candidate.value.displayName()}."
}

private fun conflictingPropertyStyleMessage(existing: InferredValue<QueryPropertyStyle>, candidate: InferredValue<QueryPropertyStyle>): String {
    return "Examples use conflicting property serialization styles for nested query parameters: \"${existing.key}\" uses ${existing.value.displayName()}, but \"${candidate.key}\" uses ${candidate.value.displayName()}."
}

private fun ObjectQueryRoot.displayName(): String {
    return when (this) {
        ObjectQueryRoot.ParameterNameWrapped -> "parameter-name bracket wrapping"
        ObjectQueryRoot.ParameterNameDotWrapped -> "parameter-name dot wrapping"
        ObjectQueryRoot.Unwrapped -> "unwrapped query keys"
    }
}

private fun QueryPropertyStyle.displayName(): String {
    return when (this) {
        QueryPropertyStyle.Bracket -> "bracket property notation"
        QueryPropertyStyle.Dot -> "dot property notation"
    }
}

private fun List<CandidateParse>.singlePropertyStyleOrNull(requiredGuidance: RequiredSyntaxGuidance): QueryPropertyStyle? {
    if (!requiredGuidance.propertyStyle) return null

    return map { it.syntax.propertyStyle }.distinct().singleOrNull()
}

private fun NestedQuerySchema.Object.requiresNestedPropertyStyle(): Boolean {
    return properties.values.any(NestedQuerySchema::requiresNestedPropertyStyle) ||
        additionalProperties?.requiresNestedPropertyStyle() == true ||
        allowsAnyAdditionalProperties
}

private fun NestedQuerySchema.requiresNestedPropertyStyle(): Boolean {
    return when (this) {
        is NestedQuerySchema.Scalar -> false
        is NestedQuerySchema.Array -> itemSchema.requiresPropertyStyleInsideArray()
        is NestedQuerySchema.Object -> properties.isNotEmpty() || additionalProperties != null || allowsAnyAdditionalProperties
        is NestedQuerySchema.Ambiguous -> true
    }
}

private fun NestedQuerySchema.requiresPropertyStyleInsideArray(): Boolean {
    return when (this) {
        is NestedQuerySchema.Scalar -> false
        is NestedQuerySchema.Array -> itemSchema.requiresPropertyStyleInsideArray()
        is NestedQuerySchema.Object -> properties.isNotEmpty() || additionalProperties != null || allowsAnyAdditionalProperties
        is NestedQuerySchema.Ambiguous -> true
    }
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

private data class CandidateParse(val syntax: ObjectQuerySyntax)

private data class QueryObjectPathParserState(
    val schema: NestedQuerySchema,
    val tokens: List<QueryObjectPathToken>
) {
    fun displayPath(): String {
        if (tokens.isEmpty()) return "root"

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
                if (!key.startsWith("$parameterName[")) {
                    throw ContractException("Expected query key $key to be wrapped by parameter $parameterName")
                }

                tokenizeRemainder(key.removePrefix(parameterName), allowBareStart = false)
            }
            ObjectQueryRoot.ParameterNameDotWrapped -> {
                if (!key.startsWith("$parameterName.")) {
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
