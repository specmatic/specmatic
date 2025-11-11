package io.specmatic.core.jsonoperator

import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnValue

sealed interface Optional<out Type> {
    data class Some<Type>(val value: Type) : Optional<Type>

    data object None : Optional<Nothing>

    fun <R> map(transform: (Type) -> R): Optional<R> = when (this) {
        is Some -> Some(transform(value))
        is None -> None
    }

    fun <R> flatMap(transform: (Type) -> Optional<R>): Optional<R> = when (this) {
        is Some -> transform(value)
        is None -> None
    }

    fun <R> flatMapReturnValue(transform: (Type) -> ReturnValue<Optional<R>>): ReturnValue<Optional<R>> = when (this) {
        is Some -> transform(value)
        is None -> HasValue(None)
    }

    fun getOrElse(default: () -> @UnsafeVariance Type): Type = when (this) {
        is Some -> value
        is None -> default()
    }

    fun getOrNull(): Type? = when (this) {
        is Some -> value
        is None -> null
    }

    fun getOrThrow(): Type = getOrNull() ?: throw IllegalStateException("Expected Optional to be present")
}
