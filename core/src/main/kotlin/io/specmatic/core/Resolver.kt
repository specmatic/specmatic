package io.specmatic.core

import io.specmatic.core.pattern.*
import io.specmatic.core.value.*
import io.specmatic.test.ExampleProcessor
import io.specmatic.test.asserts.WILDCARD_INDEX

val actualMatch: (resolver: Resolver, factKey: String?, pattern: Pattern, sampleValue: Value) -> Result = { resolver: Resolver, factKey: String?, pattern: Pattern, sampleValue: Value ->
    resolver.actualPatternMatch(factKey, pattern, sampleValue)
}

val matchAnything: (resolver: Resolver, factKey: String?, pattern: Pattern, sampleValue: Value) -> Result = { _: Resolver, _: String?, _: Pattern, _: Value ->
    Result.Success()
}

val actualParse: (resolver: Resolver, pattern: Pattern, rowValue: String) -> Value = { resolver: Resolver, pattern: Pattern, rowValue: String ->
    resolver.actualParse(pattern, rowValue)
}

val alwaysReturnStringValue: (resolver: Resolver, pattern: Pattern, rowValue: String) -> Value = { _: Resolver, _: Pattern, rowValue: String ->
    StringValue(rowValue)
}

data class JSONObjectResolver(
    val allowOnlyMandatoryKeys: Boolean = false
)

data class KeyWithPattern(
    val key: String,
    val pattern: Pattern
)

data class Resolver(
    val factStore: FactStore = CheckFacts(),
    val mockMode: Boolean = false,
    val newPatterns: Map<String, Pattern> = emptyMap(),
    val findKeyErrorCheck: KeyCheck = DefaultKeyCheck,
    val context: Context = NoContext,
    val mismatchMessages: MismatchMessages = DefaultMismatchMessages,
    val isNegative: Boolean = false,
    val patternMatchStrategy: (resolver: Resolver, factKey: String?, pattern: Pattern, sampleValue: Value) -> Result = actualMatch,
    val parseStrategy: (resolver: Resolver, pattern: Pattern, rowValue: String) -> Value = actualParse,
    val cyclePreventionStack: List<Pattern> = listOf(),
    val defaultExampleResolver: DefaultExampleResolver = DoNotUseDefaultExample,
    val generation: GenerationStrategies = NonGenerativeTests,
    val dictionary: Dictionary = Dictionary.empty(),
    val dictionaryLookupPath: String = "",
    val jsonObjectResolver: JSONObjectResolver = JSONObjectResolver(),
    val allPatternsAreMandatory: Boolean = false,
    val patternsSeenSoFar: Set<String> = setOf(),
    val lookupPathsSeenSoFar: Set<String> = setOf(),
    val cycleMarker: String = "",
) {
    constructor(facts: Map<String, Value> = emptyMap(), mockMode: Boolean = false, newPatterns: Map<String, Pattern> = emptyMap()) : this(CheckFacts(facts), mockMode, newPatterns)
    constructor() : this(emptyMap(), false)

    val patterns: Map<String, Pattern>
        get() {
            return builtInPatterns.plus(newPatterns)
        }

    val allowOnlyMandatoryKeysInJsonObject: Boolean
        get() {
            return this.jsonObjectResolver.allowOnlyMandatoryKeys
        }

    fun withUnexpectedKeyCheck(unexpectedKeyCheck: UnexpectedKeyCheck): Resolver {
        return this.copy(findKeyErrorCheck = this.findKeyErrorCheck.withUnexpectedKeyCheck(unexpectedKeyCheck))
    }

    fun withOnlyMandatoryKeysInJSONObject(): Resolver {
        return this.copy(jsonObjectResolver = this.jsonObjectResolver.copy(allowOnlyMandatoryKeys = true))
    }

    fun withAllPatternsAsMandatory(): Resolver {
        return this.copy(allPatternsAreMandatory = true)
    }

    fun withoutAllPatternsAsMandatory(): Resolver {
        return this.copy(allPatternsAreMandatory = false)
    }

    fun disableOverrideUnexpectedKeycheck(): Resolver {
        return this.copy(findKeyErrorCheck = this.findKeyErrorCheck.disableOverrideUnexpectedKeycheck())
    }

    fun findKeyError(pattern: Map<String, Any>, actual: Map<String, Any>): KeyError? {
        return findKeyErrorList(pattern, actual).firstOrNull()
    }

    fun findKeyErrorList(pattern: Map<String, Any>, actual: Map<String, Any>): List<KeyError> {
        return findKeyErrorCheck.validateAll(pattern, actual)
    }

    fun matchesPattern(factKey: String?, pattern: Pattern, sampleValue: Value): Result {
        return patternMatchStrategy(this, factKey, pattern, sampleValue)
    }

    private fun patternTokenMatch(pattern: Pattern, sampleData: Value): Result? {
        if (!mockMode) return null
        if (ExampleProcessor.isSubstitutionToken(sampleData)) return Result.Success()

        val patternFromValue = patternFromTokenBased(sampleData) ?: return null
        if (patternFromValue is AnyValuePattern) return Result.Success()

        return pattern.encompasses(patternFromValue, this, this)
    }

    fun actualPatternMatch(factKey: String?, pattern: Pattern, sampleValue: Value): Result {
        val tokenMatchResult = patternTokenMatch(pattern, sampleValue)
        if (tokenMatchResult != null) return tokenMatchResult

        return pattern.matches(sampleValue, this).ifSuccess {
            if (factKey != null && factStore.has(factKey)) {
                val result = factStore.match(sampleValue, factKey)
                if(result is Result.Failure) {
                    result.reason("Resolver was not able to match fact $factKey with value $sampleValue.")
                }
            }
            Result.Success()
        }
    }

    private fun patternFromTokenBased(sampleValue: Value): Pattern? {
        if (sampleValue !is StringValue || !isPatternToken(sampleValue.string)) return null
        return getPattern(sampleValue.string).let {
            if (it is LookupRowPattern) resolvedHop(
                it.pattern,
                this
            ) else it
        }
    }

    fun hasPattern(patternValue: String): Boolean = patternValue in patterns

    fun getPattern(patternValue: String): Pattern =
        when {
            isPatternToken(patternValue) -> {
                val resolvedPattern = patterns[patternValue] ?: parsedPattern(patternValue, null)
                when {
                    resolvedPattern is DeferredPattern && resolvedPattern.pattern == patternValue ->
                        throw ContractException("Type $patternValue does not exist")
                    else -> resolvedPattern
                }
            }
            else -> throw ContractException("$patternValue is not a type")
        }

    private fun getPatternOrElse(patternValue: String, orElse: Pattern): Pattern {
        return runCatching { getPattern(patternValue) }.getOrDefault(orElse)
    }

    fun <T> withCyclePrevention(pattern: Pattern, toResult: (r: Resolver) -> T) : T {
        return withCyclePrevention(pattern, false, toResult)!!
    }

    fun <T> withCyclePrevention(pattern: Pattern, key: String, returnNullOnCycle: Boolean = false, toResult: (r: Resolver) -> T) : T? {
        if(!allPatternsAreMandatory)
            return withCyclePrevention(pattern, returnNullOnCycle, toResult)

        val lookupPath = lookupPath(pattern.typeAlias, key)

        return try {
            if (key.isNotBlank() && lookupPathSeen(lookupPath) && cycleMarker.isEmpty()) {
                // Terminate what would otherwise be an infinite cycle.
                throw ContractException("Invalid pattern cycle: $lookupPath", isCycle = true)
            } else if (key.isNotBlank() && lookupPathSeen(lookupPath) && cycleMarker.isNotEmpty()) {
                toResult(this.clearCycleMarker())
            } else {
                toResult(this)
            }
        } catch (e: ContractException) {
            if (!e.isCycle || !returnNullOnCycle)
                throw e

            // Returns null if (and only if) a cycle has been detected and returnNullOnCycle=true
            null
        }
    }

    private fun clearCycleMarker(): Resolver {
        return this.copy(cycleMarker = "")
    }

    /**
     * Returns non-null if no cycle. If there is a cycle then ContractException(cycle=true) is thrown - unless
     * returnNullOnCycle=true in which case null is returned. Null is never returned if returnNullOnCycle=false.
     */
    fun <T> withCyclePrevention(pattern: Pattern, returnNullOnCycle: Boolean = false, toResult: (r: Resolver) -> T) : T? {
        if(allPatternsAreMandatory)
            return withCyclePrevention(pattern, "", returnNullOnCycle, toResult)

        val count = cyclePreventionStack.filter { it == pattern }.size
        val newCyclePreventionStack = cyclePreventionStack.plus(pattern)

        return try {
            if (count > 1) {
                // Terminate what would otherwise be an infinite cycle.
                val stackAsString = newCyclePreventionStack.mapNotNull { it.typeAlias }.filter { it.isNotBlank() }.joinToString(", ") { withoutPatternDelimiters(it) }
                throw ContractException("Invalid pattern cycle: $stackAsString", isCycle = true)
            }

            toResult(copy(cyclePreventionStack = newCyclePreventionStack))
        } catch (e: ContractException) {
            if (!e.isCycle || !returnNullOnCycle)
                throw e

            // Returns null if (and only if) a cycle has been detected and returnNullOnCycle=true
            null
        }
    }

    fun generate(pattern: Pattern): Value {
        val valueFromDict = dictionary.getValueFor(dictionaryLookupPath, pattern, this)
        if (valueFromDict != null) {
            return valueFromDict.unwrapOrContractException()
        }

        val defaultValueFromDict = dictionary.getDefaultValueFor(pattern, this)
        if (defaultValueFromDict != null) {
            return defaultValueFromDict
        }

        return pattern.generate(this)
    }

    fun generate(typeAlias: String?, rawLookupKey: String, pattern: Pattern): Value {
        val resolvedPattern = resolvedHop(pattern, this)
        if (resolvedPattern is ExactValuePattern && !resolvedPattern.hasPatternToken())
            return pattern.generate(this)

        val lookupKey = withoutOptionality(rawLookupKey)

        if (factStore.has(lookupKey))
            return generate(lookupKey, pattern)

        val updatedResolver = updateLookupPath(typeAlias, KeyWithPattern(lookupKey, pattern))
        return if (pattern is JSONArrayPattern) pattern.generate(updatedResolver) else updatedResolver.generate(pattern)
    }

    fun fix(typeAlias: String?, lookupKey: String, pattern: Pattern, value: Value): Value {
        val resolvedPattern = resolvedHop(pattern, this)
        if (resolvedPattern is ExactValuePattern) return resolvedPattern.generate(this)

        val updatedResolver = updateLookupPath(typeAlias, KeyWithPattern(lookupKey, pattern))
        return pattern.fixValue(value, updatedResolver)
    }

    fun updateLookupPath(typeAlias: String?, keyWithPattern: KeyWithPattern? = null): Resolver {
        val lookupPath = lookupPath(typeAlias, keyWithPattern?.key.orEmpty()).takeIf(String::isNotBlank) ?: return this

        val schemaName = typeAlias ?: "*".takeIf { dictionaryLookupPath.isBlank() }
        val patternFocused = dictionary.applyIf(schemaName) { patternName ->
            val pattern = getPatternOrElse(patternName, AnyValuePattern)
            focusIntoSchema(pattern, withoutPatternDelimiters(patternName), this@Resolver)
        }

        val keyFocused = patternFocused.applyIf(keyWithPattern) { (focusKey, keyPattern) ->
            focusIntoProperty(keyPattern, focusKey, this@Resolver)
        }

        return this.copy(
            dictionaryLookupPath = lookupPath,
            lookupPathsSeenSoFar = lookupPathsSeenSoFar.plus(lookupPath),
            dictionary = keyFocused
        )
    }

    fun <T> updateLookupPath(pattern: T, childPattern: Pattern): Resolver where T: Pattern, T: SequenceType {
        val patternFocused = updateLookupPath(pattern.typeAlias)
        val itemFocused = patternFocused.dictionary.applyIf(patternFocused.lastLookupKey()) { key ->
            focusIntoSequence(pattern, childPattern, key, patternFocused)
        }

        val lookupPath = patternFocused.lookupPath(null, WILDCARD_INDEX)
        return patternFocused.copy(
            dictionaryLookupPath = lookupPath,
            lookupPathsSeenSoFar = lookupPathsSeenSoFar.plus(lookupPath),
            dictionary = itemFocused
        )
    }

    fun updateLookupForParam(paramName: String): Resolver {
        val lookupPath = lookupPath(null, paramName).takeIf(String::isNotBlank) ?: return this
        val paramFocused = dictionary.focusIntoProperty(AnyValuePattern, paramName, this)

        return copy(
            dictionaryLookupPath = lookupPath,
            lookupPathsSeenSoFar = lookupPathsSeenSoFar.plus(lookupPath),
            dictionary = paramFocused
        )
    }

    private fun lookupPath(typeAlias: String?, lookupKey: String): String {
        val base = typeAlias?.trim()?.let(::withoutPatternDelimiters)?.takeIf(String::isNotBlank) ?: dictionaryLookupPath
        return when (lookupKey) {
            "" -> base
            "[*]" -> "$base[*]"
            else -> "$base.$lookupKey"
        }
    }

    fun lookupPathSeen(lookupPath: String): Boolean {
        if(lookupPath.isBlank())
            return false

        if(lookupPathsSeenSoFar.contains(lookupPath))
            return true

        val dotTerminatedPath = "$lookupPath."
        if(lookupPathsSeenSoFar.any { it.startsWith(dotTerminatedPath) })
            return true

        return false
    }

    fun generateList(pattern: ListPattern): Value {
        val patternFocused = updateLookupPath(pattern.typeAlias)
        val valueFromDict = patternFocused.dictionary.getValueFor(patternFocused.dictionaryLookupPath, pattern, this)
        if (valueFromDict != null) {
            return valueFromDict.unwrapOrContractException()
        }

        return this.updateLookupPath(pattern, pattern.pattern).generateRandomList(pattern.pattern)
    }

    private fun generateRandomList(pattern: Pattern): Value {
        return pattern.listOf(0.until(randomNumber(3)).mapIndexed{ index, _ ->
            attempt(breadCrumb = "[$index (random)]") { generate(pattern) }
        }, this)
    }

    fun generate(factKey: String, pattern: Pattern): Value {
        if (!factStore.has(factKey))
            return pattern.generate(this)

        return when(val fact = factStore.get(factKey)) {
            is StringValue ->
                try {
                    pattern.parse(fact.string, this)
                } catch (e: Throwable) {
                    throw ContractException("""Value $fact in fact $factKey is not a $pattern""")
                }
            True -> pattern.generate(this)
            else -> {
                when(val matchResult = pattern.matches(fact, this)) {
                    is Result.Failure -> throw ContractException(matchResult.toFailureReport())
                    else -> fact
                }
            }
        }
    }

    fun findKeyErrorListCaseInsensitive(pattern: Map<String, Pattern>, actual: Map<String, StringValue>): List<KeyError> {
        return findKeyErrorCheck.validateAllCaseInsensitive(pattern, actual)
    }

    fun parse(pattern: Pattern, rowValue: String): Value {
        return parseStrategy(this, pattern, rowValue)
    }

    fun actualParse(pattern: Pattern, rowValue: String): Value {
        return pattern.parse(rowValue, this)
    }

    fun invalidRequestResolver(): Resolver {
        return this.copy(patternMatchStrategy = matchAnything, parseStrategy = alwaysReturnStringValue)
    }

    fun generatedPatternsForGenerativeTests(pattern: Pattern, key: String): Sequence<ReturnValue<Pattern>> {
        return generation.generatedPatternsForGenerativeTests(this, pattern, key)
    }

    fun resolveExample(example: String?, pattern: Pattern): Value? {
        return defaultExampleResolver.resolveExample(example, pattern, this)
    }

    fun resolveExample(example: String?, pattern: List<Pattern>): Value? {
        return defaultExampleResolver.resolveExample(example, pattern, this)
    }

    fun resolveExample(example: List<String?>?, pattern: Pattern): JSONArrayValue? {
        return defaultExampleResolver.resolveExample(example, pattern, this)
    }

    fun generateHttpRequestBodies(body: Pattern, row: Row, requestBodyAsIs: Pattern): Sequence<ReturnValue<Pattern>> {
        return generation.generateHttpRequestBodies(this, body, row, requestBodyAsIs)
    }

    fun generateHttpRequestBodies(body: Pattern, row: Row): Sequence<ReturnValue<Pattern>> {
        return generation.generateHttpRequestBodies(this, body, row)
    }

    fun resolveRow(row: Row): Row {
        return generation.resolveRow(row)
    }

    fun generateKeySubLists(key: String, subList: List<String>): Sequence<List<String>> {
        return generation.generateKeySubLists(key, subList)
    }

    fun hasDictionaryToken(key: String): Boolean {
        return dictionary.containsKey(key)
    }

    fun getDictionaryToken(key: String): Value {
        return dictionary.getRawValue(key)
    }

    fun hasSeenPattern(pattern: Pattern): Boolean {
        return patternsSeenSoFar.contains(pattern.typeAlias)
    }

    fun hasSeenLookupPath(pattern: Pattern, key: String): Boolean {
        val lookupPath = lookupPath(pattern.typeAlias, key)

        return lookupPathSeen(lookupPath)
    }

    fun addPatternAsSeen(pattern: Pattern): Resolver {
        return this.copy(
            patternsSeenSoFar = pattern.typeAlias?.let { patternsSeenSoFar.plus(it) } ?: patternsSeenSoFar
        )
    }

    fun cyclePast(jsonPattern: Pattern, key: String): Resolver {
        return this.copy(cycleMarker = lookupPath(jsonPattern.typeAlias, key))
    }

    fun hasPartialKeyCheck(): Boolean {
        return this.findKeyErrorCheck.isPartial()
    }

    fun partializeKeyCheck(): Resolver {
        return this.copy(findKeyErrorCheck = findKeyErrorCheck.toPartialKeyCheck())
    }

    fun getPartialKeyCheck(): KeyCheck {
        return findKeyErrorCheck.toPartialKeyCheck()
    }

    private fun lastLookupKey(): String? = dictionaryLookupPath.substringAfterLast(".").takeIf(String::isNotBlank)
}

private fun ExactValuePattern.hasPatternToken(): Boolean {
    return this.pattern is StringValue && isPatternToken(this.pattern.string)
}

private fun <T, U> T.applyIf(original: U?, block: T.(U) -> T): T {
    val value: U = original ?: return this
    return block(value)
}
