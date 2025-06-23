package io.specmatic.core

import io.specmatic.core.pattern.*

object NonGenerativeTests : GenerationStrategies {

    override fun generatedPatternsForGenerativeTests(
        resolver: Resolver,
        pattern: Pattern,
        key: String
    ): Sequence<ReturnValue<Pattern>> {
        return sequenceOf()
    }

    override fun generateHttpRequestBodies(
        resolver: Resolver,
        body: Pattern,
        row: Row,
        requestBodyAsIs: Pattern
    ): Sequence<ReturnValue<Pattern>> {
        return sequenceOf(HasValue(requestBodyAsIs))
    }

    override fun generateHttpRequestBodies(
        resolver: Resolver,
        body: Pattern,
        row: Row
    ): Sequence<ReturnValue<Pattern>> {
        return resolver.withCyclePrevention(body) { cyclePreventedResolver ->
            body.newBasedOn(row, cyclePreventedResolver)
        }
    }

    override fun resolveRow(row: Row): Row {
        return row
    }

    override fun generateKeySubLists(key: String, subList: List<String>): Sequence<List<String>> {
        return sequenceOf(subList + key)
    }

    override fun fillInTheMissingMapPatterns(
        newParamsList: Sequence<Map<String, Pattern>>,
        patterns: Map<String, Pattern>,
        additionalProperties: Pattern?,
        row: Row,
        resolver: Resolver,
        breadCrumb: String
    ): Sequence<ReturnValue<Map<String, Pattern>>> = emptySequence()
}