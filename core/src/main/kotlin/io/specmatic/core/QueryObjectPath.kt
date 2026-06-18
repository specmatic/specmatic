package io.specmatic.core

import io.specmatic.core.pattern.ContractException

sealed class QueryObjectPathToken {
    abstract fun appendTo(key: String, syntax: ObjectQuerySyntax): String
    abstract fun displaySegment(): String

    data class Property(val name: String) : QueryObjectPathToken() {
        fun rootKey(parameterName: String, syntax: ObjectQuerySyntax): String {
            return syntax.root.keyForRootProperty(parameterName, name)
        }

        override fun appendTo(key: String, syntax: ObjectQuerySyntax): String {
            return syntax.propertyStyle.appendProperty(key, name)
        }

        override fun displaySegment(): String = name
    }

    data class Index(val index: Int) : QueryObjectPathToken() {
        override fun appendTo(key: String, syntax: ObjectQuerySyntax): String {
            return syntax.arrayIndexStyle.appendIndex(key, index)
        }

        override fun displaySegment(): String = "[$index]"
    }
}

data class QueryObjectPath(val tokens: List<QueryObjectPathToken>) {
    fun serialize(parameterName: String, syntax: ObjectQuerySyntax): String {
        val first = tokens.firstOrNull() as? QueryObjectPathToken.Property
            ?: throw ContractException("Query object path must start with a property")

        return tokens.drop(1).fold(first.rootKey(parameterName, syntax)) { key, token ->
            token.appendTo(key, syntax)
        }
    }
}
