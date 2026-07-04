package io.specmatic.core.pattern

interface XMLGenerationDecisions {
    fun includeOptionalXMLNode(): Boolean

    fun numberOfMultipleXMLNodes(): Int = 1

    fun numberOfXMLNodesFor(minOccurs: Int, maxOccurs: Int?): Int {
        val maximumOccurrences = maxOccurs ?: Int.MAX_VALUE
        val requiredOccurrences = minOccurs.coerceAtMost(maximumOccurrences)

        if (requiredOccurrences > 0) return requiredOccurrences
        if (maximumOccurrences == 0) return 0

        return if (includeOptionalXMLNode()) 1 else 0
    }

    fun chooseXMLChoiceBranch(choiceCount: Int): Int = kotlin.random.Random.nextInt(choiceCount)
}

object RandomXMLGenerationDecisions : XMLGenerationDecisions {
    override fun includeOptionalXMLNode(): Boolean = kotlin.random.Random.nextBoolean()
}
