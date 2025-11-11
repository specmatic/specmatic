package io.specmatic.core.matchers

import io.specmatic.core.BreadCrumb
import io.specmatic.core.Result
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.value.JSONObjectValue

sealed interface MatcherResult {
    val context: MatcherContext

    fun plus(other: MatcherResult): MatcherResult

    data class MisMatch(val failure: Result.Failure, override val context: MatcherContext) : MatcherResult {
        override fun plus(other: MatcherResult): MatcherResult {
            if (other !is MisMatch) return this.copy(context = other.context)
            val updatedFailure = Result.Companion.fromResults(listOf(this.failure, other.failure)) as Result.Failure
            return copy(failure = updatedFailure, context = other.context)
        }
    }

    data class Success(override val context: MatcherContext): MatcherResult {
        override fun plus(other: MatcherResult): MatcherResult {
            if (other !is Success) return other
            return copy(context = other.context)
        }
    }

    data class Exhausted(override val context: MatcherContext): MatcherResult {
        override fun plus(other: MatcherResult): MatcherResult {
            return when (other) {
                is MisMatch -> other
                else -> this.copy(context = other.context)
            }
        }
    }

    fun plusAny(other: MatcherResult): MatcherResult = other as? Success ?: plus(other)

    fun toReturnValue(): ReturnValue<MatcherContext> {
        return when (this) {
            is Success -> HasValue(this.context)
            is MisMatch -> HasFailure(this.failure)
            is Exhausted -> HasFailure(Result.Failure("Matcher has been exhausted"))
        }
    }

    fun checkOverArchingExhaustion(): MatcherResult {
        if (this !is Exhausted) return this
        val sharedExhaustionList = this.context.getSharedExhaustionChecks().list
        val currentExhaustionObject = this.context.getCurrentExhaustionChecks()
        return sharedExhaustionList.count { historicalRun ->
            historicalRun is JSONObjectValue && historicalRun.jsonObject == currentExhaustionObject.jsonObject
        }.let {
            if (it >= this.context.maxTimes) this else Success(context)
        }
    }

    companion object {
        fun from(errorMessage: String, breadCrumb: BreadCrumb, context: MatcherContext): MisMatch {
            return MisMatch(Result.Failure(message = errorMessage, breadCrumb = breadCrumb.value), context)
        }

        fun from(returnValue: ReturnValue<MatcherContext>, defaultContext: MatcherContext, isExhausted: Boolean = false): MatcherResult {
            return returnValue.realise(
                hasValue = { it, _ -> if (isExhausted) Exhausted(it) else Success(it) },
                orFailure = { f -> MisMatch(f.toFailure(), defaultContext) },
                orException = { e -> MisMatch(e.toHasFailure().toFailure(), defaultContext) },
            )
        }
    }
}
