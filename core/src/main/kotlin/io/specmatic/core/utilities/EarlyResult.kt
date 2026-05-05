package io.specmatic.core.utilities

sealed interface EarlyResult<out Value, out Failure> {
    data class FirstSuccess<out Value>(val value: Value): EarlyResult<Value, Nothing>
    data class Failures<out Failure>(val failures: List<Failure>): EarlyResult<Nothing, Failure>

    fun <T> fold(onSuccess: (Value) -> T, onFailure: (List<Failure>) -> T): T {
        return when (this) {
            is FirstSuccess -> onSuccess(value)
            is Failures -> onFailure(failures)
        }
    }
}

fun <Value, Failure> EarlyResult<Value, Failure>.getOrElse(orElse: (List<Failure>) -> Value): Value {
    return this.fold(onSuccess = { value -> value }, onFailure = { failure -> orElse(failure) })
}

inline fun <Item, Value, Failure> Iterable<Item>.firstSuccessOrFailures(evaluate: (Item) -> Value, isSuccess: (Value) -> Boolean, toFailure: (Value) -> Failure): EarlyResult<Value, Failure> {
    val failures = mutableListOf<Failure>()

    for (item in this) {
        val value = evaluate(item)
        if (isSuccess(value)) return EarlyResult.FirstSuccess(value)
        toFailure(value).let(failures::add)
    }

    return EarlyResult.Failures(failures)
}
