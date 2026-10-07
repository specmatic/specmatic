package io.specmatic.core.matchers

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.ScalarValue
import io.specmatic.core.value.Value
import java.util.ServiceLoader

enum class MatcherResolutionMode { RUNTIME, LOAD_TIME }
interface MatcherEngine {
    fun patternFrom(value: ScalarValue, originalPattern: Pattern, resolver: Resolver): Pattern
    fun resolveValue(
        value: Value,
        pattern: Pattern,
        resolver: Resolver,
        data: JSONObjectValue = JSONObjectValue(),
        resolutionMode: MatcherResolutionMode = MatcherResolutionMode.RUNTIME,
    ): ReturnValue<Value> = HasValue(value)

    fun matchResponseValue(expectedValue: Value, actualValue: Value, resolver: Resolver): Result
    fun matchResponseValue(
        expectedValue: Value,
        actualValue: Value,
        resolver: Resolver,
        data: JSONObjectValue
    ): Result = matchResponseValue(expectedValue, actualValue, resolver)

    companion object {
        fun load(): MatcherEngine? {
            return ServiceLoader.load(MatcherEngine::class.java).firstOrNull()
        }

        fun resolveTemplates(
            pattern: Pattern,
            value: Value,
            resolver: Resolver,
            data: JSONObjectValue,
            resolutionMode: MatcherResolutionMode = MatcherResolutionMode.RUNTIME,
        ): ReturnValue<Value> {
            val matcherEngine = load() ?: return HasValue(value)
            return matcherEngine.resolveValue(value, pattern, resolver, data, resolutionMode)
        }
    }
}
