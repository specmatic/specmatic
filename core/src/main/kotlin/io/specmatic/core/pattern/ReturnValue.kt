package io.specmatic.core.pattern

import io.specmatic.core.Result

sealed interface ReturnValue<T> {
    val value: T

    fun <U> withDefault(default: U, fn: (T) -> U): U

    fun <U, V> withDefault(default: U, other: ReturnValue<V>, fn: (T, V) -> U): U {
        if(this !is HasValue && other !is HasValue)
            return default

        return fn(this.value, other.value)
    }

    fun <U> ifValue(fn: (T) -> U): ReturnValue<U>
    fun <U> ifHasValue(fn: (HasValue<T>) -> ReturnValue<U>): ReturnValue<U>
    fun update(fn: (T) -> T): ReturnValue<T>
    fun <U> assimilate(acc: ReturnValue<U>, fn: (T, U) -> T): ReturnValue<T>
    fun <U, V> combine(acc: ReturnValue<U>, fn: (T, U) -> V): ReturnValue<V>
    fun <U, V> exceptionElseCombine(acc: ReturnValue<U>, fn: (T, U) -> V): ReturnValue<V>
    fun <U> realise(hasValue: (T, String?) -> U, orFailure: (HasFailure<T>) -> U, orException: (HasException<T>) -> U): U
    fun addDetails(message: String, breadCrumb: String): ReturnValue<T>
}

fun <ReturnType> returnValue(errorMessage: String = "", breadCrumb: String = "", f: ()->Sequence<ReturnValue<ReturnType>>): Sequence<ReturnValue<ReturnType>> {
    return try {
        f().map { it.addDetails(errorMessage, breadCrumb) }
    }
    catch(contractException: ContractException) {
        val failure =
            Result.Failure(message = errorMessage, breadCrumb = breadCrumb, cause = contractException.failure())
        sequenceOf(HasFailure(failure))
    }
    catch(throwable: Throwable) {
        sequenceOf(HasException(throwable, errorMessage, breadCrumb))
    }
}

fun <K, ValueType> Map<K, ReturnValue<out ValueType>>.mapFoldException(): ReturnValue<Map<K, ValueType>> {
    val initial: ReturnValue<Map<K, ValueType>> = HasValue(emptyMap())

    return this.entries.fold(initial) { accR, (key, valueR) ->
        accR.exceptionElseCombine(valueR) { acc, value ->
            acc.plus(key to value)
        }
    }
}

fun <K, ValueType> Map<K, ReturnValue<out ValueType>>.mapFold(): ReturnValue<Map<K, ValueType>> {
    val initial: ReturnValue<Map<K, ValueType>> = HasValue(emptyMap())

    return this.entries.fold(initial) { accR, (key, valueR) ->
        accR.combine(valueR) { acc, value ->
            acc.plus(key to value)
        }
    }
}

fun <K, ValueType> Map<ReturnValue<K>, ValueType>.mapFoldKeys(): ReturnValue<Map<K, ValueType>> {
    val initial: ReturnValue<Map<K, ValueType>> = HasValue(emptyMap())
    return entries.fold(initial) { accR, (key, value) ->
        accR.combine(key) { acc, actualKey ->
            acc + (actualKey to value)
        }
    }
}

fun <ValueType> List<ReturnValue<ValueType>>.listFoldException(): ReturnValue<List<ValueType>> {
    val initial: ReturnValue<List<ValueType>> = HasValue(emptyList())

    return this.fold(initial) { accR: ReturnValue<List<ValueType>>, valueR: ReturnValue<ValueType> ->
        accR.exceptionElseCombine(valueR) { acc, value ->
            acc.plus(value)
        }
    }
}

fun <ValueType> List<ReturnValue<out ValueType>>.listFold(): ReturnValue<List<ValueType>> {
    val initial: ReturnValue<List<ValueType>> = HasValue(emptyList())

    return this.fold(initial) { accR: ReturnValue<List<ValueType>>, valueR: ReturnValue<out ValueType> ->
        accR.combine(valueR) { acc, value ->
            acc.plus(value)
        }
    }
}

fun <T> Sequence<List<ReturnValue<out T>>>.sequenceListFold(): Sequence<ReturnValue<List<T>>> {
    val data: Sequence<List<ReturnValue<out T>>> = this

    return data.map { listOfReturnValues ->
        val error: ReturnValue<out T>? = listOfReturnValues.firstOrNull { it !is HasValue }

        if (error != null)
            listOf(error)

        val valueDetails: List<ValueDetails> = listOfReturnValues.map { (it as HasValue).valueDetails }.flatten()
        HasValue(listOfReturnValues.map { it.value }, valueDetails)
    }
}

fun <T> Sequence<ReturnValue<T>>.foldIntoReturnValueOfSequence(): ReturnValue<Sequence<T>> {
    val init: ReturnValue<Sequence<T>> = HasValue(emptySequence())

    return this.fold(init) { acc: ReturnValue<Sequence<T>>, item: ReturnValue<T> ->
        acc.assimilate(item) { accValue, itemValue ->
            accValue.plus(itemValue)
        }
    }
}

fun <T> ReturnValue<T>.breadCrumb(breadCrumb: String?): ReturnValue<T> {
    if(breadCrumb == null)
        return this

    return this.addDetails("", breadCrumb)
}

fun returnValueSequence(fn: () -> Sequence<ReturnValue<Pattern>>): Sequence<ReturnValue<Pattern>> {
    return try {
        fn()
    } catch(t: Throwable) {
        return sequenceOf(HasException(t, ""))
    }
}

fun <T> Sequence<ReturnValue<T>>.filterValueIsNot(fn: (T) -> Boolean): Sequence<ReturnValue<T>> {
    return this.filterNot { returnValue ->
        returnValue.withDefault(false) { value ->
            fn(value)
        }
    }
}

fun Sequence<ReturnValue<List<List<Pattern>>>>.foldToSequenceOfReturnValueList(): Sequence<ReturnValue<List<Pattern>>> {
    val seq = this

    return sequence {
        seq.forEach { returnValue: ReturnValue<List<List<Pattern>>> ->
            if (returnValue is HasValue) {
                returnValue.value.forEach { list ->
                    yield(HasValue(list, returnValue.valueDetails))
                }
            } else if (returnValue is ReturnFailure) {
                yield(returnValue.cast())
            }
        }
    }

}

fun <T> exception(fn: () -> T): Throwable? {
    return try {
        fn()
        null
    } catch(t: Throwable) {
        t
    }
}

fun <T> Throwable.asReturnValue(
    message: String
): ReturnValue<T> = when (this) {
    is ContractException -> HasFailure(this.failure(), message = message)
    else -> HasException(this, message = message, breadCrumb = "")
}

fun <T> ReturnValue<T>.unwrapOrContractException(): T {
    return this.realise(
        hasValue = { value, _ -> value },
        orFailure = { hasF -> throw ContractException(hasF.toFailure().toFailureReport()) },
        orException = { hasE -> throw ContractException(hasE.toHasFailure().toFailure().toFailureReport()) }
    )
}

inline fun <T> ReturnValue<T>.unwrapOrReturn(inlined: (ReturnFailure) -> Nothing): T {
    return when (this) {
        is HasValue -> value
        is HasFailure, is HasException -> inlined(this as ReturnFailure)
    }
}
