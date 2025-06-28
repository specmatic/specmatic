package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.mismatchResult
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

data class PatternInStringPattern(override val pattern: Pattern = StringPattern(), override val typeAlias: String? = null): Pattern {
    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if(sampleData !is StringValue)
            return mismatchResult(pattern, sampleData, resolver.mismatchMessages)

        val value = try {
            pattern.parse(sampleData.string, resolver)
        } catch(e: Throwable) {
            return Result.Failure("Could not parse ${sampleData.displayableValue()} to ${pattern.typeName}")
        }

        return pattern.matches(value, resolver)
    }

    override fun generate(resolver: Resolver): Value {
        return StringValue(resolver.withCyclePrevention(pattern, pattern::generate).toStringLiteral())
    }

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> =
        resolver.withCyclePrevention(pattern) { cyclePreventedResolver ->
            pattern.newBasedOn(row, cyclePreventedResolver).map { it.ifValue { PatternInStringPattern(it) } }
        }

    override fun newBasedOn(resolver: Resolver): Sequence<Pattern> =
        resolver.withCyclePrevention(pattern) { cyclePreventedResolver ->
            pattern.newBasedOn(cyclePreventedResolver).map { PatternInStringPattern(it) }
        }

    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> {
        return sequenceOf(HasValue(NullPattern))
    }

    override fun parse(value: String, resolver: Resolver): Value = StringValue(pattern.parse(value, resolver).toStringLiteral())

    override fun patternSet(resolver: Resolver): List<PatternInStringPattern> =
            pattern.patternSet(resolver).map { PatternInStringPattern(it) }

    override fun encompasses(otherPattern: Pattern, thisResolver: Resolver, otherResolver: Resolver, typeStack: TypeStack): Result =
            when (otherPattern) {
                is ExactValuePattern -> otherPattern.fitsWithin(patternSet(thisResolver), otherResolver, thisResolver, typeStack)
                is PatternInStringPattern -> pattern.encompasses(otherPattern.pattern, otherResolver, thisResolver, typeStack)
                else -> Result.Failure("Expected type in string type, got ${otherPattern.typeName}")
            }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        return pattern.listOf(valueList, resolver)
    }

    override fun parseToType(valueString: String, resolver: Resolver): Pattern {
        return PatternInStringPattern(pattern.parse(valueString, resolver).exactMatchElseType())
    }

    override val typeName: String = "${pattern.typeName} in string"
}
