package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.pattern.config.NegativePatternConfiguration

class AllNegativePatterns : NegativePatternsTemplate() {

    override fun getNegativePatterns(
        patternMap: Map<String, Pattern>,
        resolver: Resolver,
        row: Row,
        config: NegativePatternConfiguration
    ): Map<String, Sequence<ReturnValue<Pattern>>> {
        return patternMap.mapValues { (key, pattern) ->
            resolver.withCyclePrevention(pattern, isOptional(key)) { cyclePreventedResolver ->
                pattern.negativeBasedOn(
                    row.stepDownOneLevelInJSONHierarchy(withoutOptionality(key)),
                    cyclePreventedResolver,
                    config
                )
            } ?: emptySequence()
        }
    }
}