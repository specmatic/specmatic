package io.specmatic.core.matchers

import io.specmatic.core.BreadCrumb
import io.specmatic.core.DefaultKeyCheck
import io.specmatic.core.Resolver
import io.specmatic.core.pattern.DeferredPattern
import io.specmatic.core.pattern.HasException
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.NullPattern
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.breadCrumb
import io.specmatic.core.pattern.unwrapOrReturn
import io.specmatic.core.pattern.withPatternDelimiters
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

enum class PatternMatchStrategy(val value: String) {
    FULL("full"),
    PARTIAL("partial");

    fun update(resolver: Resolver): Resolver {
        return if (this == PARTIAL) {
            resolver.partializeKeyCheck()
        } else {
            resolver.copy(findKeyErrorCheck = DefaultKeyCheck)
        }
    }

    companion object {
        fun from(value: String): ReturnValue<PatternMatchStrategy> {
            return entries.firstOrNull {
                it.value.equals(value, ignoreCase=true)
            }?.let(::HasValue) ?: HasFailure("Invalid PatternMatchStrategy '$value' must be one of full, partial")
        }
    }
}

class PatternMatcher(
    private val path: BreadCrumb = BreadCrumb.from(),
    private val pattern: Pattern = NullPattern,
    private val strategy: PatternMatchStrategy = PatternMatchStrategy.FULL,
) : Matcher {
    override val canBeExhausted: Boolean = false

    override fun createDynamicMatchers(context: MatcherContext): List<PatternMatcher> {
        // TODO: Support dynamic matchers based on array length
        return listOf(this)
    }

    override fun rawExecute(context: MatcherContext): MatcherResult {
        val valueToMatch = context.getValueToMatch(path).unwrapOrReturn {
            return MatcherResult.from("Couldn't extract path $path", path, context)
        }.getOrNull()

        if (valueToMatch == null) return MatcherResult.from("Couldn't find value at path $path", path, context)
        val updatedResolver = strategy.update(context.resolver)

        val returnValue = pattern.matches(valueToMatch, updatedResolver).toReturnValue(context)
        return MatcherResult.from(returnValue.breadCrumb(path.value), context)
    }

    @MatcherKey("pattern")
    companion object : MatcherFactory {
        private const val DATA_TYPE_KEY = "dataType"
        private const val PARTIAL_KEY = "partial"

        override fun parse(path: BreadCrumb, value: Value, context: MatcherContext): ReturnValue<out Matcher> {
            if (value !is StringValue) return HasFailure("Invalid '$DATA_TYPE_KEY', expected String got ${value.displayableValue()}", path.value)
            val properties = extractPropertiesIfExist(value)
            return if (properties.isNullOrEmpty() || !canParseFrom(path, properties)) {
                val pattern = getPatternFromResolver(value.nativeValue, context.resolver).unwrapOrReturn {
                    return it.breadCrumb(path.value).cast()
                }
                HasValue(PatternMatcher(path, pattern))
            } else {
                parseFrom(path, properties, context)
            }
        }

        override fun canParseFrom(path: BreadCrumb, properties: Map<String, Value>): Boolean {
            return DATA_TYPE_KEY in properties
        }

        override fun parseFrom(path: BreadCrumb, properties: Map<String, Value>, context: MatcherContext): ReturnValue<out Matcher> {
            if (!canParseFrom(path, properties)) return HasFailure("Missing or invalid property '$DATA_TYPE_KEY'", path.value)

            val dataType = properties.getValue(DATA_TYPE_KEY)
            if (dataType !is StringValue) return HasFailure("Expected property '$DATA_TYPE_KEY' to be string")

            val patternStrategy = properties.getOrElse(PARTIAL_KEY) { StringValue("full") }
            if (patternStrategy !is StringValue) return HasFailure("Expected property '$PARTIAL_KEY' to be string")

            val pattern = getPatternFromResolver(dataType.nativeValue, context.resolver).unwrapOrReturn {
                return it.breadCrumb(path.value).cast()
            }
            val strategy = PatternMatchStrategy.from(patternStrategy.nativeValue).unwrapOrReturn {
                return it.breadCrumb(path.value).cast()
            }

            return HasValue(PatternMatcher(path, pattern, strategy))
        }

        private fun getPatternFromResolver(value: String, resolver: Resolver): ReturnValue<Pattern> {
            return runCatching {
                resolver.getPattern(withPatternDelimiters(value))
            }.map(::HasValue).getOrElse(::HasException)
        }

        override fun toPatternSimplified(value: Value): Pattern? {
            if (value !is StringValue) return null
            val properties = extractPropertiesIfExist(value)
            return if (properties.isNullOrEmpty() || !canParseFrom(BreadCrumb.from(), properties)) {
                DeferredPattern(withPatternDelimiters(value.nativeValue))
            } else {
                toPatternSimplified(properties)
            }
        }

        override fun toPatternSimplified(properties: Map<String, Value>): Pattern? {
            if (!canParseFrom(BreadCrumb.from(), properties)) return null
            val dataType = properties.getValue(DATA_TYPE_KEY) as? StringValue ?: return null
            return DeferredPattern(withPatternDelimiters(dataType.nativeValue))
        }
    }
}
