package io.specmatic.conversions

import io.specmatic.core.NoBodyPattern
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.AnyPattern
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.extractCombinedExtensions
import io.specmatic.core.value.Value

data class OptionalBodyPattern(override val pattern: AnyPattern, private val bodyPattern: Pattern) : Pattern by pattern {
    companion object {
        fun fromPattern(bodyPattern: Pattern): OptionalBodyPattern {
            val anyPatternPatterns = listOf(bodyPattern, NoBodyPattern)
            return OptionalBodyPattern(
                AnyPattern(anyPatternPatterns, extensions = anyPatternPatterns.extractCombinedExtensions()),
                bodyPattern
            )
        }
    }


    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        val bodyPatternMatchResult = bodyPattern.matches(sampleData, resolver)

        if(bodyPatternMatchResult is Result.Success)
            return bodyPatternMatchResult

        val nobodyPatternMatchResult = NoBodyPattern.matches(sampleData, resolver)

        if(nobodyPatternMatchResult is Result.Success)
            return nobodyPatternMatchResult

        return bodyPatternMatchResult
    }
}
