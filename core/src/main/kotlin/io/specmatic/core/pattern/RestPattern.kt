package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.utilities.withNullPattern
import io.specmatic.core.value.Value

data class RestPattern(override val pattern: Pattern, override val typeAlias: String? = null) : Pattern {
    private val listPattern = ListPattern(pattern)

    override fun collectReferences(references: ReferencedPatterns) {
        references.add(pattern)
    }

    override fun matches(sampleData: Value?, resolver: Resolver): Result =
            listPattern.matches(sampleData, resolver)

    override fun generate(resolver: Resolver): Value = resolver.withCyclePrevention(listPattern, listPattern::generate)

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> {
        return resolver.withCyclePrevention(pattern) { cyclePreventedResolver ->
            pattern.newBasedOn(row, cyclePreventedResolver)
        }
    }

    override fun newBasedOn(resolver: Resolver): Sequence<Pattern> {
        return resolver.withCyclePrevention(pattern) { cyclePreventedResolver ->
            pattern.newBasedOn(cyclePreventedResolver).map { RestPattern(it) }
        }
    }
    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> {
        return sequenceOf(HasValue(this))
    }

    override fun parse(value: String, resolver: Resolver): Value = listPattern.parse(value, resolver)

    override fun patternSet(resolver: Resolver): List<Pattern> =
            pattern.patternSet(resolver).map { RestPattern(it) }

    override fun encompasses(otherPattern: Pattern, thisResolver: Resolver, otherResolver: Resolver, typeStack: TypeStack): Result {
        val thisResolverWithNullType = withNullPattern(thisResolver)
        val otherResolverWithNullType = withNullPattern(otherResolver)

        return when (otherPattern) {
            is ExactValuePattern -> otherPattern.fitsWithin(listOf(this), otherResolverWithNullType, thisResolverWithNullType, typeStack)
            is RestPattern -> pattern.encompasses(otherPattern.pattern, otherResolverWithNullType, thisResolverWithNullType, typeStack)
            else -> Result.Failure("Expected rest in string type, got ${otherPattern.typeName}")
        }
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        return pattern.listOf(valueList, resolver)
    }

    override val typeName: String = "the rest are ${pattern.typeName}"
}

private const val REST_SUFFIX = "..."

fun withoutRestToken(pattern: String): String =
        "(" + withoutPatternDelimiters(pattern.trim()).removeSuffix((REST_SUFFIX)) + ")"

fun isRestPattern(it: String): Boolean = it.trim().removePrefix("(").removeSuffix(")").endsWith(REST_SUFFIX)
