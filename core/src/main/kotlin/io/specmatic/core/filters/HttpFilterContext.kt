package io.specmatic.core.filters

import io.specmatic.core.Scenario

data class HttpFilterContext(private val scenario: Scenario) : FilterContext {
    override fun includes(key: String, values: List<String>): Boolean {
        val filterKey = HTTPFilterKeys.fromKey(key)
        return values.any { eachValue ->
            filterKey.includes(scenario, key, eachValue)
        }
    }

    override fun compare(filterKey: String, operator: String, filterValue: String): Boolean {
        if (filterKey.uppercase() == "STATUS") {
            return evaluateCondition(scenario.status, operator, filterValue.toIntOrNull() ?: 0)
        } else {
            throw IllegalArgumentException("Unknown filter key: $filterKey")
        }
    }
}
