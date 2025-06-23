package io.specmatic.core

import io.specmatic.core.pattern.*
import io.specmatic.core.pattern.isOptional
import io.specmatic.core.pattern.withoutOptionality

data class GenerativeTestsEnabled(private val positiveOnly: Boolean) : GenerationStrategies {

    override fun generatedPatternsForGenerativeTests(
        resolver: Resolver,
        pattern: Pattern,
        key: String
    ): Sequence<ReturnValue<Pattern>> {
        // TODO generate value outside
        return resolver.withCyclePrevention(pattern, isOptional(key)) { cyclePreventedResolver ->
            pattern.newBasedOn(Row(), cyclePreventedResolver)
        } ?: emptySequence()
    }

    override fun generateHttpRequestBodies(
        resolver: Resolver,
        body: Pattern,
        row: Row,
        requestBodyAsIs: Pattern
    ): Sequence<ReturnValue<Pattern>> {
        // TODO generate value outside
        val requestsFromFlattenedRow: Sequence<ReturnValue<Pattern>> =
            resolver.withCyclePrevention(body) { cyclePreventedResolver ->
                body.newBasedOn(row.noteRequestBody(), cyclePreventedResolver)
            }

        var matchFound = false

        val iterator = requestsFromFlattenedRow.iterator()

        return sequence {

            while(iterator.hasNext()) {
                val next = iterator.next()

                next.withDefault(false) {
                    if(it.encompasses(requestBodyAsIs, resolver, resolver, emptySet()) is Result.Success)
                        matchFound = true
                }

                yield(next)
            }

            if(!matchFound)
                yield(HasValue(requestBodyAsIs))
        }
    }

    override fun generateHttpRequestBodies(
        resolver: Resolver,
        body: Pattern,
        row: Row
    ): Sequence<ReturnValue<Pattern>> {
        // TODO generate value outside
        val vanilla: Sequence<ReturnValue<Pattern>> = resolver.withCyclePrevention(body) { cyclePreventedResolver ->
            body.newBasedOn(Row(), cyclePreventedResolver)
        }

        val fromExamples: Sequence<ReturnValue<Pattern>> = resolver.withCyclePrevention(body) { cyclePreventedResolver ->
            body.newBasedOn(row, cyclePreventedResolver)
        }

        val remainingVanilla: Sequence<ReturnValue<Pattern>> = vanilla.filterNot { vanillaTypeR ->
            fromExamples.any { typeFromExamplesR ->
                vanillaTypeR.withDefault(false, typeFromExamplesR) { vanillaType, typeFromExamples ->
                    vanillaType.encompasses(
                        typeFromExamples,
                        resolver,
                        resolver
                    ).isSuccess()
                }
            }
        }

        return fromExamples.plus(remainingVanilla)
    }

    override fun resolveRow(row: Row): Row {
        return Row()
    }

    override fun generateKeySubLists(key: String, subList: List<String>): Sequence<List<String>> {
        return if(isOptional(key)) {
            sequenceOf(subList, subList + key)
        } else
            sequenceOf(subList + key)
    }

    override fun fillInTheMissingMapPatterns(
        newParamsList: Sequence<Map<String, Pattern>>,
        patterns: Map<String, Pattern>,
        additionalProperties: Pattern?,
        row: Row,
        resolver: Resolver,
        breadCrumb: String
    ): Sequence<ReturnValue<Map<String, Pattern>>> {
        val additionalPatterns = attempt(breadCrumb = breadCrumb) {
            val mapPattern = patterns.let {
                if (additionalProperties != null)
                    it.plus(randomString(5) to additionalProperties)
                else
                    it
            }

            forEachKeyCombinationIn(mapPattern, Row()) { entry: Map<String, Pattern> ->
                newMapBasedOn(entry, row, resolver)
            }.map { newPattern ->
                newPattern.update { map ->
                    map.mapKeys { withoutOptionality(it.key) }
                }
            }
        }

        return additionalPatterns.mapNotNull { pattern ->
            val overlapResult = noOverlapBetween(pattern.value, newParamsList, resolver) ?: return@mapNotNull null
            pattern.update { overlapResult }
        }
    }
}