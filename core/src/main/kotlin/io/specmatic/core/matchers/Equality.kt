package io.specmatic.core.matchers

import io.specmatic.core.BreadCrumb
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.unwrapOrReturn
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

enum class EqualityStrategy(val value: String) {
    EQUALS("eq"),
    NOT_EQUALS("neq");

    fun matches(actual: Value, expected: Value): Boolean = when (this) {
        EQUALS -> actual == expected
        NOT_EQUALS -> actual != expected
    }

    companion object {
        fun from(value: String): ReturnValue<EqualityStrategy> {
            return entries.firstOrNull {
                it.value.equals(value, ignoreCase=true)
            }?.let(::HasValue) ?: HasFailure("Invalid EqualityStrategy. '$value' must be one of any, each")
        }
    }
}

data class EqualityMatcher(private val path: BreadCrumb, private val value: Value, private val strategy: EqualityStrategy) : Matcher {
    override val canBeExhausted: Boolean = false

    override fun createDynamicMatchers(context: MatcherContext): List<Matcher> {
        // TODO: Support dynamic matchers based on array length
        return listOf(this)
    }

    override fun rawExecute(context: MatcherContext): MatcherResult {
        val valueToMatch = context.getValueToMatch(path).unwrapOrReturn {
            return MatcherResult.from("Couldn't extract path $path", path, context)
        }.getOrNull()

        if (valueToMatch == null) return MatcherResult.from("Couldn't find value at path $path", path, context)
        val matches = strategy.matches(valueToMatch, value)
        if (matches) return MatcherResult.Success(context)

        val operatorText = when (strategy) {
            EqualityStrategy.EQUALS -> "equal to"
            EqualityStrategy.NOT_EQUALS -> "not equal to"
        }

        return MatcherResult.from(
            errorMessage = "Expected value to be $operatorText ${value.displayableValue()}, but got ${valueToMatch.displayableValue()}",
            breadCrumb = path,
            context = context,
        )
    }

    companion object {
        private const val VALUE_KEY = "exact"
        private const val EQUALITY_KEY = "matchType"

        abstract class BaseEqualityFactory(private val defaultStrategy: EqualityStrategy, private val createPattern: (Value) -> Pattern?) : MatcherFactory {
            override fun parse(path: BreadCrumb, value: Value, context: MatcherContext): ReturnValue<out Matcher> {
                val properties = extractPropertiesIfExist(value)
                return if (properties.isNullOrEmpty() || !canParseFrom(path, properties)) {
                    HasValue(EqualityMatcher(path, value, defaultStrategy))
                } else {
                    parseFrom(path, properties, context)
                }
            }

            override fun canParseFrom(path: BreadCrumb, properties: Map<String, Value>): Boolean {
                return VALUE_KEY in properties
            }

            override fun parseFrom(path: BreadCrumb, properties: Map<String, Value>, context: MatcherContext): ReturnValue<out Matcher> {
                val value = properties[VALUE_KEY] ?: return HasFailure("Expected key '$VALUE_KEY' to be present", path.value)
                val strategyKey = properties.getOrDefault(EQUALITY_KEY, StringValue("eq"))
                if (strategyKey !is StringValue) return HasFailure(
                    "Expected key '$EQUALITY_KEY' to be eq or neq",
                    path.value,
                )

                val strategy = EqualityStrategy.from(strategyKey.nativeValue).unwrapOrReturn { return it.cast() }
                return HasValue(EqualityMatcher(path, value, strategy))
            }

            override fun toPatternSimplified(value: Value): Pattern? {
                val properties = extractPropertiesIfExist(value)
                return if (properties.isNullOrEmpty() || !canParseFrom(BreadCrumb.from(), properties)) {
                    if (defaultStrategy == EqualityStrategy.EQUALS) createPattern(value) else null
                } else {
                    toPatternSimplified(properties)
                }
            }

            override fun toPatternSimplified(properties: Map<String, Value>): Pattern? {
                val value = properties[VALUE_KEY] ?: return null
                val strategyKey = properties.getOrDefault(EQUALITY_KEY, StringValue("eq"))
                val equalityStrategy = EqualityStrategy.from(strategyKey.toStringLiteral())
                if (equalityStrategy.withDefault(EqualityStrategy.EQUALS) { it } != defaultStrategy) return null
                return createPattern(value)
            }
        }

        @MatcherKey("eq")
        object EqualityFactory : BaseEqualityFactory(
            defaultStrategy = EqualityStrategy.EQUALS,
            createPattern = { ExactValuePattern(it) },
        )

        @MatcherKey("neq")
        object NonEqualityFactory : BaseEqualityFactory(
            defaultStrategy = EqualityStrategy.NOT_EQUALS,
            createPattern = { it.deepPattern() },
        )
    }
}
