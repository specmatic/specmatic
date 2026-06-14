package io.specmatic.core

import io.specmatic.core.pattern.ContractException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class ObjectQuerySyntax(
    val root: ObjectQueryRoot,
    val propertyStyle: QueryPropertyStyle,
    val arrayIndexStyle: QueryArrayIndexStyle
) {
    companion object {
        val Default = ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)

        fun supportedSyntaxes(): List<ObjectQuerySyntax> {
            return ObjectQueryRoot.entries.flatMap { root ->
                QueryPropertyStyle.entries.map { propertyStyle ->
                    ObjectQuerySyntax(root, propertyStyle, QueryArrayIndexStyle.Bracket)
                }
            }
        }
    }
}

enum class ObjectQueryRoot(
    val displayName: String,
    internal val allowBareStart: Boolean,
    private val wrapperSeparator: String?
) {
    ParameterNameWrapped("parameter-name bracket wrapping", allowBareStart = false, wrapperSeparator = "[") {
        override fun keyForRootProperty(parameterName: String, propertyName: String): String = "$parameterName[$propertyName]"
    },
    ParameterNameDotWrapped("parameter-name dot wrapping", allowBareStart = false, wrapperSeparator = ".") {
        override fun keyForRootProperty(parameterName: String, propertyName: String): String = "$parameterName.$propertyName"
    },
    Unwrapped("unwrapped query keys", allowBareStart = true, wrapperSeparator = null) {
        override fun keyForRootProperty(parameterName: String, propertyName: String): String = propertyName
    };

    abstract fun keyForRootProperty(parameterName: String, propertyName: String): String

    internal fun isExplicitRootFor(key: String, parameterName: String): Boolean {
        val prefix = wrapperPrefix(parameterName) ?: return false
        return key.startsWith(prefix)
    }

    internal fun tokenizerRemainder(key: String, parameterName: String): String {
        if (wrapperSeparator == null) return key

        if (!isExplicitRootFor(key, parameterName)) {
            throw ContractException("Expected query key $key to be wrapped by parameter $parameterName")
        }

        return key.removePrefix(parameterName)
    }

    private fun wrapperPrefix(parameterName: String): String? {
        return wrapperSeparator?.let { separator -> "$parameterName$separator" }
    }

    companion object {
        fun explicitRootFor(key: String, parameterName: String): ObjectQueryRoot? {
            return entries.firstOrNull { root -> root.isExplicitRootFor(key, parameterName) }
        }
    }
}

enum class QueryPropertyStyle(val displayName: String) {
    Bracket("bracket property notation") {
        override fun appendProperty(key: String, propertyName: String): String = "$key[$propertyName]"
    },
    Dot("dot property notation") {
        override fun appendProperty(key: String, propertyName: String): String = "$key.$propertyName"
    };

    abstract fun appendProperty(key: String, propertyName: String): String
}

enum class QueryArrayIndexStyle {
    Bracket {
        override fun appendIndex(key: String, index: Int): String = "$key[$index]"
    };

    abstract fun appendIndex(key: String, index: Int): String
}

sealed class QueryObjectPathToken {
    abstract fun appendTo(key: String, syntax: ObjectQuerySyntax): String
    abstract fun displaySegment(): String

    data class Property(val name: String) : QueryObjectPathToken() {
        fun rootKey(parameterName: String, syntax: ObjectQuerySyntax): String {
            return syntax.root.keyForRootProperty(parameterName, name)
        }

        override fun appendTo(key: String, syntax: ObjectQuerySyntax): String {
            return syntax.propertyStyle.appendProperty(key, name)
        }

        override fun displaySegment(): String = name
    }

    data class Index(val index: Int) : QueryObjectPathToken() {
        override fun appendTo(key: String, syntax: ObjectQuerySyntax): String {
            return syntax.arrayIndexStyle.appendIndex(key, index)
        }

        override fun displaySegment(): String = "[$index]"
    }
}

data class QueryObjectPath(val tokens: List<QueryObjectPathToken>) {
    fun serialize(parameterName: String, syntax: ObjectQuerySyntax): String {
        val first = tokens.firstOrNull() as? QueryObjectPathToken.Property
            ?: throw ContractException("Query object path must start with a property")

        return tokens.drop(1).fold(first.rootKey(parameterName, syntax)) { key, token ->
            token.appendTo(key, syntax)
        }
    }
}

sealed class NestedQuerySchema {
    fun schemaAt(path: QueryObjectPath): NestedQuerySchema? {
        return schemaAt(path.tokens)
    }

    private fun schemaAt(tokens: List<QueryObjectPathToken>): NestedQuerySchema? {
        if (tokens.isEmpty()) return this

        return childSchema(tokens.first())?.schemaAt(tokens.drop(1))
    }

    protected abstract fun childSchema(token: QueryObjectPathToken): NestedQuerySchema?
    internal abstract fun requiresSyntaxExampleAtPath(): Boolean
    internal abstract fun requiresPropertyStyleGuidance(): Boolean

    data object Scalar : NestedQuerySchema() {
        override fun childSchema(token: QueryObjectPathToken): NestedQuerySchema? = null
        override fun requiresSyntaxExampleAtPath(): Boolean = false
        override fun requiresPropertyStyleGuidance(): Boolean = false
    }

    data class Object(
        val properties: Map<String, NestedQuerySchema>,
        val additionalProperties: NestedQuerySchema? = null,
        val allowsAnyAdditionalProperties: Boolean = false
    ) : NestedQuerySchema() {
        override fun childSchema(token: QueryObjectPathToken): NestedQuerySchema? {
            val property = token as? QueryObjectPathToken.Property ?: return null

            return properties[property.name]
                ?: additionalProperties
                ?: if (allowsAnyAdditionalProperties) Scalar else null
        }

        override fun requiresSyntaxExampleAtPath(): Boolean = true

        override fun requiresPropertyStyleGuidance(): Boolean {
            return properties.isNotEmpty() || additionalProperties != null || allowsAnyAdditionalProperties
        }

        fun requiresSyntaxExamples(): Boolean {
            return syntaxGuidanceChildSchemas().any { schema -> schema.requiresSyntaxExampleAtPath() }
        }

        fun requiresNestedPropertyStyle(): Boolean {
            return syntaxGuidanceChildSchemas().any { schema -> schema.requiresPropertyStyleGuidance() } ||
                allowsAnyAdditionalProperties
        }

        internal fun couldOwnQueryKey(key: String, parameterName: String): Boolean {
            return ObjectQueryRoot.explicitRootFor(key, parameterName) != null || couldStartWithRootProperty(key)
        }

        internal fun couldStartWithRootProperty(key: String): Boolean {
            return properties.keys.any { propertyName ->
                key == propertyName || key.startsWith("$propertyName.") || key.startsWith("$propertyName[")
            }
        }

        private fun syntaxGuidanceChildSchemas(): List<NestedQuerySchema> {
            return properties.values.toList() + listOfNotNull(additionalProperties)
        }
    }

    data class Array(val itemSchema: NestedQuerySchema) : NestedQuerySchema() {
        override fun childSchema(token: QueryObjectPathToken): NestedQuerySchema? {
            if (token !is QueryObjectPathToken.Index) return null

            return itemSchema
        }

        override fun requiresSyntaxExampleAtPath(): Boolean = true
        override fun requiresPropertyStyleGuidance(): Boolean = itemSchema.requiresPropertyStyleGuidance()
    }

    data class Ambiguous(val reason: String) : NestedQuerySchema() {
        override fun childSchema(token: QueryObjectPathToken): NestedQuerySchema? = null
        override fun requiresSyntaxExampleAtPath(): Boolean = true
        override fun requiresPropertyStyleGuidance(): Boolean = true
    }
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
        if (!schema.requiresSyntaxExamples()) return NestedQuerySyntaxInferenceResult.SyntaxNotRequired

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
                    when (mergeResult) {
                        is GuidanceMergeResult.Conflict -> return NestedQuerySyntaxInferenceResult.Failure(listOf(mergeResult.message))
                        is GuidanceMergeResult.Merged -> {
                            inferredGuidance = mergeResult.guidance
                            if (inferredGuidance.isComplete(requiredGuidance)) break
                        }
                    }
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
            when (mergeResult) {
                is GuidanceMergeResult.Conflict -> return SyntaxGuidanceResult.Conflict(mergeResult.message)
                is GuidanceMergeResult.Merged -> exampleGuidance = mergeResult.guidance
            }
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
            root = ObjectQueryRoot.explicitRootFor(key, parameterName)?.let { InferredValue(it, key) },
            propertyStyle = candidates.singlePropertyStyleOrNull(requiredGuidance)?.let { InferredValue(it, key) },
            sawParseableExample = true
        )
    }

    private fun candidatesFor(
        parameterName: String,
        schema: NestedQuerySchema.Object,
        keys: List<String>
    ): List<ObjectQuerySyntax> {
        return ObjectQuerySyntax.supportedSyntaxes().filter { syntax ->
            canParseAll(parameterName, schema, syntax, keys)
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
        return schema.couldOwnQueryKey(this, parameterName)
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
        val mergedRoot = when (val result = mergeGuidance(root, other.root, ::conflictingRootStyleMessage)) {
            is MergeValueResult.Conflict -> return GuidanceMergeResult.Conflict(result.message)
            is MergeValueResult.Merged -> result.value
        }

        val mergedPropertyStyle = when (val result = mergeGuidance(propertyStyle, other.propertyStyle, ::conflictingPropertyStyleMessage)) {
            is MergeValueResult.Conflict -> return GuidanceMergeResult.Conflict(result.message)
            is MergeValueResult.Merged -> result.value
        }

        return GuidanceMergeResult.Merged(copy(
            root = mergedRoot,
            propertyStyle = mergedPropertyStyle,
            sawParseableExample = sawParseableExample || other.sawParseableExample
        ))
    }

    fun isComplete(requiredGuidance: RequiredSyntaxGuidance): Boolean {
        return !requiredGuidance.propertyStyle || propertyStyle != null
    }

    fun hasAnyGuidance(): Boolean {
        return sawParseableExample || root != null || propertyStyle != null
    }

    fun toSyntax(): ObjectQuerySyntax {
        return ObjectQuerySyntax.Default.copy(
            root = root?.value ?: ObjectQuerySyntax.Default.root,
            propertyStyle = propertyStyle?.value ?: ObjectQuerySyntax.Default.propertyStyle
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
    return "Examples use conflicting root serialization styles for nested query parameters: \"${existing.key}\" uses ${existing.value.displayName}, but \"${candidate.key}\" uses ${candidate.value.displayName}."
}

private fun conflictingPropertyStyleMessage(existing: InferredValue<QueryPropertyStyle>, candidate: InferredValue<QueryPropertyStyle>): String {
    return "Examples use conflicting property serialization styles for nested query parameters: \"${existing.key}\" uses ${existing.value.displayName}, but \"${candidate.key}\" uses ${candidate.value.displayName}."
}

private fun List<ObjectQuerySyntax>.singlePropertyStyleOrNull(requiredGuidance: RequiredSyntaxGuidance): QueryPropertyStyle? {
    if (!requiredGuidance.propertyStyle) return null

    return map(ObjectQuerySyntax::propertyStyle).distinct().singleOrNull()
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
