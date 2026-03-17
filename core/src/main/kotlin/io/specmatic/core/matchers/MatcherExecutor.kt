package io.specmatic.core.matchers

import io.specmatic.core.Resolver
import io.specmatic.core.value.Value
import io.specmatic.core.Result

interface MatcherExecutor {
    fun matchesResult(expectedValue: Value, actualValue: Value, resolver: Resolver): Result
}