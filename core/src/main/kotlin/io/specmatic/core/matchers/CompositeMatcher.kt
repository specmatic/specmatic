package io.specmatic.core.matchers

import io.specmatic.core.BreadCrumb
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.unwrapOrReturn
import io.specmatic.core.value.Value

data class CompositeMatcher(
    private val path: BreadCrumb = BreadCrumb.from(),
    private val matchers: List<Matcher> = emptyList(),
) : Matcher {
    override val canBeExhausted: Boolean = matchers.any { it.canBeExhausted }

    override fun match(context: MatcherContext): MatcherResult {
        val dynamicMatchers = this.createDynamicMatchers(context)
        return executeMany(dynamicMatchers, context) { matcher, accContext ->
            matcher.rawExecute(accContext)
        }.let(MatcherResult::fromIfAny)
    }

    override fun createDynamicMatchers(context: MatcherContext): List<CompositeMatcher> {
        // TODO: Support dynamic matchers based on array length
        return listOf(this)
    }

    override fun rawExecute(context: MatcherContext): MatcherResult {
        val (exhaustiveMatchers, nonExhaustiveMatchers) = matchers.partition { it.canBeExhausted }
        val nonExhaustiveResult = executeMany(nonExhaustiveMatchers, context) { matcher, accContext ->
            matcher.rawExecute(accContext)
        }.let(MatcherResult::from)

        if (nonExhaustiveResult !is MatcherResult.Success) return nonExhaustiveResult
        return executeMany(exhaustiveMatchers, nonExhaustiveResult.context) { matcher, accContext ->
            matcher.rawExecute(accContext)
        }.let(MatcherResult::fromIfAny)
    }

    @MatcherKey("match")
    companion object : MatcherFactory {
        override fun parse(path: BreadCrumb, value: Value, context: MatcherContext): ReturnValue<out Matcher> {
            val properties = extractPropertiesIfExist(value)
                ?: return HasFailure("Cannot create CompositeMatcher from value '${value.displayableValue()}", path.value)

            val matchers = Matcher.parse(path, properties, context).unwrapOrReturn { return it.cast() }
            return HasValue(CompositeMatcher(path = path, matchers = matchers))
        }

        override fun canParseFrom(path: BreadCrumb, properties: Map<String, Value>): Boolean {
            return false
        }

        override fun parseFrom(path: BreadCrumb, properties: Map<String, Value>, context: MatcherContext): ReturnValue<out Matcher> {
            return HasFailure("CompositeMatcher cannot be parsed from properties", path.value)
        }

        override fun toPatternSimplified(value: Value): Pattern? {
            val properties = extractPropertiesIfExist(value) ?: return null
            return Matcher.toPatternSimplified(properties)
        }
    }
}
