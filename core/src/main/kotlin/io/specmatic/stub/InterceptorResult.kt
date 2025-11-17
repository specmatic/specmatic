package io.specmatic.stub

data class InterceptorResult<T>(
    val value: T?,
    val errors: List<InterceptorError> = emptyList()
) {
    companion object {
        fun <T> success(value: T): InterceptorResult<T> {
            return InterceptorResult(value, emptyList())
        }

        fun <T> failure(error: InterceptorError): InterceptorResult<T> {
            return InterceptorResult(null, listOf(error))
        }

        fun <T> passthrough(): InterceptorResult<T> {
            return InterceptorResult(null, emptyList())
        }
    }
}
