package io.specmatic.core.matchers

import io.specmatic.core.BreadCrumb
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.listFold
import io.specmatic.core.value.Value
import io.specmatic.test.traverse

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class MatcherKey(val key: String)

sealed interface MatcherResult {
    data class MisMatch(val failure: Result.Failure) : MatcherResult

    data class Success(val context: MatcherContext): MatcherResult

    data object Exhausted : MatcherResult

    fun toReturnValue(): ReturnValue<MatcherContext> {
        return when (this) {
            is Success -> HasValue(this.context)
            is MisMatch -> HasFailure(this.failure)
            is Exhausted -> HasFailure(Result.Failure("Matcher has been exhausted"))
        }
    }

    companion object {
        fun fromIfAny(result: Pair<MatcherContext, List<Result>>): MatcherResult {
            if (result.second.any { it.isSuccess() } || result.second.isEmpty()) return Success(result.first)
            val failures = result.second.filterIsInstance<Result.Failure>()
            return if (failures.all { it.isPartial }) {
                return Exhausted
            } else {
                MisMatch(Result.fromResults(result.second) as Result.Failure)
            }
        }

        fun from(result: Pair<MatcherContext, List<Result>>): MatcherResult {
            val finalResult = Result.fromResults(result.second)
            if (finalResult.isSuccess()) return Success(result.first)

            val failures = result.second.filterIsInstance<Result.Failure>()
            return if (failures.all { it.isPartial }) {
                return Exhausted
            } else {
                MisMatch(Result.fromResults(result.second) as Result.Failure)
            }
        }

        fun from(errorMessage: String, breadCrumb: BreadCrumb, isExhausted: Boolean = false): MisMatch {
            return MisMatch(Result.Failure(message = errorMessage, breadCrumb = breadCrumb.value, isPartial = isExhausted))
        }

        fun from(returnValue: ReturnValue<MatcherContext>): MatcherResult {
            return returnValue.realise(
                hasValue = { it, _ -> Success(it) },
                orFailure = { f -> MisMatch(f.toFailure()) },
                orException = { e -> MisMatch(e.toHasFailure().toFailure()) },
            )
        }
    }
}

interface Matcher {
    val canBeExhausted: Boolean

    fun match(context: MatcherContext): MatcherResult {
        val dynamicMatchers = this.createDynamicMatchers(context)
        return executeMany(dynamicMatchers, context) { matcher, accContext ->
            matcher.rawExecute(accContext)
        }.let(MatcherResult::from)
    }

    fun createDynamicMatchers(context: MatcherContext): List<Matcher>

    fun rawExecute(context: MatcherContext): MatcherResult

    fun <T : Matcher> executeMany(matchers: List<T>, context: MatcherContext, onEach: (T, MatcherContext) -> MatcherResult): Pair<MatcherContext, List<Result>> {
        return matchers.fold(context to emptyList<Result>()) { (accContext, results), matcher ->
            when (val result = onEach(matcher, accContext)) {
                is MatcherResult.Success -> result.context to results.plus(Result.Success())
                is MatcherResult.MisMatch -> accContext to results.plus(result.failure)
                else -> accContext to results.plus(Result.Failure("Exhausted", isPartial = true))
            }
        }
    }

    companion object {
        private val defaultFactories: List<MatcherFactory> = buildList {
            add(CompositeMatcher.Companion)
            add(PatternMatcher.Companion)
            add(RepetitionMatcher.Companion)
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
