package io.specmatic.proxy

import io.specmatic.core.pattern.withPatternDelimiters
import io.specmatic.core.value.ScalarValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.test.SET_COOKIE_SEPARATOR

interface ValueTransformer {
    fun transform(value: Value?): Value?

    data class GeneralizeToType(private val valueToType: (Value) -> String = defaultValueToTypeConverter): ValueTransformer {
        override fun transform(value: Value?): Value? {
            if (value == null) return null
            return StringValue(valueToType(value))
        }

        companion object {
            private val defaultValueToTypeConverter: (Value) -> String = {
                when (it) {
                    is ScalarValue -> withPatternDelimiters(it.type().typeName)
                    else -> "(anyvalue)"
                }
            }
        }
    }

    data object RemoveValue : ValueTransformer {
        override fun transform(value: Value?): Value? {
            return null
        }
    }

    data object CookieExpiryTransformer: ValueTransformer {
        override fun transform(value: Value?): Value? {
            if (value !is StringValue) return value
            val transformed = value.nativeValue.split(SET_COOKIE_SEPARATOR).joinToString(SET_COOKIE_SEPARATOR, transform = ::stripExpiryAttributes)
            return StringValue(transformed)
        }

        private fun stripExpiryAttributes(cookie: String): String {
            return cookie.split(";").map { it.trim() }.filterNot(::isExpiryAttribute).joinToString("; ")
        }

        private fun isExpiryAttribute(attribute: String): Boolean {
            val lower = attribute.lowercase()
            return lower.startsWith("expires=") || lower.startsWith("max-age=")
        }
    }
}