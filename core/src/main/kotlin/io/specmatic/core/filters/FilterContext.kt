package io.specmatic.core.filters

interface FilterContext {
    fun includes(key: String, values: List<String>): Boolean
    fun compare(filterKey: String, operator: String, filterValue: String): Boolean

    fun evaluateCondition(lhs: Int, operator: String, rhs: Int): Boolean {
        return when (operator) {
            ">" -> lhs > rhs
            "<" -> lhs < rhs
            ">=" -> lhs >= rhs
            "<=" -> lhs <= rhs
            else -> throw IllegalArgumentException("Unsupported filter operator: $operator")
        }
    }
}