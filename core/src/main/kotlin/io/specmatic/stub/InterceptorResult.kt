package io.specmatic.stub

import com.fasterxml.jackson.databind.ObjectMapper
import io.specmatic.core.pattern.parsedJSON
import io.specmatic.core.value.Value

data class InterceptorResult<T>(
    val name: String,
    val originalValue: T?,
    val processedValue: T?,
    val errors: List<InterceptorError> = emptyList()
) {

    fun toLogString(): String {
        return ObjectMapper().writeValueAsString(this)
    }

    fun toValue(): Value {
        return parsedJSON(toLogString())
    }

    companion object {
        fun <T> success(name: String, originalValue: T, processedValue: T): InterceptorResult<T> {
            return InterceptorResult(name, originalValue,processedValue, emptyList())
        }

        fun <T> failure(name: String, originalValue: T, error: InterceptorError): InterceptorResult<T> {
            return InterceptorResult(name, originalValue, null,listOf(error))
        }

        fun <T> passthrough(name: String): InterceptorResult<T> {
            return InterceptorResult(name,null, null, emptyList())
        }
    }
}
