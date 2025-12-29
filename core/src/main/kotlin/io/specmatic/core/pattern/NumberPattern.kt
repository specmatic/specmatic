package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.constraintMismatchResult
import io.specmatic.core.patternMismatchResult
import io.specmatic.core.dataTypeMismatchResult
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.Value
import java.math.BigDecimal
import java.security.SecureRandom

data class NumberPattern(
    override val typeAlias: String? = null,
    val minLength: Int = 1,
    val maxLength: Int = Int.MAX_VALUE,
    val minimum: BigDecimal? = null,
    val exclusiveMinimum: Boolean = false,
    val maximum: BigDecimal? = null,
    val exclusiveMaximum: Boolean = false,
    override val example: String? = null,
    val isDoubleFormat: Boolean = false,
    internal val boundaryTestingEnabled: Boolean = false
) : Pattern, ScalarType, HasDefaultExample {
    private fun minValueIsSet() = minimum != null
    private fun maxValueIsSet() = maximum != null
    private fun minAndMaxValuesNotSet() = minimum == null && maximum == null
    private val lowerBound = if (isDoubleFormat) BigDecimal(-Double.MAX_VALUE) else BigDecimal(Int.MIN_VALUE)
    private val upperBound = if (isDoubleFormat) BigDecimal(Double.MAX_VALUE) else BigDecimal(Int.MAX_VALUE)
    private val smallInc = BigDecimal("1")

    private val effectiveMax =
        if (maximum != null) {
            if (exclusiveMaximum) maximum - smallInc else maximum
        } else {
            upperBound
        }

    private val effectiveMin =
        if (minimum != null) {
            if (exclusiveMinimum) minimum + smallInc else minimum
        } else {
            lowerBound
        }

    init {
        if (minLength < 1) throw IllegalArgumentException("minLength $minLength cannot be less than 1")
        if (maxLength < minLength) throw IllegalArgumentException("maxLength $maxLength cannot be less than minLength $minLength")
        if (minimum != null && maximum != null) {
            if (minimum > maximum) throw IllegalArgumentException("minimum $minimum cannot be greater than maximum $maximum")
            if (effectiveMin > effectiveMax) throw IllegalArgumentException("effective minimum $effectiveMin cannot be greater than effective maximum $effectiveMax after applying exclusiveMinimum and exclusiveMaximum")
        }
    }

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if (sampleData?.hasTemplate() == true)
            return Result.Success()

        if (sampleData !is NumberValue)
            return dataTypeMismatchResult(this, sampleData, resolver.mismatchMessages)

        if (sampleData.toStringLiteral().length < minLength)
            return constraintMismatchResult("number with minLength $minLength", sampleData, resolver.mismatchMessages)

        if (sampleData.toStringLiteral().length > maxLength)
            return constraintMismatchResult("number with maxLength $maxLength", sampleData, resolver.mismatchMessages)

        val sampleNumber = BigDecimal(sampleData.number.toString())

        if (sampleNumber < effectiveMin)
            return constraintMismatchResult("number >= $effectiveMin", sampleData, resolver.mismatchMessages)

        if (sampleNumber > effectiveMax)
            return constraintMismatchResult("number <= $effectiveMax", sampleData, resolver.mismatchMessages)

        return Result.Success()
    }

    override fun generate(resolver: Resolver): Value {
        val exampleValue = resolver.resolveExample(example, this)
        if (exampleValue != null) return exampleValue

        if (minAndMaxValuesNotSet()) {
            val length = when {
                minLength > 3 -> minLength
                maxLength < 3 -> maxLength
                else -> 3
            }
            return if (isDoubleFormat)
                NumberValue(randomNumber(length).toDouble())
            else
                NumberValue(randomNumber(length))
        }

        val number = if (isDoubleFormat)
            SecureRandom().nextDouble(effectiveMin.toDouble(), effectiveMax.toDouble())
        else
            SecureRandom().nextInt(effectiveMin.toInt(), effectiveMax.toInt())
        return NumberValue(number)
    }

    private fun randomNumber(minLength: Int): Int {
        val first = randomPositiveDigit().toString()
        val rest = (1 until minLength).joinToString("") { randomDigit() }

        val stringNumber = "$first$rest"

        return stringNumber.toInt()
    }

    private fun randomDigit() = SecureRandom().nextInt(10).toString()

    private fun randomPositiveDigit() = (SecureRandom().nextInt(9) + 1)

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> {
        val values = mutableListOf<HasValue<Pattern>>()

        val messageForTestFromThisObject =
            if (minAndMaxValuesNotSet()) {
                ""
            } else {
                "is set to a value within the specified bounds"
            }

        values.add(HasValue(this, messageForTestFromThisObject))

        if (minValueIsSet() || boundaryTestingEnabled) {
            val minimumValueAnnotation =
                if (minValueIsSet()) {
                    if (exclusiveMinimum) "is set to a value just within exclusive minimum '$effectiveMin'" else "is set to the smallest possible value '$effectiveMin'"
                } else {
                    "is set to the lowest possible value when no minimum is set"
                }

            values.add(HasValue(ExactValuePattern(NumberValue(effectiveMin)), minimumValueAnnotation))
        }

        if (maxValueIsSet() || boundaryTestingEnabled) {
            val maximumValueAnnotation =
                if (maxValueIsSet()) {
                    if (exclusiveMaximum) "is set to a value just within exclusive maximum '$effectiveMax'" else "is set to the largest possible value"
                } else {
                    "is set to the largest possible value when no maximum is set"
                }

            values.add(HasValue(ExactValuePattern(NumberValue(effectiveMax)), maximumValueAnnotation))
        }

        return values.asSequence().distinct()
    }

    override fun newBasedOn(resolver: Resolver): Sequence<Pattern> = sequenceOf(this)

    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> {
        val current = this

        return sequence {
            if (config.withDataTypeNegatives) {
                yieldAll(scalarAnnotation(current, sequenceOf(NullPattern, BooleanPattern(), StringPattern())))
            }
            val negativeForMinimumValue: Sequence<ReturnValue<Pattern>> =
                negativeRangeValues(minValueIsSet(), effectiveMin - smallInc, "is set to a value lesser than minimum value '$effectiveMin'")
            val negativeForMaximumValue: Sequence<ReturnValue<Pattern>> =
                negativeRangeValues(maxValueIsSet(), effectiveMax + smallInc, "is set to a value greater than maximum value '$effectiveMax'")

            yieldAll(negativeForMinimumValue + negativeForMaximumValue)
        }
    }

    private fun negativeRangeValues(condition: Boolean, number: BigDecimal, message: String): Sequence<ReturnValue<Pattern>> {
        return if (condition) {
            sequenceOf(HasValue(ExactValuePattern(NumberValue(number)), message))
        } else
            emptySequence()
    }

    override fun parse(value: String, resolver: Resolver): Value = attemptParse(this, value, resolver.mismatchMessages) {
        NumberValue(convertToNumber(value))
    }

    override fun encompasses(
        otherPattern: Pattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        return encompasses(this, otherPattern, thisResolver, otherResolver, typeStack)
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        return JSONArrayValue(valueList)
    }

    override val typeName: String = "number"

    override val pattern: Any = "(number)"
    override fun toString(): String = pattern.toString()
}

fun encompasses(
    thisPattern: Pattern,
    otherPattern: Pattern,
    thisResolver: Resolver,
    otherResolver: Resolver,
    typeStack: TypeStack
): Result =
    when {
        otherPattern is ExactValuePattern -> otherPattern.fitsWithin(
            thisPattern.patternSet(thisResolver),
            otherResolver,
            thisResolver,
            typeStack
        )

        otherPattern is SubSchemaCompositePattern -> {
            val failures: List<Result.Failure> = otherPattern.patternSet(otherResolver).map {
                thisPattern.encompasses(it, thisResolver, otherResolver)
            }.filterIsInstance<Result.Failure>()

            if (failures.isEmpty())
                Result.Success()
            else
                Result.Failure.fromFailures(failures)
        }

        otherPattern is EnumPattern -> {
            encompasses(thisPattern, otherPattern.pattern, thisResolver, otherResolver, typeStack)
        }

        thisPattern is ScalarType && otherPattern is ScalarType && thisPattern.matches(
            otherPattern.generate(
                otherResolver
            ), thisResolver
        ) is Result.Success -> Result.Success()

        otherPattern::class == thisPattern::class -> Result.Success()

        else -> patternMismatchResult(thisPattern, otherPattern, thisResolver.mismatchMessages)
    }
