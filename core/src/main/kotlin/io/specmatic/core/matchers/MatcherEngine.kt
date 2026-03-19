package io.specmatic.core.matchers

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.value.ScalarValue
import io.specmatic.core.value.Value
import java.util.ServiceLoader

interface MatcherEngine {
    fun patternFrom(value: ScalarValue, originalPattern: Pattern, resolver: Resolver): Pattern
    fun matchesResult(expectedValue: Value, actualValue: Value, resolver: Resolver): Result

    companion object {
        fun load(): MatcherEngine? {
            return ServiceLoader.load(MatcherEngine::class.java).firstOrNull()
        }

        fun loadOrThrow(): MatcherEngine {
            return this.load() ?: throw IllegalStateException("Matcher is not supported in Specmatic Open Source")
        }
    }
}