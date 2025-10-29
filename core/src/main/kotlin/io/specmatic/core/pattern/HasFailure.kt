package io.specmatic.core.pattern

import io.specmatic.core.Result

data class HasFailure<T>(val failure: Result.Failure, val message: String = "") : ReturnValue<T>, ReturnFailure {
    constructor(message: String) : this(Result.Failure(message))

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
        return when (acc) {
            is HasFailure<*> -> combine(acc).cast()
            is HasException<*> -> combine(acc.toHasFailure()).cast()
            else -> cast()
        }
    }

    override fun <U> cast(): ReturnValue<U> {
        return HasFailure(failure, message)
    }

    override val value: T
        get() = throw ContractException(failure.toFailureReport())

    override fun <U, V> exceptionElseCombine(acc: ReturnValue<U>, fn: (T, U) -> V): ReturnValue<V> {
        return when (acc) {
            is HasFailure<*> -> combine(acc).cast()
            is HasException<*> -> acc.cast()
            else -> cast()
        }
    }

    override fun <U> ifHasValue(fn: (HasValue<T>) -> ReturnValue<U>): ReturnValue<U> {
        return cast()
    }

    override fun toFailure(): Result.Failure {
        return Result.Failure(message, failure).updateScenario(failure.scenario) as Result.Failure
    }

    override fun addDetails(message: String, breadCrumb: String): HasFailure<T> {
        return HasFailure(Result.Failure(message, this.toFailure(), breadCrumb))
    }

    override fun <U> realise(hasValue: (T, String?) -> U, orFailure: (HasFailure<T>) -> U, orException: (HasException<T>) -> U): U {
        return orFailure(this)
    }

    private fun combine(other: HasFailure<*>): HasFailure<T> {
        return HasFailure(
            failure = Result.Failure.fromFailures(listOf(this.failure, other.failure)),
            message = listOf(this.message, other.message).filter { it.isNotBlank() }.joinToString("\n\n")
        )
    }
}
