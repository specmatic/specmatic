package io.specmatic.core.wsdl.parser.operation

import io.specmatic.core.pattern.XMLPattern

data class SOAPTypes(val types: Map<String, XMLPattern>) {
    fun statements(): List<String> {
        val typeStrings = types.entries.map { (typeName, type) ->
            type.toGherkinStatement(typeName)
        }

        return firstLineShouldBeGiven(typeStrings).map { it.trimEnd() }
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
