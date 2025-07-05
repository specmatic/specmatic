package io.specmatic.core.pattern

import io.specmatic.core.*
import io.specmatic.core.Result.Failure
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.*

data class AllOfPattern(
    override val pattern: List<Pattern>,
    override val typeAlias: String? = null,
    override val example: String? = null,
    override val extensions: Map<String, Any> = pattern.extractCombinedExtensions()
) : Pattern, HasDefaultExample, PossibleJsonObjectPatternContainer {

    override fun equals(other: Any?): Boolean = other is AllOfPattern && other.pattern == this.pattern

    override fun hashCode(): Int = pattern.hashCode()

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if (pattern.isEmpty()) {
            return Result.Success()
        }

        val results = pattern.map { subPattern ->
            subPattern.matches(sampleData, resolver)
        }

        val failures = results.filterIsInstance<Failure>()
        if (failures.isNotEmpty()) {
            return Failure.fromFailures(failures)
        }

        return Result.Success()
    }

    override fun generate(resolver: Resolver): Value {
        if (pattern.isEmpty()) {
            return EmptyString
        }

        return resolver.resolveExample(example, pattern)
            ?: generateValue(resolver)
    }

    private fun generateValue(resolver: Resolver): Value {
        // For AllOf, we need to generate a value that satisfies all patterns
        // Start with the first pattern's generated value and ensure it matches all others
        val baseValue = pattern.first().generate(resolver)
        
        // Check if the base value satisfies all patterns
        val allMatch = pattern.all { subPattern ->
            subPattern.matches(baseValue, resolver) is Result.Success
        }
        
        if (allMatch) {
            return baseValue
        }
        
        // If not all match, try to find a value that works for all
        // This is a simplified approach - in practice, this might need more sophisticated logic
        for (subPattern in pattern) {
            val candidateValue = subPattern.generate(resolver)
            val allMatch = pattern.all { otherPattern ->
                otherPattern.matches(candidateValue, resolver) is Result.Success
            }
            if (allMatch) {
                return candidateValue
            }
        }
        
        // Fallback to the base value if no better option is found
        return baseValue
    }

    override fun fixValue(value: Value, resolver: Resolver): Value {
        if (resolver.matchesPattern(null, this, value).isSuccess()) {
            return value
        }

        // Try to fix the value against all patterns and return the first successful fix
        for (subPattern in pattern) {
            try {
                val fixedValue = subPattern.fixValue(value, resolver)
                if (matches(fixedValue, resolver) is Result.Success) {
                    return fixedValue
                }
            } catch (e: Exception) {
                // Continue to next pattern
            }
        }

        // If no pattern can fix it, return the original value
        return value
    }

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> {
        return sequence {
            // Generate new patterns for each sub-pattern
            val newSubPatterns = pattern.map { subPattern ->
                subPattern.newBasedOn(row, resolver).toList()
            }

            // Combine the results to create new AllOfPatterns
            if (newSubPatterns.all { it.isNotEmpty() }) {
                // Simple approach: take first result from each sub-pattern
                val firstResults = newSubPatterns.map { results ->
                    results.firstOrNull()
                }.filterNotNull()

                if (firstResults.size == newSubPatterns.size) {
                    val allSuccessful = firstResults.all { it is HasValue }
                    if (allSuccessful) {
                        val newPatterns = firstResults.map { (it as HasValue).value }
                        yield(HasValue(AllOfPattern(newPatterns, typeAlias, example, extensions)))
                    }
                }
            }
        }
    }

    override fun newBasedOn(resolver: Resolver): Sequence<Pattern> {
        return sequence {
            // Generate combinations of new patterns from all sub-patterns
            val newSubPatterns = pattern.map { subPattern ->
                subPattern.newBasedOn(resolver).toList()
            }

            if (newSubPatterns.all { it.isNotEmpty() }) {
                // For simplicity, just take the first new pattern from each sub-pattern
                val firstNewPatterns = newSubPatterns.map { it.first() }
                yield(AllOfPattern(firstNewPatterns, typeAlias, example, extensions))
            }
        }
    }

    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> {
        return sequence {
            // For negative tests, we can generate patterns that fail any one of the sub-patterns
            for ((index, subPattern) in pattern.withIndex()) {
                val negativeSubPatterns = subPattern.negativeBasedOn(row, resolver, config)
                for (negativeSubPattern in negativeSubPatterns) {
                    when (negativeSubPattern) {
                        is HasValue -> {
                            // Replace the sub-pattern at index with the negative pattern
                            val newPatterns = pattern.toMutableList()
                            newPatterns[index] = negativeSubPattern.value
                            yield(HasValue(AllOfPattern(newPatterns, typeAlias, example, extensions)))
                        }
                        is HasFailure -> yield(negativeSubPattern)
                        is HasException -> yield(negativeSubPattern)
                    }
                }
            }
        }
    }

    override fun parse(value: String, resolver: Resolver): Value {
        // Try to parse with each pattern and return the first successful parse
        for (subPattern in pattern) {
            try {
                val parsedValue = subPattern.parse(value, resolver)
                if (matches(parsedValue, resolver) is Result.Success) {
                    return parsedValue
                }
            } catch (e: Exception) {
                // Continue to next pattern
            }
        }

        // If no pattern can parse it successfully, use the first pattern as fallback
        return pattern.firstOrNull()?.parse(value, resolver) ?: StringValue(value)
    }

    override val typeName: String
        get() = if (typeAlias != null) {
            typeAlias
        } else {
            "(${pattern.joinToString(" and ") { withoutPatternDelimiters(it.typeName) }})"
        }

    override fun toNullable(defaultValue: String?): Pattern {
        return AllOfPattern(
            pattern.map { it.toNullable(defaultValue) },
            typeAlias,
            example,
            extensions
        )
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        if (pattern.isEmpty()) {
            throw ContractException("AllOfPattern doesn't have any types, so can't infer which type of list to wrap the given value in")
        }

        return pattern.first().listOf(valueList, resolver)
    }

    override fun patternSet(resolver: Resolver): List<Pattern> {
        return pattern.flatMap { it.patternSet(resolver) }
    }

    override fun encompasses(
        otherPattern: Pattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        // For AllOf to encompass another pattern, the other pattern must be encompassed by ALL sub-patterns
        val results = pattern.map { subPattern ->
            subPattern.encompasses(otherPattern, thisResolver, otherResolver, typeStack)
        }

        val failures = results.filterIsInstance<Failure>()
        if (failures.isNotEmpty()) {
            return Failure.fromFailures(failures)
        }

        return Result.Success()
    }

    override fun addTypeAliasesToConcretePattern(concretePattern: Pattern, resolver: Resolver, typeAlias: String?): Pattern {
        return AllOfPattern(
            pattern.map { it.addTypeAliasesToConcretePattern(concretePattern, resolver, this.typeAlias ?: typeAlias) },
            this.typeAlias ?: typeAlias,
            example,
            extensions
        )
    }

    override fun fillInTheBlanks(value: Value, resolver: Resolver, removeExtraKeys: Boolean): ReturnValue<Value> {
        // For AllOf, we need to fill in blanks that satisfy all patterns
        var currentValue = value
        
        for (subPattern in pattern) {
            when (val result = subPattern.fillInTheBlanks(currentValue, resolver, removeExtraKeys)) {
                is HasValue -> currentValue = result.value
                is HasFailure -> return result
                is HasException -> return result
            }
        }
        
        return HasValue(currentValue)
    }

    override fun resolveSubstitutions(
        substitution: Substitution,
        value: Value,
        resolver: Resolver,
        key: String?
    ): ReturnValue<Value> {
        // Try to resolve substitutions with each pattern
        for (subPattern in pattern) {
            when (val result = subPattern.resolveSubstitutions(substitution, value, resolver, key)) {
                is HasValue -> {
                    // Check if the resolved value matches all patterns
                    if (matches(result.value, resolver) is Result.Success) {
                        return result
                    }
                }
                is HasFailure -> continue
                is HasException -> continue
            }
        }

        return HasFailure(Failure("Could not resolve substitutions for AllOfPattern"))
    }

    override fun getTemplateTypes(key: String, value: Value, resolver: Resolver): ReturnValue<Map<String, Pattern>> {
        val initialValue: ReturnValue<Map<String, Pattern>> = HasValue(emptyMap())

        return pattern.fold(initialValue) { acc, subPattern ->
            val templateTypes = subPattern.getTemplateTypes(key, value, resolver)
            acc.assimilate(templateTypes) { data, additional -> data + additional }
        }
    }

    override fun removeKeysNotPresentIn(keys: Set<String>, resolver: Resolver): Pattern {
        if (keys.isEmpty()) return this

        return AllOfPattern(
            pattern.map { subPattern ->
                if (subPattern !is PossibleJsonObjectPatternContainer) return@map subPattern
                subPattern.removeKeysNotPresentIn(keys, resolver)
            },
            typeAlias,
            example,
            extensions
        )
    }

    override fun jsonObjectPattern(resolver: Resolver): JSONObjectPattern? {
        // Try to get a JSON object pattern from the first sub-pattern that provides one
        for (subPattern in pattern) {
            when (subPattern) {
                is JSONObjectPattern -> return subPattern
                is PossibleJsonObjectPatternContainer -> {
                    subPattern.jsonObjectPattern(resolver)?.let { return it }
                }
            }
        }
        return null
    }

    override fun eliminateOptionalKey(value: Value, resolver: Resolver): Value {
        // Apply optional key elimination to all sub-patterns
        var currentValue = value
        for (subPattern in pattern) {
            currentValue = subPattern.eliminateOptionalKey(currentValue, resolver)
        }
        return currentValue
    }
}