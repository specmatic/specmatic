package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.Result.Failure
import io.specmatic.core.StandardRuleViolation
import io.specmatic.core.patternMismatchResult
import io.specmatic.core.value.EmptyString
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value

class AnyOfPattern(
    override val pattern: List<Pattern>,
    private val key: String? = null,
    override val typeAlias: String? = null,
    override val example: String? = null,
    override val discriminator: Discriminator? = null,
    override val extensions: Map<String, Any> = pattern.extractCombinedExtensions(),
    private val delegate: AnyPattern =
        AnyPattern(
            pattern = pattern,
            key = key,
            typeAlias = typeAlias,
            example = example,
            discriminator = discriminator,
            extensions = extensions,
        ),
) : Pattern by delegate,
    HasDefaultExample by delegate,
    PossibleJsonObjectPatternContainer by delegate,
    SubSchemaCompositePattern by delegate {
    override fun matches(
        sampleData: Value?,
        resolver: Resolver,
    ): Result {
        if (discriminator != null) {
            return delegate.matches(sampleData, resolver)
        }

        val updatedPatterns = delegate.getUpdatedPattern(resolver)

        val matchResults =
            updatedPatterns.map { innerPattern ->
                resolver.matchesPattern(key, innerPattern, sampleData ?: EmptyString)
            }

        val jsonMatchAnalysis =
            if (sampleData is JSONObjectValue) {
                analyzeJSONObjectKeys(sampleData, updatedPatterns, matchResults, resolver)
            } else {
                JsonMatchAnalysis.empty()
            }

        if (jsonMatchAnalysis.isClean() && matchResults.any { it.isSuccess() }) {
            return Result.Success()
        }

        val delegateResult = delegate.matches(sampleData, resolver)

        val failures = mutableListOf<Failure>()
        if (jsonMatchAnalysis.unknownKeys.isNotEmpty()) {
            failures.add(Failure(
                message = "Key(s) ${jsonMatchAnalysis.unknownKeys.joinToString(", ")} are not declared in any anyOf option",
                ruleViolation = StandardRuleViolation.ANY_OF_UNKNOWN_KEY
            ))
        }

        if (jsonMatchAnalysis.keysDeclaredButUnmatched.isNotEmpty()) {
            failures.add(Failure(
                message = "Key(s) ${jsonMatchAnalysis.keysDeclaredButUnmatched.joinToString(", ")} did not match any anyOf option that declares them",
                ruleViolation = StandardRuleViolation.ANY_OF_NO_MATCHING_SCHEMA
            ))
        }

        failures.addAll(jsonMatchAnalysis.relevantPatternFailures)

        if (delegateResult is Failure) {
            failures.add(delegateResult)
        }

        return when (failures.size) {
            0 -> delegateResult
            1 -> failures.first()
            else -> Failure.fromFailures(failures)
        }
    }

    override fun generate(resolver: Resolver): Value = delegate.generate(resolver)

    override fun fixValue(
        value: Value,
        resolver: Resolver,
    ): Value {
        val updatedPatterns = delegate.getUpdatedPattern(resolver)
        val firstPattern =
            updatedPatterns.firstOrNull { it !is NullPattern }
                ?: updatedPatterns.firstOrNull()
                ?: throw ContractException("Cannot fix value: anyOf has no subschemas")

        val updatedResolver = resolver.updateLookupPath(typeAlias)
        return firstPattern.fixValue(value, updatedResolver)
    }

    override fun encompasses(
        otherPattern: Pattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack,
    ): Result {
        if (otherPattern !is AnyOfPattern) {
            return patternMismatchResult(this, otherPattern, thisResolver.mismatchMessages)
        }

        val myPatterns = patternSet(thisResolver)

        val encompassResults =
            otherPattern.pattern.map { legacyPattern ->
                val results =
                    myPatterns.asSequence().map { candidate ->
                        biggerEncompassesSmaller(candidate, legacyPattern, thisResolver, otherResolver, typeStack)
                    }

                results.find { it is Result.Success } ?: results.firstOrNull()?.let {
                    when (it) {
                        is Failure -> it
                        else -> Result.Success()
                    }
                } ?: Failure("Could not find matching subschema in anyOf pattern")
            }

        return Result.fromResults(encompassResults)
    }

    override fun equals(other: Any?): Boolean = other is AnyOfPattern && other.pattern == this.pattern

    override fun hashCode(): Int = pattern.hashCode()

    override fun newBasedOn(
        row: Row,
        resolver: Resolver,
    ): Sequence<ReturnValue<Pattern>> {
        val newPatterns = delegate.newBasedOn(row, resolver)

        val resolvedPatterns = pattern.map { resolvedHop(it, resolver) }
        if (resolvedPatterns.all { it is JSONObjectPattern } && discriminator == null) {
            val combinedPattern = combineJSONPatterns(pattern.filterIsInstance<JSONObjectPattern>())
            return newPatterns.plus(combinedPattern.newBasedOn(row, resolver))
        }

        return newPatterns
    }

    private fun combineJSONPatterns(pattern: List<JSONObjectPattern>): Pattern {
        val patternMap =
            pattern.fold(emptyMap<String, Pattern>()) { acc, next ->
                val updated = acc + next.pattern

                val optionalKeys = acc.keys.filter { it.endsWith("?") }
                val mandatoryKeyExists = optionalKeys.filter { withoutOptionality(it) in updated }

                mandatoryKeyExists.fold(updated) { withoutClobberedOptionals, key ->
                    withoutClobberedOptionals - key
                }
            }

        return JSONObjectPattern(patternMap)
    }

    override val typeName: String
        get() =
            when {
                pattern.isEmpty() -> "(anyOf)"
                else ->
                    pattern.joinToString(
                        prefix = "(anyOf ",
                        postfix = ")",
                        separator = " or ",
                    ) { withoutPatternDelimiters(it.typeName) }
            }

    private fun analyzeJSONObjectKeys(
        value: JSONObjectValue,
        patterns: List<Pattern>,
        matchResults: List<Result>,
        resolver: Resolver,
    ): JsonMatchAnalysis {
        val patternKeysPerPattern = patterns.map { it.extractObjectKeys(resolver) }
        val declaredKeys = patternKeysPerPattern.flatten().toSet()
        val presentKeys = value.jsonObject.keys

        val unknownKeys = presentKeys.filter { key -> key !in declaredKeys }

        val keysWithSuccessfulMatch =
            patternKeysPerPattern
                .zip(matchResults)
                .filter { (_, result) -> result.isSuccess() }
                .flatMap { (keys, _) -> keys }
                .toSet()

        val keysDeclaredButUnmatched =
            presentKeys.filter { key -> key in declaredKeys && key !in keysWithSuccessfulMatch }

        val relevantPatternFailures =
            patternKeysPerPattern
                .withIndex()
                .filter { (index, keys) ->
                    keys.intersect(presentKeys).isNotEmpty() && matchResults.getOrNull(index) is Failure
                }
                .mapNotNull { (index, _) -> matchResults[index] as? Failure }

        return JsonMatchAnalysis(unknownKeys, keysDeclaredButUnmatched, relevantPatternFailures)
    }

    private data class JsonMatchAnalysis(
        val unknownKeys: List<String>,
        val keysDeclaredButUnmatched: List<String>,
        val relevantPatternFailures: List<Failure>,
    ) {
        fun isClean(): Boolean = unknownKeys.isEmpty() && keysDeclaredButUnmatched.isEmpty()

        companion object {
            fun empty() = JsonMatchAnalysis(emptyList(), emptyList(), emptyList())
        }
    }

    private fun Pattern.extractObjectKeys(
        resolver: Resolver,
        visited: Set<Pattern> = emptySet(),
    ): Set<String> {
        val resolved = resolvedHop(this, resolver)
        if (resolved in visited) return emptySet()

        val nextVisited = visited + resolved

        return when (resolved) {
            is JSONObjectPattern ->
                resolved.pattern.keys
                    .map(::withoutOptionality)
                    .toSet()

            is SubSchemaCompositePattern ->
                resolved.pattern
                    .flatMap { it.extractObjectKeys(resolver, nextVisited) }
                    .toSet()

            is PossibleJsonObjectPatternContainer ->
                resolved
                    .jsonObjectPattern(resolver)
                    ?.pattern
                    ?.keys
                    ?.map(::withoutOptionality)
                    ?.toSet() ?: emptySet()

            else -> emptySet()
        }
    }
}
