package io.specmatic.core.pattern

import io.specmatic.core.Result
import io.specmatic.core.utilities.exceptionCauseMessage

data class HasException<T>(val t: Throwable, val message: String = "", val breadCrumb: String? = null) : ReturnValue<T>, ReturnFailure {
    fun toHasFailure(): HasFailure<T> {
        val failure: Result.Failure = toFailure()
        return HasFailure(failure, message)
    }

    override fun toFailure(): Result.Failure {
        val failure: Result.Failure = Result.Failure(
            message = exceptionCauseMessage(t),
            breadCrumb = breadCrumb ?: ""
        )
        return failure
    }

    override fun breadCrumb(breadCrumb: String?, message: String?): ReturnFailure {
        if (breadCrumb.isNullOrBlank()) return this
        return this.addDetails(message.orEmpty(), breadCrumb)
    }

    override fun <U> withDefault(default: U, fn: (T) -> U): U {
        return default
    }

    override fun <U> ifValue(fn: (T) -> U): ReturnValue<U> {
        return cast()
    }

    override fun update(fn: (T) -> T): ReturnValue<T> {
        return this
    }

    override fun <U> assimilate(acc: ReturnValue<U>, fn: (T, U) -> T): ReturnValue<T> {
        return cast()
    }

    override fun <U, V> combine(acc: ReturnValue<U>, fn: (T, U) -> V): ReturnValue<V> {
        val otherReturnValue = when(acc) {
            is HasException -> acc.toHasFailure()
            is HasFailure -> acc
            else -> return cast()
        }

        return this.toHasFailure().combine(otherReturnValue, fn)
    }

    override fun <V> cast(): ReturnValue<V> {
        return HasException(t, message, breadCrumb)
    }

    override val value: T
        get() = throw t

    override fun <U, V> exceptionElseCombine(acc: ReturnValue<U>, fn: (T, U) -> V): ReturnValue<V> {
        return cast()
    }

    override fun <U> ifHasValue(fn: (HasValue<T>) -> ReturnValue<U>): ReturnValue<U> {
        return cast()
    }

    override fun addDetails(message: String, breadCrumb: String): HasException<T> {
        val newE = toException(message, breadCrumb, toException())

        return HasException(newE)
    }

    private fun toException(): Throwable {
        return toException(message, breadCrumb ?: "", t)
    }

    private fun toException(
        errorMessage: String,
        breadCrumb: String,
        t: Throwable
    ): Throwable {
        val newE = when (t) {
            is ContractException -> ContractException(errorMessage, breadCrumb, t, t.scenario, t.isCycle)
            else -> ContractException(errorMessage, breadCrumb, t)
        }

        return newE
    }

    override fun <U> realise(hasValue: (T, String?) -> U, orFailure: (HasFailure<T>) -> U, orException: (HasException<T>) -> U): U {
        return orException(this)
    }
}
