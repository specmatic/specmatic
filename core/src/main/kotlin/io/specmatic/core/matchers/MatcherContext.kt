package io.specmatic.core.matchers

import io.specmatic.core.BreadCrumb
import io.specmatic.core.HttpRequest
import io.specmatic.core.Resolver
import io.specmatic.core.Scenario
import io.specmatic.core.jsonoperator.JsonPointerOperator
import io.specmatic.core.jsonoperator.Optional
import io.specmatic.core.jsonoperator.RequestResponseOperator
import io.specmatic.core.jsonoperator.RootMutableJsonOperator.Companion.finalizeValue
import io.specmatic.core.jsonoperator.value.ObjectValueOperator
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.unwrapOrReturn
import io.specmatic.core.value.Value

data class MatcherContext(
    val resolver: Resolver = Resolver(),
    private val matchOperator: JsonPointerOperator<*, *> = ObjectValueOperator(),
    private val contextOperator: JsonPointerOperator<*, *> = ObjectValueOperator(),
    private val sharedOperator: ObjectValueOperator = ObjectValueOperator()
) {
    fun getValueToMatch(path: BreadCrumb): ReturnValue<Optional<Value>> {
        return matchOperator.get(path.value).finalizeValue()
    }

    fun getContextValue(path: BreadCrumb): ReturnValue<Optional<Value>> {
        return contextOperator.get(path.value).finalizeValue()
    }

    fun getSharedValue(path: BreadCrumb): ReturnValue<Optional<Value>> {
        return sharedOperator.get(path.value).finalizeValue()
    }

    fun storeIntoSharedValue(path: BreadCrumb, value: Value): ReturnValue<MatcherContext> {
        if (path.value.isEmpty()) return HasFailure("Updating of Root-Node is not allowed")
        val updatedSharedOperator = sharedOperator.upsert(path.value, value).unwrapOrReturn {return it.cast() }
        return HasValue(copy(sharedOperator = updatedSharedOperator as ObjectValueOperator))
    }

    fun finalizeSharedState(): ObjectValueOperator = sharedOperator

    companion object {
        fun from(request: HttpRequest, shared: ObjectValueOperator, scenario: Scenario): MatcherContext {
            val requestResponseOperator = RequestResponseOperator.from(request, scenario)
            return MatcherContext(matchOperator = requestResponseOperator, sharedOperator = shared)
        }
    }
}
