package io.specmatic.core.wsdl.parser.operation

import io.specmatic.core.pattern.AnyPattern
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.XMLPattern

data class SOAPTypes(val types: Map<String, Pattern>) {
    fun statements(): List<String> {
        val typeStrings = types.entries.map { (typeName, type) ->
            type.toGherkinStatement(typeName)
        }

        return firstLineShouldBeGiven(typeStrings).map { it.trimEnd() }
    }

    fun expandedVariants(): List<SOAPTypes> {
        val choices = types.filterValues { pattern ->
            pattern is AnyPattern && pattern.pattern.all { it is XMLPattern }
        }

        if (choices.isEmpty()) {
            return listOf(this)
        }

        val expandedTypeMaps = types.entries.fold(listOf(emptyMap<String, Pattern>())) { accumulated, (typeName, pattern) ->
            when {
                pattern is AnyPattern && pattern.pattern.all { it is XMLPattern } -> {
                    accumulated.flatMap { current ->
                        pattern.pattern.map { choicePattern ->
                            current.plus(typeName to choicePattern)
                        }
                    }
                }

                else -> accumulated.map { it.plus(typeName to pattern) }
            }
        }

        return expandedTypeMaps.map(::SOAPTypes)
    }
}

private fun Pattern.toGherkinStatement(typeName: String): String {
    return when (this) {
        is XMLPattern -> this.toGherkinStatement(typeName)
        else -> "And type $typeName ${this.pattern}"
    }
}

private fun firstLineShouldBeGiven(typeStrings: List<String>): List<String> {
    return when (typeStrings.size) {
        0 -> typeStrings
        else -> {
            val firstLine = typeStrings.first()
            val adjustedFirstLine = "Given " + firstLine.removePrefix("And ")

            listOf(adjustedFirstLine).plus(typeStrings.drop(1))
        }
    }
}
