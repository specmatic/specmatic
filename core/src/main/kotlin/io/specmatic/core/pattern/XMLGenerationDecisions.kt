package io.specmatic.core.pattern

interface XMLGenerationDecisions {
    fun includeOptionalXMLNode(): Boolean
}

object RandomXMLGenerationDecisions : XMLGenerationDecisions {
    override fun includeOptionalXMLNode(): Boolean = kotlin.random.Random.nextBoolean()
}
