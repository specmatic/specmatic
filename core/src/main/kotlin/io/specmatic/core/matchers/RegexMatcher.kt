package io.specmatic.core.matchers

import io.specmatic.core.BreadCrumb
import io.specmatic.core.Resolver
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.pattern.parsedScalarValue
import io.specmatic.core.pattern.unwrapOrReturn
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

data class RegexMatcher(val path: BreadCrumb, val regex: String) : Matcher {

    override val canBeExhausted: Boolean = false

    override fun createDynamicMatchers(context: MatcherContext): List<Matcher> {
        return listOf(this)
    }

    override fun execute(context: MatcherContext): MatcherResult {
        val valueToMatch = context.getValueToMatch(path).unwrapOrReturn {
            return MatcherResult.from("Couldn't extract path $path", path, context)
        }.getOrNull()

        if (valueToMatch == null) return MatcherResult.from("Couldn't find value at path $path", path, context)

        try {
            val regexPattern = StringPattern(regex = regex)
            if (regexPattern.matches(StringValue(valueToMatch.toStringLiteral()), context.resolver).isSuccess()) {
                return MatcherResult.Success(context)
            }
            return MatcherResult.from(
                errorMessage = "Expected value to match regex pattern '$regex', but got '${valueToMatch.displayableValue()}'",
                breadCrumb = path,
                context = context,
            )
        } catch (e: Exception) {
            return MatcherResult.from(
                errorMessage = "Error while performing match using regex pattern '$regex': ${e.message}",
                breadCrumb = path,
                context = context,
            )
        }
    }

    companion object : MatcherFactory {
        private const val PATTERN_KEY = "pattern"

        override fun parse(path: BreadCrumb, value: Value, context: MatcherContext): ReturnValue<RegexMatcher> {
            val properties = extractPropertiesIfExist(value)
            return if (properties.isNullOrEmpty() || !canParseFrom(path, properties)) {
                HasFailure("Cannot create RegexMatcher from value '${value.displayableValue()}", path.value)
            } else {
                parseFrom(path, properties, context)
            }
        }

        override fun canParseFrom(path: BreadCrumb, properties: Map<String, Value>): Boolean {
            return PATTERN_KEY in properties
        }

        override fun parseFrom(path: BreadCrumb, properties: Map<String, Value>, context: MatcherContext): ReturnValue<RegexMatcher> {
            val patternValue = properties[PATTERN_KEY] ?: return HasFailure("Expected key '$PATTERN_KEY' to be present", path.value)
            if (patternValue !is StringValue) return HasFailure(
                "Expected key '$PATTERN_KEY' to be a string",
                path.value,
            )

            return HasValue(RegexMatcher(path, patternValue.nativeValue))
        }

        override fun toPatternSimplified(value: Value): Pattern? {
            if (value !is StringValue) return null
            val properties = extractPropertiesIfExist(value) ?: return null
            return toPatternSimplified(properties)
        }

        override fun toPatternSimplified(properties: Map<String, Value>): Pattern? {
            if (!canParseFrom(BreadCrumb.from(), properties)) return null
            val regex = properties.getValue(PATTERN_KEY) as? StringValue ?: return null

            val pattern = StringPattern(regex = regex.nativeValue)
            val value = parsedScalarValue(
                pattern.generate(Resolver()).toStringLiteral()
            )
            return ExactValuePattern(pattern = value)
        }
    }
}

