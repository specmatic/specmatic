package io.specmatic.core.filters

import io.specmatic.core.HttpRequest
import io.specmatic.core.filters.HTTPFilterKeys.*

class HttpRequestFilterContext(private val httpRequest: HttpRequest) : FilterContext {
    override fun includes(key: String, values: List<String>): Boolean {
        val filterKey = HTTPFilterKeys.fromKey(key)
        return values.any { eachValue ->
            when (filterKey) {
                METHOD -> {
                    httpRequest.method.equals(eachValue, ignoreCase = true)
                }
                PATH -> {
                    httpRequest.path == eachValue
                }
                PARAMETERS_HEADER -> {
                    httpRequest.hasHeader(eachValue)
                }
                else -> false
            }
        }
    }

    override fun compare(filterKey: String, operator: String, filterValue: String): Boolean {
        throw UnsupportedOperationException("Compare is not supported for HttpRequestFilterContext")
    }
}
