package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.pattern.config.NegativePatternConfiguration

abstract class NegativePatternsTemplate {
    fun negativeBasedOn(
        patternMap: Map<String, Pattern>,
        row: Row,
        resolver: Resolver,
        config: NegativePatternConfiguration = NegativePatternConfiguration()
    ): Sequence<ReturnValue<Map<String, Pattern>>> {
        val eachKeyMappedToPatternMap: Map<String, Map<String, Pattern>> = patternMap.mapValues { patternMap }
        val negativePatternsForEachKey: Map<String, Sequence<ReturnValue<Pattern>>> =
            getNegativePatterns(patternMap, resolver, row, config)

        val modifiedPatternMap: Map<String, Map<String, Sequence<ReturnValue<Pattern>>>?> =
            eachKeyMappedToPatternMap.mapValues { (keyToNegate, patterns) ->
                val negativePatterns: Sequence<ReturnValue<Pattern>> = negativePatternsForEachKey.getValue(keyToNegate)

                if(!negativePatterns.any())
                    return@mapValues null

                patterns.mapValues { (key, pattern) ->
                    when (key) {
                        keyToNegate -> {
                            val result: Sequence<ReturnValue<Pattern>> = negativePatterns
                            result
                        }
                        else -> newPatternsBasedOn(row, key, pattern, resolver)
                    }.map {
                        it.breadCrumb(withoutOptionality(key))
                    }
                }
            }

        if (modifiedPatternMap.values.isEmpty())
            return sequenceOf(HasValue(emptyMap()))

        return modifiedPatternMap.values.asSequence().filterNotNull().flatMap {
            patternList(it, resolver.maxTestRequestCombinations)
        }

    }

    abstract fun getNegativePatterns(
        patternMap: Map<String, Pattern>,
        resolver: Resolver,
        row: Row,
        config: NegativePatternConfiguration = NegativePatternConfiguration()
    ): Map<String, Sequence<ReturnValue<Pattern>>>

}
