package io.specmatic.core.matchers

import io.specmatic.core.BreadCrumb
import io.specmatic.core.Resolver
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.listFold
import io.specmatic.core.value.Value
import io.specmatic.test.traverse

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class MatcherKey(val key: String)

interface Matcher {
    val canBeExhausted: Boolean

    fun match(context: MatcherContext): MatcherResult {
        val dynamicMatchers = this.createDynamicMatchers(context)
        return fold(dynamicMatchers, context) { matcher, accContext ->
            matcher.execute(accContext)
        }
    }

    fun createDynamicMatchers(context: MatcherContext): List<Matcher>

    fun execute(context: MatcherContext): MatcherResult

    fun <T : Matcher> fold(matchers: List<T>, context: MatcherContext, onEach: (T, MatcherContext) -> MatcherResult): MatcherResult {
        if (matchers.isEmpty()) return MatcherResult.Success(context)
        return matchers.fold(MatcherResult.Success(context) as MatcherResult) { accResult, matcher ->
            val result = onEach(matcher, accResult.context)
            accResult.plus(result)
        }
    }

    fun <T : Matcher> foldAny(matchers: List<T>, context: MatcherContext, onEach: (T, MatcherContext) -> MatcherResult): MatcherResult {
        if (matchers.isEmpty()) return MatcherResult.Success(context)
        return matchers.fold(MatcherResult.Exhausted(context) as MatcherResult) { accResult, matcher ->
            val result = onEach(matcher, accResult.context)
            accResult.plusAny(result)
        }
    }

    companion object {
        private val defaultFactories: List<MatcherFactory> = buildList {
            add(EqualityMatcher.Companion.EqualityFactory)
            add(EqualityMatcher.Companion.NonEqualityFactory)
            add(CompositeMatcher.Companion)
            add(PatternMatcher.Companion)
            add(RepetitionMatcher.Companion)
            add(RegexMatcher.Companion)
        }

        private val registry: MatcherRegistry by lazy { MatcherRegistry.build(defaultFactories) }

        fun parse(path: BreadCrumb, value: Value, context: MatcherContext): ReturnValue<out Matcher>? {
            return registry.parse(path, value, context)
        }

        fun parse(path: BreadCrumb, properties: Map<String, Value>, context: MatcherContext): ReturnValue<List<Matcher>> {
            return registry.parse(path, properties, context)
        }

        fun toPatternSimplified(value: Value): Pattern? = registry.toPatternSimplified(value)

        fun toPatternSimplified(properties: Map<String, Value>): Pattern? = registry.toPatternSimplified(properties)

        fun from(value: Value, resolver: Resolver, prefix: String = ""): ReturnValue<List<Matcher>> {
            val context = MatcherContext(resolver = resolver)
            val pathToMatchers = value.traverse(
                prefix = prefix,
                onScalar = { value, path ->
                    val matcher = parse(BreadCrumb(path), value, context) ?: return@traverse emptyMap()
                    mapOf(path to matcher)
                },
                // TODO: Find better way to cause * as array indices
                onAssert = { _, _ -> emptyMap() },
            )
            return pathToMatchers.values.toList().listFold()
        }
    }
}
