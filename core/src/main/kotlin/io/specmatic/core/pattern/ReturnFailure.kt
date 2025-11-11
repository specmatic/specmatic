package io.specmatic.core.pattern

import io.specmatic.core.Result

interface ReturnFailure {
    fun <T> cast(): ReturnValue<T>
    fun toFailure(): Result.Failure
    fun breadCrumb(breadCrumb: String? = null, message: String? = null): ReturnFailure
}
