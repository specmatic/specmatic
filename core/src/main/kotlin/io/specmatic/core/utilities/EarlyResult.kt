package io.specmatic.core.utilities

import io.specmatic.core.Result

sealed interface EarlyResult<T> {
    data class FirstSuccess<T>(val value: T): EarlyResult<T>
    data class Failures<T>(val failures: List<Result.Failure>): EarlyResult<T>
}
