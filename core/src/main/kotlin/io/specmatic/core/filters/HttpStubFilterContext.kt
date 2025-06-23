package io.specmatic.core.filters

import io.specmatic.core.filters.HTTPFilterKeys.*
import io.specmatic.mock.ScenarioStub
import java.util.regex.Pattern
import javax.activation.MimeType

class HttpStubFilterContext(private val scenario: ScenarioStub) : FilterContext {
    override fun includes(key: String, values: List<String>): Boolean {
        val filterKey = HTTPFilterKeys.fromKey(key)
        return values.any { eachValue ->
            filterKey.includes(scenario, key, eachValue)
        }
    }

    override fun compare(filterKey: String, operator: String, filterValue: String): Boolean {
        val key = HTTPFilterKeys.fromKey(filterKey)
        return when (key) {
            STATUS -> evaluateCondition(
                scenario.response.status,
                operator,
                filterValue.toIntOrNull() ?: 0
            )

            else -> throw IllegalArgumentException("Unknown filter key: $filterKey")
        }
    }
}
