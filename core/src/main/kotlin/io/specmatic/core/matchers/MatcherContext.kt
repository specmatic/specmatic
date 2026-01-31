package io.specmatic.core.matchers

import io.specmatic.core.BreadCrumb
import io.specmatic.core.HttpRequest
import io.specmatic.core.Resolver
import io.specmatic.core.Scenario
import io.specmatic.core.jsonoperator.JsonPointerOperator
import io.specmatic.core.jsonoperator.Optional
import io.specmatic.core.jsonoperator.RequestResponseOperator
import io.specmatic.core.jsonoperator.RootMutableJsonOperator.Companion.finalizeValue
import io.specmatic.core.jsonoperator.getOrElse
import io.specmatic.core.jsonoperator.value.ArrayValueOperator
import io.specmatic.core.jsonoperator.value.ObjectValueOperator
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.unwrapOrReturn
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value
import kotlin.math.max

data class MatcherContext(
    val resolver: Resolver = Resolver(),
    val maxTimes: Int = -1,
    private val matchOperator: JsonPointerOperator<*, *> = ObjectValueOperator(),
    private val sharedOperator: ObjectValueOperator = ObjectValueOperator(),
    private val runSharedOperator: ObjectValueOperator = ObjectValueOperator()
) {
    fun withUpdatedTimes(times: Int): MatcherContext = copy(maxTimes = max(maxTimes, times))

    fun getValueToMatch(path: BreadCrumb): ReturnValue<Optional<Value>> {
        return matchOperator.get(path.value).finalizeValue()
    }

    fun getSharedValue(path: BreadCrumb): ReturnValue<Optional<Value>> {
        return sharedOperator.get(path.value).finalizeValue()
    }

    fun getJsonArray(path: BreadCrumb, orElse: JSONArrayValue = JSONArrayValue()): ReturnValue<JSONArrayValue> {
        val valueInShared = getSharedValue(path).unwrapOrReturn { return it.cast() }
        return HasValue(valueInShared.getOrElse { orElse } as? JSONArrayValue ?: orElse)
    }

    fun appendToJsonArray(path: BreadCrumb, value: Value): ReturnValue<MatcherContext> {
        val nextArrayItemPath = path.plus(ArrayValueOperator.APPEND.toString())
        return sharedOperator.insert(nextArrayItemPath.value, value).ifValue {
            copy(sharedOperator = it as ObjectValueOperator)
        }
    }

    fun addToCurrentExhaustionChecks(path: BreadCrumb, value: Value): MatcherContext {
        val updatedRun = runSharedOperator.rawInsert(path.value, value)
        return copy(runSharedOperator = updatedRun)
    }

    fun getCurrentExhaustionChecks(): JSONObjectValue {
        return runSharedOperator.finalize().value
    }

    fun getSharedExhaustionChecks(): JSONArrayValue {
        return getJsonArray(SHARED_EXHAUSTION_KEY).withDefault(JSONArrayValue()) { it }
    }

    fun finalizeSharedState(): ObjectValueOperator {
        val exhaustionCheckValuesThisRun = getCurrentExhaustionChecks()
        val updatedStore = appendToJsonArray(SHARED_EXHAUSTION_KEY, exhaustionCheckValuesThisRun).unwrapOrReturn { return sharedOperator }
        return updatedStore.sharedOperator
    }

    companion object {
        private val SHARED_EXHAUSTION_KEY = BreadCrumb("EXHAUSTION_VALUES")

        fun from(request: HttpRequest, shared: ObjectValueOperator, scenario: Scenario): MatcherContext {
            val requestResponseOperator = RequestResponseOperator.from(request, scenario)
            return MatcherContext(matchOperator = requestResponseOperator, sharedOperator = shared)
        }
    }
}
