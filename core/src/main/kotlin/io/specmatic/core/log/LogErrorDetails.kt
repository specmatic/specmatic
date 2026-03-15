package io.specmatic.core.log

import io.specmatic.core.pattern.ContractException
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import java.io.IOException

data class LogErrorDetails(
    val errorCode: String,
    val errorCategory: String,
    val message: String,
    val cause: String,
    val stackTrace: String? = null,
) {
    fun toValue(): JSONObjectValue {
        val data = buildMap<String, Value> {
            put("errorCode", StringValue(errorCode))
            put("errorCategory", StringValue(errorCategory))
            put("message", StringValue(message))
            put("cause", StringValue(cause))
            stackTrace?.let { put("stackTrace", StringValue(it)) }
        }
        return JSONObjectValue(data)
    }

    companion object {
        fun from(throwable: Throwable, includeStackTrace: Boolean = false): LogErrorDetails {
            val message = throwable.localizedMessage ?: throwable.message ?: throwable.javaClass.name
            val cause = throwable.cause?.localizedMessage ?: throwable.cause?.message ?: message
            return LogErrorDetails(
                errorCode = throwable.javaClass.simpleName.ifBlank { "UNKNOWN_ERROR" },
                errorCategory = categoryOf(throwable),
                message = message,
                cause = cause,
                stackTrace = if (includeStackTrace) throwable.stackTraceToString() else null,
            )
        }

        private fun categoryOf(throwable: Throwable): String {
            return when (throwable) {
                is ContractException -> "CONTRACT"
                is IOException -> "IO"
                is IllegalArgumentException -> "VALIDATION"
                else -> "UNEXPECTED"
            }
        }
    }
}
