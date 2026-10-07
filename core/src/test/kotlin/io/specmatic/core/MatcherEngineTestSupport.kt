package io.specmatic.core

import io.specmatic.core.matchers.MatcherEngine
import io.specmatic.core.matchers.MatcherResolutionMode
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.ScalarValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

internal data class MatcherResolutionCall(
    val pattern: Pattern,
    val value: Value,
    val data: JSONObjectValue,
    val resolutionMode: MatcherResolutionMode = MatcherResolutionMode.RUNTIME,
)

internal class MatcherEngineTestSupport : MatcherEngine {
    val resolutionCalls = mutableListOf<MatcherResolutionCall>()

    override fun resolveValue(
        value: Value,
        pattern: Pattern,
        resolver: Resolver,
        data: JSONObjectValue,
        resolutionMode: MatcherResolutionMode
    ): ReturnValue<Value> {
        resolutionCalls.add(MatcherResolutionCall(pattern, value, data, resolutionMode))
        return HasValue(value.resolveExactMatcherReferences(data))
    }

    override fun patternFrom(value: ScalarValue, originalPattern: Pattern, resolver: Resolver): Pattern {
        return originalPattern
    }

    override fun matchResponseValue(expectedValue: Value, actualValue: Value, resolver: Resolver): Result {
        return Result.Success()
    }
}

private val exactMatcherReference = Regex($$"""^\$match\(exact:\s*\$\((data\.[^()]+)\)\)$""")
private fun Value.resolveExactMatcherReferences(data: JSONObjectValue): Value = when (this) {
    is StringValue -> {
        val reference = exactMatcherReference.matchEntire(string)?.groupValues?.get(1)
        if (reference == null) this else data.findFirstChildByPath(reference) ?: this
    }
    is JSONObjectValue -> copy(jsonObject = jsonObject.mapValues { (_, value) -> value.resolveExactMatcherReferences(data) })
    is JSONArrayValue -> JSONArrayValue(list.map { it.resolveExactMatcherReferences(data) })
    else -> this
}
