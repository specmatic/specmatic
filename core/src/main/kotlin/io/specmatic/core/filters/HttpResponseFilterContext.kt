package io.specmatic.core.filters

import io.specmatic.core.HttpResponse
import io.specmatic.core.filters.HTTPFilterKeys.*

class HttpResponseFilterContext(private val httpResponse: HttpResponse) : FilterContext {
    override fun includes(key: String, values: List<String>): Boolean {
        val filterKey = HTTPFilterKeys.fromKey(key)
        return values.any { eachValue ->
            when (filterKey) {
                STATUS -> {
                    httpResponse.status == eachValue.toIntOrNull()
                }

                RESPONSE_CONTENT_TYPE -> {
                    httpResponse.getHeader("CONTENT_TYPE") == eachValue
                }

                PARAMETERS_HEADER_WITH_SPECIFIC_VALUE -> {
                    val headerKey = key.substringAfter(PARAMETERS_HEADER_WITH_SPECIFIC_VALUE.key)
                    httpResponse.getHeader(headerKey)?.equals(eachValue, ignoreCase = true) ?: false
                }

                else -> false
            }
        }
    }

    override fun compare(filterKey: String, operator: String, filterValue: String): Boolean {
        val key = HTTPFilterKeys.fromKey(filterKey)
        return when (key) {
            STATUS -> evaluateCondition(httpResponse.status, operator, filterValue.toIntOrNull() ?: 0)
            else -> throw IllegalArgumentException("Unknown filter key: $filterKey")
        }
    }
}
